// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_performance_hint_manager.h"

#include <android/api-level.h>
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <mutex>
#include <utility>

#include "flutter/fml/native_library.h"
#include "flutter/fml/time/time_point.h"

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
using APerformanceHint_setThreads_fn = int (*)(APerformanceHintSession*,
                                               const int32_t*,
                                               size_t);
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
  APerformanceHintManager* manager = nullptr;
  APerformanceHintSession* session = nullptr;
  AWorkDuration* work_duration = nullptr;
  int64_t applied_target_duration_ns = 0;
  std::vector<int32_t> current_tids;

  // State for coalescing multi-engine (FlutterEngineGroup / Spawn) or
  // resubmitted frame reports on the same thread within a single vsync period.
  int64_t last_work_period_start_ns = 0;
  int64_t last_total_duration_ns = 0;
  int64_t last_cpu_duration_ns = 0;
  int64_t last_gpu_duration_ns = 0;
  int64_t last_recreate_attempt_ns = 0;

  APerformanceHint_createSession_fn create_session = nullptr;
  APerformanceHint_reportActualWorkDuration2_fn report_actual_work_duration2 =
      nullptr;
  APerformanceHint_updateTargetWorkDuration_fn update_target_work_duration =
      nullptr;
  APerformanceHint_setThreads_fn set_threads = nullptr;
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
    if (work_duration && release_work_duration) {
      release_work_duration(work_duration);
      work_duration = nullptr;
    }
  }

  void CloseSessionLocked() {
    if (session && close_session) {
      close_session(session);
      session = nullptr;
    }
  }

  bool EnsureSessionLocked() {
    if (session) {
      return true;
    }
    if (!manager || !create_session || current_tids.empty() ||
        applied_target_duration_ns <= 0) {
      return false;
    }
    const int64_t now_ns =
        fml::TimePoint::Now().ToEpochDelta().ToNanoseconds();
    constexpr int64_t kRecreateBackoffNs = 1'000'000'000LL;  // 1 second
    if (last_recreate_attempt_ns > 0 &&
        now_ns - last_recreate_attempt_ns < kRecreateBackoffNs) {
      return false;
    }
    last_recreate_attempt_ns = now_ns;
    session = create_session(manager, current_tids.data(), current_tids.size(),
                             applied_target_duration_ns);
    return session != nullptr;
  }
};

std::unique_ptr<AndroidPerformanceHintManager>
AndroidPerformanceHintManager::Create(const std::vector<int32_t>& tids,
                                      int64_t target_duration_ns) {
  if (tids.empty() || target_duration_ns <= 0) {
    return nullptr;
  }

  // Require Android 15+ (API 35+), which introduces AWorkDuration and
  // APerformanceHint_reportActualWorkDuration2 with explicit work-period start
  // timestamps and CPU/GPU duration breakdown.
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
  AWorkDuration_create_fn create_work_duration = nullptr;
  auto impl = std::make_unique<Impl>();
  if (!ResolveSymbol(lib_android, "APerformanceHint_getManager", get_manager) ||
      !ResolveSymbol(lib_android, "APerformanceHint_createSession",
                     impl->create_session) ||
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

  ResolveSymbol(lib_android, "APerformanceHint_setThreads", impl->set_threads);

  impl->manager = get_manager();
  if (!impl->manager) {
    return nullptr;
  }

  impl->session = impl->create_session(impl->manager, tids.data(), tids.size(),
                                       target_duration_ns);
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
  impl->current_tids = tids;

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
  if (!impl_->EnsureSessionLocked() || !impl_->work_duration ||
      !impl_->report_actual_work_duration2) {
    return;
  }

  // If multiple spawned engines (FlutterEngineGroup) sharing this thread or a
  // resubmitted frame report work within the same half-vsync window, accumulate
  // their durations so the PowerHAL sees the thread's combined work for that
  // vsync rather than under-boosting on partial per-engine slices.
  if (impl_->last_work_period_start_ns > 0 &&
      impl_->applied_target_duration_ns > 0 &&
      std::abs(work_period_start_ns - impl_->last_work_period_start_ns) <
          impl_->applied_target_duration_ns / 2) {
    work_period_start_ns =
        std::min(work_period_start_ns, impl_->last_work_period_start_ns);
    actual_total_duration_ns += impl_->last_total_duration_ns;
    actual_cpu_duration_ns = std::min(
        actual_total_duration_ns,
        impl_->last_cpu_duration_ns + actual_cpu_duration_ns);
    actual_gpu_duration_ns += impl_->last_gpu_duration_ns;
  } else if (impl_->last_work_period_start_ns > 0 &&
             work_period_start_ns <= impl_->last_work_period_start_ns) {
    work_period_start_ns = impl_->last_work_period_start_ns + 1;
  }

  impl_->last_work_period_start_ns = work_period_start_ns;
  impl_->last_total_duration_ns = actual_total_duration_ns;
  impl_->last_cpu_duration_ns = actual_cpu_duration_ns;
  impl_->last_gpu_duration_ns = actual_gpu_duration_ns;

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
    // Use a 5% deadband so sub-millisecond refresh rate jitter is ignored while
    // real display mode transitions (including 90 Hz <-> 80 Hz = 11.1%) update
    // the session target.
    constexpr double kTargetChangeThreshold = 0.05;
    if (delta < static_cast<int64_t>(impl_->applied_target_duration_ns *
                                     kTargetChangeThreshold)) {
      return;
    }
  }
  if (!impl_->EnsureSessionLocked()) {
    impl_->applied_target_duration_ns = target_duration_ns;
    return;
  }
  if (impl_->session && impl_->update_target_work_duration) {
    const int result =
        impl_->update_target_work_duration(impl_->session, target_duration_ns);
    if (result == EPIPE) {
      impl_->CloseSessionLocked();
      impl_->applied_target_duration_ns = target_duration_ns;
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

bool AndroidPerformanceHintManager::SetThreads(
    const std::vector<int32_t>& tids) {
  if (!impl_ || tids.empty()) {
    return false;
  }
  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (impl_->current_tids == tids) {
    return true;
  }
  if (!impl_->EnsureSessionLocked() || !impl_->set_threads) {
    return false;
  }
  const int result =
      impl_->set_threads(impl_->session, tids.data(), tids.size());
  if (result == EPIPE) {
    impl_->CloseSessionLocked();
    impl_->current_tids = tids;
    return false;
  }
  if (result == 0) {
    impl_->current_tids = tids;
    return true;
  }
  return false;
}

std::vector<int32_t> AndroidPerformanceHintManager::GetThreads() const {
  if (!impl_) {
    return {};
  }
  std::lock_guard<std::mutex> lock(impl_->mutex);
  return impl_->current_tids;
}

}  // namespace flutter
