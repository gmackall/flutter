// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_performance_hint_manager.h"

#include <android/api-level.h>
#include <cerrno>
#include <cstdlib>
#include <mutex>
#include <utility>

#include "flutter/fml/native_library.h"

namespace flutter {

namespace {

// Opaque handles matching NDK <android/performance_hint.h>
struct APerformanceHintManager;
struct APerformanceHintSession;
struct AWorkDuration;

using APerformanceHint_getManager_fn = APerformanceHintManager* (*)();
using APerformanceHint_createSession_fn =
    APerformanceHintSession* (*)(APerformanceHintManager*,
                                 const int32_t*,
                                 size_t,
                                 int64_t);
using APerformanceHint_reportActualWorkDuration2_fn =
    int (*)(APerformanceHintSession*, AWorkDuration*);
using APerformanceHint_updateTargetWorkDuration_fn =
    int (*)(APerformanceHintSession*, int64_t);
using APerformanceHint_closeSession_fn = void (*)(APerformanceHintSession*);

using AWorkDuration_create_fn = AWorkDuration* (*)();
using AWorkDuration_release_fn = void (*)(AWorkDuration*);
using AWorkDuration_setWorkPeriodStartTimestampNanos_fn =
    void (*)(AWorkDuration*, int64_t);
using AWorkDuration_setActualTotalDurationNanos_fn = void (*)(AWorkDuration*,
                                                              int64_t);
using AWorkDuration_setActualCpuDurationNanos_fn = void (*)(AWorkDuration*,
                                                            int64_t);
using AWorkDuration_setActualGpuDurationNanos_fn = void (*)(AWorkDuration*,
                                                            int64_t);

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
  fml::RefPtr<fml::NativeLibrary> lib_android;
  APerformanceHintSession* session = nullptr;
  AWorkDuration* work_duration = nullptr;
  int64_t applied_target_duration_ns = 0;

  APerformanceHint_reportActualWorkDuration2_fn report_actual_work_duration2 =
      nullptr;
  APerformanceHint_updateTargetWorkDuration_fn update_target_work_duration =
      nullptr;
  APerformanceHint_closeSession_fn close_session = nullptr;

  AWorkDuration_release_fn release_work_duration = nullptr;
  AWorkDuration_setWorkPeriodStartTimestampNanos_fn set_work_period_start =
      nullptr;
  AWorkDuration_setActualTotalDurationNanos_fn set_actual_total_duration =
      nullptr;
  AWorkDuration_setActualCpuDurationNanos_fn set_actual_cpu_duration = nullptr;
  AWorkDuration_setActualGpuDurationNanos_fn set_actual_gpu_duration = nullptr;

  ~Impl() {
    std::lock_guard<std::mutex> lock(mutex);
    CloseSessionLocked();
  }

