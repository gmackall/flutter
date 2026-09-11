// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_REGISTRY_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_REGISTRY_H_

#include <sys/types.h>

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "flutter/fml/macros.h"
#include "flutter/fml/task_runner.h"
#include "flutter/fml/thread.h"
#include "flutter/shell/platform/android/adpf/android_adpf_policy.h"
#include "flutter/shell/platform/android/adpf/android_performance_hint_api.h"

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      Process-wide state shared by every
///             `AndroidGraphicsPerformanceController`.
///
///             The registry owns:
///
///             - The management sequence: one lazily started thread on which
///               all hint-session NDK calls are serialized, so that neither
///               raster work nor the platform thread waits on vendor IPC.
///             - The immutable platform capability evaluation, performed once
///               and cached for the life of the process. An unsupported device
///               never re-attempts resolution or creation.
///             - Per-application graphics-pipeline thread accounting. The
///               platform limit applies to the whole process, so independent
///               execution groups coordinate here and count unique thread ids.
///             - The display cadence the embedding intends to render at, and
///               its observers.
///
///             There is one process instance; tests construct their own with
///             injected collaborators.
///
class AndroidPerformanceHintRegistry {
 public:
  struct PlatformCapabilities {
    bool api_available = false;
    bool manager_available = false;
    bool sessions = false;
    bool surface_binding = false;
    bool graphics_pipeline = false;
    bool auto_cpu_timing = false;
    bool auto_gpu_timing = false;
    int max_graphics_pipeline_threads = 0;

    /// Whether every capability the automatic path requires is present.
    bool IsComplete() const;

    /// A short, comma separated list of what is missing. Empty if complete.
    std::string DescribeMissing() const;
  };

  using ApiFactory =
      std::function<std::shared_ptr<AndroidPerformanceHintApi>()>;
  using RefreshRateObserver = std::function<void(double frame_rate)>;

  static AndroidPerformanceHintRegistry& GetProcessInstance();

  //----------------------------------------------------------------------------
  /// @param[in]  api_factory        Creates the API wrapper on first use, on
  ///                                the management sequence.
  /// @param[in]  device_identity    The device identity used by the denylist.
  /// @param[in]  device_api_level   The running Android API level.
  /// @param[in]  management_runner  The sequence for NDK calls. May be null,
  ///                                in which case a thread is started on first
  ///                                use.
  ///
  AndroidPerformanceHintRegistry(
      ApiFactory api_factory,
      AndroidDeviceIdentity device_identity,
      int device_api_level,
      fml::RefPtr<fml::TaskRunner> management_runner);

  ~AndroidPerformanceHintRegistry();

  const AndroidDeviceIdentity& GetDeviceIdentity() const;

  int GetDeviceApiLevel() const;

  //----------------------------------------------------------------------------
  /// @brief      The management sequence. Starts the management thread on
  ///             first use; call only once cheaper eligibility checks passed.
  ///
  fml::RefPtr<fml::TaskRunner> GetManagementRunner();

  //----------------------------------------------------------------------------
  /// @brief      Resolves the API, acquires the manager and queries every
  ///             required feature. Performed once; the result is immutable.
  ///
  /// @note       Management sequence only.
  ///
  const PlatformCapabilities& EvaluateCapabilities();

  /// Management sequence only, after `EvaluateCapabilities`.
  const std::shared_ptr<AndroidPerformanceHintApi>& GetApi() const;

  /// Management sequence only, after `EvaluateCapabilities`.
  APerformanceHintManager* GetManager() const;

  //----------------------------------------------------------------------------
  /// @brief      Records that session creation with automatic timing reported
  ///             ENOTSUP. The device is treated as lacking automatic timing
  ///             from then on, so no controller retries creation.
  ///
  /// @note       Management sequence only.
  ///
  void MarkAutomaticTimingUnsupported();

  //----------------------------------------------------------------------------
  /// @brief      Reserves `tids` for `owner` if the union of every owner's
  ///             reservation fits within the per-application graphics
  ///             pipeline thread limit. Replaces any prior reservation held by
  ///             `owner`; on failure `owner` holds no reservation.
  ///
  bool ReserveGraphicsPipelineThreads(const void* owner,
                                      const std::vector<pid_t>& tids);

  void ReleaseGraphicsPipelineThreads(const void* owner);

  /// The number of unique thread ids currently reserved.
  size_t GetReservedGraphicsPipelineThreadCount() const;

  //----------------------------------------------------------------------------
  /// @brief      Sets the cadence the embedding intends to render at, in
  ///             frames per second, as reported by the display. This is the
  ///             single owner of that value for the ADPF integration.
  ///
  void SetDisplayRefreshRate(double frame_rate);

  double GetDisplayRefreshRate() const;

  uint64_t AddRefreshRateObserver(RefreshRateObserver observer);

  void RemoveRefreshRateObserver(uint64_t token);

  //----------------------------------------------------------------------------
  /// @brief      Blocks until every task queued on the management sequence so
  ///             far has run. Tests only.
  ///
  void FlushManagementRunnerForTesting();

 private:
  const ApiFactory api_factory_;
  const AndroidDeviceIdentity device_identity_;
  const int device_api_level_;

  mutable std::mutex mutex_;
  std::unique_ptr<fml::Thread> management_thread_;
  fml::RefPtr<fml::TaskRunner> management_runner_;
  std::map<const void*, std::vector<pid_t>> thread_reservations_;
  double display_refresh_rate_ = 0;
  uint64_t next_observer_token_ = 1;
  std::map<uint64_t, RefreshRateObserver> refresh_rate_observers_;

  // Management sequence only.
  bool capabilities_evaluated_ = false;
  PlatformCapabilities capabilities_;
  std::shared_ptr<AndroidPerformanceHintApi> api_;
  APerformanceHintManager* manager_ = nullptr;

  size_t CountUniqueThreadsLocked(const void* excluded_owner,
                                  const std::vector<pid_t>& extra) const;

  FML_DISALLOW_COPY_AND_ASSIGN(AndroidPerformanceHintRegistry);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_REGISTRY_H_
