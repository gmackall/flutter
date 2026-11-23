// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_performance_hint_manager.h"

#include <android/api-level.h>
#include <sys/system_properties.h>

#include <algorithm>
#include <cerrno>
#include <cstddef>
#include <cstdlib>
#include <mutex>
#include <utility>

#include "flutter/fml/logging.h"

namespace flutter {

namespace {

constexpr int kMinAdpfApiLevel = 35;
constexpr char kHwuiUseHintManagerProperty[] = "debug.hwui.use_hint_manager";

bool ValidateCreateArgs(const std::vector<int32_t>& tids,
                        int64_t target_duration_ns) {
  if (tids.empty() || target_duration_ns <= 0) {
    return false;
  }
  return std::all_of(tids.begin(), tids.end(),
                     [](int32_t tid) { return tid > 0; });
}

bool HasRequiredSymbols(const AndroidPerformanceHintManager::Api& api) {
  return api.get_manager && api.create_session &&
         api.report_actual_work_duration2 && api.update_target_work_duration &&
         api.close_session && api.create_work_duration &&
         api.release_work_duration && api.set_work_period_start &&
         api.set_actual_total_duration && api.set_actual_cpu_duration &&
         api.set_actual_gpu_duration;
}

template <typename Fn>
bool ResolveSymbol(const fml::RefPtr<fml::NativeLibrary>& lib,
                   const char* symbol_name,
                   Fn& out_fn) {
  auto resolved = lib->ResolveFunction<Fn>(symbol_name);
  if (!resolved.has_value()) {
    return false;
  }
  out_fn = resolved.value();
  return true;
}

}  // namespace

struct AndroidPerformanceHintManager::Impl {
  mutable std::mutex mutex;
  // Keeps libandroid.so loaded until after CloseSessionLocked() runs in
  // ~Impl().
  fml::RefPtr<fml::NativeLibrary> lib_android;
  Api api;

  APerformanceHintSession* session = nullptr;
  AWorkDuration* work_duration = nullptr;
  int64_t applied_target_duration_ns = 0;

  ~Impl() { CloseSessionLocked(); }