  void CloseSessionLocked() {
    if (session && close_session) {
      close_session(session);
      session = nullptr;
    }
    if (work_duration && release_work_duration) {
      release_work_duration(work_duration);
      work_duration = nullptr;
    }
  }
};

std::unique_ptr<AndroidPerformanceHintManager>
AndroidPerformanceHintManager::Create(const std::vector<int32_t>& tids,
                                      int64_t target_duration_ns) {
  if (tids.empty() || target_duration_ns <= 0) {
    return nullptr;
  }

  // Require Android 15+ (API 35+), which introduces AWorkDuration and
  // APerformanceHint_reportActualWorkDuration2.
  const int api_level = android_get_device_api_level();
  if (api_level < 35) {
    return nullptr;
  }

  fml::RefPtr<fml::NativeLibrary> lib_android =
      fml::NativeLibrary::Create("libandroid.so");
  if (!lib_android) {
    return nullptr;
  }

  APerformanceHint_getManager_fn get_manager = nullptr;
  APerformanceHint_createSession_fn create_session = nullptr;
  AWorkDuration_create_fn create_work_duration = nullptr;
  auto impl = std::make_unique<Impl>();
  if (!ResolveSymbol(lib_android, "APerformanceHint_getManager", get_manager) ||
      !ResolveSymbol(lib_android, "APerformanceHint_createSession",
                     create_session) ||
      !ResolveSymbol(lib_android, "APerformanceHint_updateTargetWorkDuration",
                     impl->update_target_work_duration) ||
      !ResolveSymbol(lib_android, "APerformanceHint_closeSession",
                     impl->close_session) ||
      !ResolveSymbol(lib_android, "APerformanceHint_reportActualWorkDuration2",
                     impl->report_actual_work_duration2) ||
      !ResolveSymbol(lib_android, "AWorkDuration_create",
                     create_work_duration) ||
      !ResolveSymbol(lib_android, "AWorkDuration_release",
                     impl->release_work_duration) ||
      !ResolveSymbol(lib_android,
                     "AWorkDuration_setWorkPeriodStartTimestampNanos",
                     impl->set_work_period_start) ||
      !ResolveSymbol(lib_android, "AWorkDuration_setActualTotalDurationNanos",
                     impl->set_actual_total_duration) ||
      !ResolveSymbol(lib_android, "AWorkDuration_setActualCpuDurationNanos",
                     impl->set_actual_cpu_duration) ||
      !ResolveSymbol(lib_android, "AWorkDuration_setActualGpuDurationNanos",
                     impl->set_actual_gpu_duration)) {
    return nullptr;
  }

  APerformanceHintManager* manager = get_manager();
  if (!manager) {
    return nullptr;
  }

  impl->session =
      create_session(manager, tids.data(), tids.size(), target_duration_ns);
  if (!impl->session) {
    return nullptr;
  }

  impl->work_duration = create_work_duration();
  if (!impl->work_duration) {
    impl->CloseSessionLocked();
    return nullptr;
  }

  impl->lib_android = std::move(lib_android);
  impl->applied_target_duration_ns = target_duration_ns;

  return std::unique_ptr<AndroidPerformanceHintManager>(
      new AndroidPerformanceHintManager(std::move(impl)));
}

AndroidPerformanceHintManager::AndroidPerformanceHintManager(
    std::unique_ptr<Impl> impl)
    : impl_(std::move(impl)) {}

AndroidPerformanceHintManager::~AndroidPerformanceHintManager() = default;

void AndroidPerformanceHintManager::ReportActualWorkDuration(
    int64_t work_period_start_ns,
    int64_t actual_total_duration_ns,
    int64_t actual_cpu_duration_ns,
    int64_t actual_gpu_duration_ns) {
  if (!impl_ || work_period_start_ns <= 0 || actual_total_duration_ns <= 0 ||
      actual_cpu_duration_ns < 0 || actual_gpu_duration_ns < 0 ||
      (actual_cpu_duration_ns == 0 && actual_gpu_duration_ns == 0)) {
    return;
  }

  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (!impl_->session || !impl_->work_duration ||
      !impl_->report_actual_work_duration2) {
    return;
  }

  impl_->set_work_period_start(impl_->work_duration, work_period_start_ns);
  impl_->set_actual_total_duration(impl_->work_duration,
                                   actual_total_duration_ns);
  impl_->set_actual_cpu_duration(impl_->work_duration, actual_cpu_duration_ns);
  impl_->set_actual_gpu_duration(impl_->work_duration, actual_gpu_duration_ns);

  if (impl_->report_actual_work_duration2(impl_->session,
                                          impl_->work_duration) == EPIPE) {
    impl_->CloseSessionLocked();
  }
}

void AndroidPerformanceHintManager::UpdateTargetWorkDuration(
    int64_t target_duration_ns) {
  if (!impl_ || target_duration_ns <= 0) {
    return;
  }
  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (impl_->applied_target_duration_ns > 0) {
    const int64_t delta =
        std::abs(target_duration_ns - impl_->applied_target_duration_ns);
    constexpr double kTargetChangeThreshold = 0.15;
    if (delta < static_cast<int64_t>(impl_->applied_target_duration_ns *
                                     kTargetChangeThreshold)) {
      return;
    }
  }
  if (impl_->session && impl_->update_target_work_duration) {
    const int result =
        impl_->update_target_work_duration(impl_->session, target_duration_ns);
    if (result == EPIPE) {
      impl_->CloseSessionLocked();
    } else if (result == 0) {
      impl_->applied_target_duration_ns = target_duration_ns;
    }
  }
}

int64_t AndroidPerformanceHintManager::GetTargetWorkDuration() const {
  if (!impl_) {
    return 0;
  }
  std::lock_guard<std::mutex> lock(impl_->mutex);
  return impl_->applied_target_duration_ns;
}

}  // namespace flutter
