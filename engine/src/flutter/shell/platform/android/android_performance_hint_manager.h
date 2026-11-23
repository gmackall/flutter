// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_ANDROID_PERFORMANCE_HINT_MANAGER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_ANDROID_PERFORMANCE_HINT_MANAGER_H_

#include <android/performance_hint.h>

#include <cstdint>
#include <memory>
#include <string_view>
#include <vector>

#include "flutter/fml/macros.h"
#include "flutter/fml/native_library.h"

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      Manages an Android Dynamic Performance Framework (ADPF)
///             APerformanceHintSession for reporting per-stage target and
///             actual work durations to the Android PowerHAL.
///
///             All instance methods are thread-safe after construction.
///
class AndroidPerformanceHintManager {
 public:
  /// Function table of NDK ADPF symbols, exposed for deterministic unit
  /// testing.
  struct Api {
    decltype(&APerformanceHint_getManager) get_manager = nullptr;
    decltype(&APerformanceHint_createSession) create_session = nullptr;
    decltype(&APerformanceHint_reportActualWorkDuration2)
        report_actual_work_duration2 = nullptr;
    decltype(&APerformanceHint_updateTargetWorkDuration)
        update_target_work_duration = nullptr;
    decltype(&APerformanceHint_closeSession) close_session = nullptr;

    decltype(&AWorkDuration_create) create_work_duration = nullptr;
    decltype(&AWorkDuration_release) release_work_duration = nullptr;
    decltype(&AWorkDuration_setWorkPeriodStartTimestampNanos)
        set_work_period_start = nullptr;
    decltype(&AWorkDuration_setActualTotalDurationNanos)
        set_actual_total_duration = nullptr;
    decltype(&AWorkDuration_setActualCpuDurationNanos) set_actual_cpu_duration =
        nullptr;
    decltype(&AWorkDuration_setActualGpuDurationNanos) set_actual_gpu_duration =
        nullptr;
  };

  /// Returns true if `api_level` (35+) and the `debug.hwui.use_hint_manager`
  /// system property value indicate that ADPF should be enabled.
  static bool ShouldUseAdpf(int api_level, std::string_view hwui_prop);

  /// Creates an ADPF session for `tids` with the given initial frame budget in
  /// nanoseconds. Returns nullptr if arguments are invalid, the device is
  /// below API 35, HWUI disables ADPF, or the OS/PowerHAL cannot create a
  /// session.
  static std::unique_ptr<AndroidPerformanceHintManager> Create(
      const std::vector<int32_t>& tids,
      int64_t target_duration_ns);

  /// Creates a manager backed by an injected `Api` function table for testing.
  static std::unique_ptr<AndroidPerformanceHintManager> CreateForTesting(
      const std::vector<int32_t>& tids,
      int64_t target_duration_ns,
      const Api& api);

  ~AndroidPerformanceHintManager();

  /// Reports actual work duration for a completed frame stage.
  /// `work_period_start_ns` must be a positive CLOCK_MONOTONIC timestamp in
  /// nanoseconds; `actual_total_duration_ns` must be positive; CPU and GPU
  /// durations must be non-negative with at least one positive.
  void ReportActualWorkDuration(int64_t work_period_start_ns,
                                int64_t actual_total_duration_ns,
                                int64_t actual_cpu_duration_ns,
                                int64_t actual_gpu_duration_ns = 0);

  /// Updates the session's target frame duration in nanoseconds when it
  /// changes by at least 5% from the currently applied target.
  void UpdateTargetWorkDuration(int64_t target_duration_ns);

  /// Returns the currently applied target duration in nanoseconds, or 0 if
  /// the session has been closed due to an IPC error.
  int64_t GetTargetWorkDuration() const;

 private:
  struct Impl;

  static std::unique_ptr<AndroidPerformanceHintManager> CreateWithApi(
      const std::vector<int32_t>& tids,
      int64_t target_duration_ns,
      const Api& api,
      fml::RefPtr<fml::NativeLibrary> lib_android);

  explicit AndroidPerformanceHintManager(std::unique_ptr<Impl> impl);

  std::unique_ptr<Impl> impl_;

  FML_DISALLOW_COPY_AND_ASSIGN(AndroidPerformanceHintManager);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_ANDROID_PERFORMANCE_HINT_MANAGER_H_