  void CloseSessionLocked() {
    if (session) {
      api.close_session(session);
      session = nullptr;
    }
    if (work_duration) {
      api.release_work_duration(work_duration);
      work_duration = nullptr;
    }
    applied_target_duration_ns = 0;
  }
};

bool AndroidPerformanceHintManager::ShouldUseAdpf(int api_level,
                                                  std::string_view hwui_prop) {
  // Require Android 15+ (API 35+), which introduces AWorkDuration and
  // APerformanceHint_reportActualWorkDuration2, and gate on AOSP HWUI's
  // `debug.hwui.use_hint_manager` property (case-sensitive android::base::
  // ParseBool truthy values with default false).
  return api_level >= kMinAdpfApiLevel &&
         (hwui_prop == "1" || hwui_prop == "y" || hwui_prop == "yes" ||
          hwui_prop == "on" || hwui_prop == "true");
}

std::unique_ptr<AndroidPerformanceHintManager>
AndroidPerformanceHintManager::Create(const std::vector<int32_t>& tids,
                                      int64_t target_duration_ns) {
  if (!ValidateCreateArgs(tids, target_duration_ns)) {
    return nullptr;
  }

  const int api_level = android_get_device_api_level();
  if (api_level < kMinAdpfApiLevel) {
    return nullptr;
  }

  char prop_value[PROP_VALUE_MAX] = {};
  const int prop_len =
      __system_property_get(kHwuiUseHintManagerProperty, prop_value);
  const std::string_view hwui_prop(
      prop_value,
      prop_len > 0 ? static_cast<size_t>(std::min(prop_len, PROP_VALUE_MAX - 1))
                   : 0u);
  if (!ShouldUseAdpf(api_level, hwui_prop)) {
    return nullptr;
  }

  fml::RefPtr<fml::NativeLibrary> lib_android =
      fml::NativeLibrary::Create("libandroid.so");
  if (!lib_android) {
    return nullptr;
  }

  Api api;
  if (!ResolveSymbol(lib_android, "APerformanceHint_getManager",
                     api.get_manager) ||
      !ResolveSymbol(lib_android, "APerformanceHint_createSession",
                     api.create_session) ||
      !ResolveSymbol(lib_android, "APerformanceHint_updateTargetWorkDuration",
                     api.update_target_work_duration) ||
      !ResolveSymbol(lib_android, "APerformanceHint_closeSession",
                     api.close_session) ||
      !ResolveSymbol(lib_android, "APerformanceHint_reportActualWorkDuration2",
                     api.report_actual_work_duration2) ||
      !ResolveSymbol(lib_android, "AWorkDuration_create",
                     api.create_work_duration) ||
      !ResolveSymbol(lib_android, "AWorkDuration_release",
                     api.release_work_duration) ||
      !ResolveSymbol(lib_android,
                     "AWorkDuration_setWorkPeriodStartTimestampNanos",
                     api.set_work_period_start) ||
      !ResolveSymbol(lib_android, "AWorkDuration_setActualTotalDurationNanos",
                     api.set_actual_total_duration) ||
      !ResolveSymbol(lib_android, "AWorkDuration_setActualCpuDurationNanos",
                     api.set_actual_cpu_duration) ||
      !ResolveSymbol(lib_android, "AWorkDuration_setActualGpuDurationNanos",
                     api.set_actual_gpu_duration)) {
    return nullptr;
  }

  return CreateWithApi(tids, target_duration_ns, api, std::move(lib_android));
}

std::unique_ptr<AndroidPerformanceHintManager>
AndroidPerformanceHintManager::CreateForTesting(
    const std::vector<int32_t>& tids,
    int64_t target_duration_ns,
    const Api& api) {
  return CreateWithApi(tids, target_duration_ns, api, /*lib_android=*/nullptr);
}

std::unique_ptr<AndroidPerformanceHintManager>
AndroidPerformanceHintManager::CreateWithApi(
    const std::vector<int32_t>& tids,
    int64_t target_duration_ns,
    const Api& api,
    fml::RefPtr<fml::NativeLibrary> lib_android) {
  if (!ValidateCreateArgs(tids, target_duration_ns) ||
      !HasRequiredSymbols(api)) {
    return nullptr;
  }

  auto impl = std::make_unique<Impl>();
  impl->lib_android = std::move(lib_android);
  impl->api = api;

  APerformanceHintManager* manager = impl->api.get_manager();
  if (!manager) {
    return nullptr;
  }

  impl->session = impl->api.create_session(manager, tids.data(), tids.size(),
                                           target_duration_ns);
  if (!impl->session) {
    return nullptr;
  }

  impl->work_duration = impl->api.create_work_duration();
  if (!impl->work_duration) {
    // ~Impl() closes impl->session before releasing lib_android.
    return nullptr;
  }

  impl->applied_target_duration_ns = target_duration_ns;

  return std::unique_ptr<AndroidPerformanceHintManager>(
      new AndroidPerformanceHintManager(std::move(impl)));
}

AndroidPerformanceHintManager::AndroidPerformanceHintManager(
    std::unique_ptr<Impl> impl)
    : impl_(std::move(impl)) {
  FML_DCHECK(impl_);
}

AndroidPerformanceHintManager::~AndroidPerformanceHintManager() = default;

void AndroidPerformanceHintManager::ReportActualWorkDuration(
    int64_t work_period_start_ns,
    int64_t actual_total_duration_ns,
    int64_t actual_cpu_duration_ns,
    int64_t actual_gpu_duration_ns) {
  if (work_period_start_ns <= 0 || actual_total_duration_ns <= 0 ||
      actual_cpu_duration_ns < 0 || actual_gpu_duration_ns < 0 ||
      (actual_cpu_duration_ns == 0 && actual_gpu_duration_ns == 0)) {
    return;
  }

  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (!impl_->session || !impl_->work_duration) {
    return;
  }

  impl_->api.set_work_period_start(impl_->work_duration, work_period_start_ns);
  impl_->api.set_actual_total_duration(impl_->work_duration,
                                       actual_total_duration_ns);
  impl_->api.set_actual_cpu_duration(impl_->work_duration,
                                     actual_cpu_duration_ns);
  impl_->api.set_actual_gpu_duration(impl_->work_duration,
                                     actual_gpu_duration_ns);

  if (impl_->api.report_actual_work_duration2(impl_->session,
                                              impl_->work_duration) == EPIPE) {
    impl_->CloseSessionLocked();
  }
}

void AndroidPerformanceHintManager::UpdateTargetWorkDuration(
    int64_t target_duration_ns) {
  if (target_duration_ns <= 0) {
    return;
  }
  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (!impl_->session) {
    return;
  }
  FML_DCHECK(impl_->applied_target_duration_ns > 0);
  if (target_duration_ns == impl_->applied_target_duration_ns) {
    return;
  }

  const int64_t delta =
      std::abs(target_duration_ns - impl_->applied_target_duration_ns);
  // Use a 5% deadband so sub-millisecond refresh rate jitter is ignored while
  // real display mode transitions (including 90 Hz <-> 80 Hz = 11.1%) update
  // the session target. Round up applied / 20 so strictly < 5% is suppressed
  // and >= 5% is applied without floating-point rounding or int64 overflow.
  constexpr int64_t kDeadbandDivisor = 20;
  const int64_t threshold =
      (impl_->applied_target_duration_ns / kDeadbandDivisor) +
      ((impl_->applied_target_duration_ns % kDeadbandDivisor) != 0 ? 1 : 0);
  if (delta < threshold) {
    return;
  }

  const int result = impl_->api.update_target_work_duration(impl_->session,
                                                            target_duration_ns);
  if (result == EPIPE) {
    impl_->CloseSessionLocked();
  } else if (result == 0) {
    impl_->applied_target_duration_ns = target_duration_ns;
  }
}

int64_t AndroidPerformanceHintManager::GetTargetWorkDuration() const {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  return impl_->applied_target_duration_ns;
}

}  // namespace flutter
