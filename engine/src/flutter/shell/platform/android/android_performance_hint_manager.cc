// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_performance_hint_manager.h"

#include <android/api-level.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <optional>

#include "flutter/fml/logging.h"
#include "flutter/fml/native_library.h"
#include "flutter/fml/time/time_point.h"

namespace flutter {

namespace {
// SessionHint values from AOSP <private/performance_hint_private.h> exported
// via APerformanceHint_sendHint in libandroid.so.
constexpr int32_t kSessionHintCpuLoadUp = 0;
constexpr int32_t kSessionHintCpuLoadReset = 2;
constexpr int32_t kSessionHintGpuLoadUp = 5;

// On Pixel devices (/vendor/etc/powerhint.json), ADPF session votes go stale
// after StaleTimeFactor (15.0) * targetDuration (~6.25ms at 120Hz) = ~93.75ms
// of inactivity, dropping uclamp.min to 0. Waking the session with
// CPU_LOAD_RESET after >= 90ms of idle restores uclamp.min before the first
// frame's build and raster work begins.
constexpr int64_t kIdleResetThresholdNs = 90'000'000;  // 90 ms

// libandroid.so enforces a 100ms rate limit (kSendHintTimeout) per hint type.
constexpr int64_t kSendHintCooldownNs = 100'000'000;  // 100 ms

// Matches JankCheckTimeFactor (1.2) in /vendor/etc/powerhint.json.
constexpr double kWorkloadSpikeFactor = 1.2;

enum class AdpfRuntimeMode {
  kImproved,
  kMaxStage8_3ms,
  kDualSession8_3ms,
  kTarget6_25ms,
  kTarget8_3ms,
  kPhase4,
  kOff,
};

AdpfRuntimeMode GetAdpfRuntimeMode() {
  char prop[PROP_VALUE_MAX] = {0};
  if (__system_property_get("debug.flutter.adpf_mode", prop) > 0) {
    if (std::strcmp(prop, "off") == 0 || std::strcmp(prop, "control") == 0 ||
        std::strcmp(prop, "none") == 0) {
      return AdpfRuntimeMode::kOff;
    }
    if (std::strcmp(prop, "phase4") == 0) {
      return AdpfRuntimeMode::kPhase4;
    }
    if (std::strcmp(prop, "max_stage_8.3ms") == 0) {
      return AdpfRuntimeMode::kMaxStage8_3ms;
    }
    if (std::strcmp(prop, "dual_session_8.3ms") == 0) {
      return AdpfRuntimeMode::kDualSession8_3ms;
    }
    if (std::strcmp(prop, "target_8.3ms") == 0) {
      return AdpfRuntimeMode::kTarget8_3ms;
    }
    if (std::strcmp(prop, "target_6.25ms") == 0) {
      return AdpfRuntimeMode::kTarget6_25ms;
    }
  }
  return AdpfRuntimeMode::kImproved;
}

bool ModeEnablesHints(AdpfRuntimeMode mode) {
  return mode == AdpfRuntimeMode::kImproved ||
         mode == AdpfRuntimeMode::kMaxStage8_3ms ||
         mode == AdpfRuntimeMode::kDualSession8_3ms ||
         mode == AdpfRuntimeMode::kTarget6_25ms ||
         mode == AdpfRuntimeMode::kTarget8_3ms;
}
}  // namespace

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
using APerformanceHint_reportActualWorkDuration_fn =
    int (*)(APerformanceHintSession*, int64_t);
using APerformanceHint_reportActualWorkDuration2_fn =
    int (*)(APerformanceHintSession*, AWorkDuration*);
using APerformanceHint_updateTargetWorkDuration_fn =
    int (*)(APerformanceHintSession*, int64_t);
using APerformanceHint_sendHint_fn = int (*)(APerformanceHintSession*, int32_t);
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

struct AndroidPerformanceHintManager::Impl {
  mutable std::mutex mutex;
  fml::RefPtr<fml::NativeLibrary> lib_android;
  APerformanceHintSession* session = nullptr;
  AWorkDuration* work_duration = nullptr;
  int64_t applied_target_duration_ns = 0;
  AdpfRuntimeMode mode = AdpfRuntimeMode::kImproved;
  std::atomic<int64_t> last_activity_timestamp_ns{0};
  int64_t last_reset_hint_ns = 0;
  int64_t last_up_hint_ns = 0;
  int64_t last_gpu_up_hint_ns = 0;
  int64_t last_cpu_duration_ns = 0;
  int64_t last_gpu_duration_ns = 0;

  APerformanceHint_reportActualWorkDuration_fn report_actual_work_duration =
      nullptr;
  APerformanceHint_reportActualWorkDuration2_fn report_actual_work_duration2 =
      nullptr;
  APerformanceHint_updateTargetWorkDuration_fn update_target_work_duration =
      nullptr;
  APerformanceHint_sendHint_fn send_hint = nullptr;
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

  const AdpfRuntimeMode mode = GetAdpfRuntimeMode();
  if (mode == AdpfRuntimeMode::kOff) {
    FML_LOG(INFO) << "ADPF disabled via debug.flutter.adpf_mode=off";
    return nullptr;
  }

  // Support Android 12+ (API 31+) where APerformanceHintManager was introduced,
  // preferring AWorkDuration + APerformanceHint_reportActualWorkDuration2 on
  // Android 15+ (API 35+) and falling back to v1 reportActualWorkDuration on
  // API 31-34.
  const int api_level = android_get_device_api_level();
  if (api_level < 31) {
    return nullptr;
  }

  fml::RefPtr<fml::NativeLibrary> lib_android =
      fml::NativeLibrary::Create("libandroid.so");
  if (!lib_android) {
    return nullptr;
  }

  const std::optional<APerformanceHint_getManager_fn> get_manager =
      lib_android->ResolveFunction<APerformanceHint_getManager_fn>(
          "APerformanceHint_getManager");
  const std::optional<APerformanceHint_createSession_fn> create_session =
      lib_android->ResolveFunction<APerformanceHint_createSession_fn>(
          "APerformanceHint_createSession");
  const std::optional<APerformanceHint_reportActualWorkDuration_fn>
      report_actual_v1 =
          lib_android
              ->ResolveFunction<APerformanceHint_reportActualWorkDuration_fn>(
                  "APerformanceHint_reportActualWorkDuration");
  const std::optional<APerformanceHint_reportActualWorkDuration2_fn>
      report_actual2 =
          lib_android
              ->ResolveFunction<APerformanceHint_reportActualWorkDuration2_fn>(
                  "APerformanceHint_reportActualWorkDuration2");
  const std::optional<APerformanceHint_updateTargetWorkDuration_fn>
      update_target =
          lib_android
              ->ResolveFunction<APerformanceHint_updateTargetWorkDuration_fn>(
                  "APerformanceHint_updateTargetWorkDuration");
  const std::optional<APerformanceHint_sendHint_fn> send_hint =
      lib_android->ResolveFunction<APerformanceHint_sendHint_fn>(
          "APerformanceHint_sendHint");
  const std::optional<APerformanceHint_closeSession_fn> close_session =
      lib_android->ResolveFunction<APerformanceHint_closeSession_fn>(
          "APerformanceHint_closeSession");

  if (!get_manager.has_value() || !create_session.has_value() ||
      !update_target.has_value() || !close_session.has_value()) {
    FML_LOG(WARNING)
        << "ADPF PerformanceHint base APIs not available in libandroid.so";
    return nullptr;
  }

  const std::optional<AWorkDuration_create_fn> work_duration_create =
      lib_android->ResolveFunction<AWorkDuration_create_fn>(
          "AWorkDuration_create");
  const std::optional<AWorkDuration_release_fn> work_duration_release =
      lib_android->ResolveFunction<AWorkDuration_release_fn>(
          "AWorkDuration_release");
  const std::optional<AWorkDuration_setWorkPeriodStartTimestampNanos_fn>
      set_work_period_start = lib_android->ResolveFunction<
          AWorkDuration_setWorkPeriodStartTimestampNanos_fn>(
          "AWorkDuration_setWorkPeriodStartTimestampNanos");
  const std::optional<AWorkDuration_setActualTotalDurationNanos_fn>
      set_actual_total_duration =
          lib_android
              ->ResolveFunction<AWorkDuration_setActualTotalDurationNanos_fn>(
                  "AWorkDuration_setActualTotalDurationNanos");
  const std::optional<AWorkDuration_setActualCpuDurationNanos_fn>
      set_actual_cpu_duration =
          lib_android
              ->ResolveFunction<AWorkDuration_setActualCpuDurationNanos_fn>(
                  "AWorkDuration_setActualCpuDurationNanos");
  const std::optional<AWorkDuration_setActualGpuDurationNanos_fn>
      set_actual_gpu_duration =
          lib_android
              ->ResolveFunction<AWorkDuration_setActualGpuDurationNanos_fn>(
                  "AWorkDuration_setActualGpuDurationNanos");

  const bool has_v2 = api_level >= 35 && report_actual2.has_value() &&
                      work_duration_create.has_value() &&
                      work_duration_release.has_value() &&
                      set_work_period_start.has_value() &&
                      set_actual_total_duration.has_value() &&
                      set_actual_cpu_duration.has_value() &&
                      set_actual_gpu_duration.has_value();

  if (!has_v2 && !report_actual_v1.has_value()) {
    FML_LOG(WARNING)
        << "ADPF PerformanceHint reporting APIs not available in libandroid.so";
    return nullptr;
  }

  APerformanceHintManager* manager = get_manager.value()();
  if (!manager) {
    FML_LOG(WARNING) << "APerformanceHint_getManager returned nullptr";
    return nullptr;
  }

  APerformanceHintSession* session = create_session.value()(
      manager, tids.data(), tids.size(), target_duration_ns);
  if (!session) {
    FML_LOG(WARNING) << "Failed to create APerformanceHintSession for "
                     << tids.size() << " threads";
    return nullptr;
  }

  AWorkDuration* work_duration = nullptr;
  if (has_v2) {
    work_duration = work_duration_create.value()();
    if (!work_duration) {
      FML_LOG(WARNING) << "Failed to allocate AWorkDuration";
      close_session.value()(session);
      return nullptr;
    }
  }

  std::unique_ptr<Impl> impl = std::make_unique<Impl>();
  impl->lib_android = std::move(lib_android);
  impl->session = session;
  impl->work_duration = work_duration;
  impl->applied_target_duration_ns = target_duration_ns;
  impl->mode = mode;
  impl->last_activity_timestamp_ns.store(
      fml::TimePoint::Now().ToEpochDelta().ToNanoseconds(),
      std::memory_order_relaxed);
  if (report_actual_v1.has_value()) {
    impl->report_actual_work_duration = report_actual_v1.value();
  }
  if (has_v2) {
    impl->report_actual_work_duration2 = report_actual2.value();
    impl->release_work_duration = work_duration_release.value();
    impl->set_work_period_start = set_work_period_start.value();
    impl->set_actual_total_duration = set_actual_total_duration.value();
    impl->set_actual_cpu_duration = set_actual_cpu_duration.value();
    impl->set_actual_gpu_duration = set_actual_gpu_duration.value();
  }
  if (send_hint.has_value()) {
    impl->send_hint = send_hint.value();
  }
  impl->update_target_work_duration = update_target.value();
  impl->close_session = close_session.value();

  FML_LOG(INFO) << "Created ADPF PerformanceHintSession (mode="
                << (mode == AdpfRuntimeMode::kPhase4 ? "phase4" : "improved")
                << ", v2=" << has_v2 << ") with target " << target_duration_ns
                << " ns (" << (1e9 / target_duration_ns) << " Hz) for "
                << tids.size() << " threads";

  return std::unique_ptr<AndroidPerformanceHintManager>(
      new AndroidPerformanceHintManager(std::move(impl)));
}

AndroidPerformanceHintManager::AndroidPerformanceHintManager(
    std::unique_ptr<Impl> impl)
    : impl_(std::move(impl)) {}

AndroidPerformanceHintManager::~AndroidPerformanceHintManager() = default;

void AndroidPerformanceHintManager::NotifyWorkloadReset() {
  if (!impl_ || !ModeEnablesHints(impl_->mode)) {
    return;
  }
  const int64_t now_ns = fml::TimePoint::Now().ToEpochDelta().ToNanoseconds();
  const int64_t prev_activity_ns =
      impl_->last_activity_timestamp_ns.load(std::memory_order_relaxed);
  if (prev_activity_ns > 0 &&
      (now_ns - prev_activity_ns) < kIdleResetThresholdNs) {
    return;
  }

  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (!impl_->session || !impl_->send_hint) {
    return;
  }
  const int64_t latest_activity_ns =
      impl_->last_activity_timestamp_ns.load(std::memory_order_relaxed);
  if (latest_activity_ns > 0 &&
      (now_ns - latest_activity_ns) < kIdleResetThresholdNs) {
    return;
  }
  impl_->last_activity_timestamp_ns.store(now_ns, std::memory_order_relaxed);
  if ((now_ns - impl_->last_reset_hint_ns) >= kSendHintCooldownNs) {
    impl_->last_reset_hint_ns = now_ns;
    impl_->send_hint(impl_->session, kSessionHintCpuLoadReset);
  }
}

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
  const int64_t now_ns = fml::TimePoint::Now().ToEpochDelta().ToNanoseconds();
  impl_->last_activity_timestamp_ns.store(now_ns, std::memory_order_relaxed);

  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (!impl_->session) {
    return;
  }

  // If a frame suddenly spikes past the target budget (> 1.2x) following a
  // within-budget frame, send a proactive CPU_LOAD_UP hint so the PowerHAL
  // immediately raises uclamp.min for the next frame instead of waiting
  // multiple janky frames for heuristic boost to ramp up. Avoid sending
  // CPU_LOAD_UP on consecutive heavy frames when the PID / heuristic boost is
  // already elevated above UclampMin_LoadUp (480).
  const int64_t prev_cpu_duration_ns = impl_->last_cpu_duration_ns;
  impl_->last_cpu_duration_ns = actual_cpu_duration_ns;
  if (ModeEnablesHints(impl_->mode) && impl_->send_hint &&
      impl_->applied_target_duration_ns > 0 && prev_cpu_duration_ns > 0 &&
      prev_cpu_duration_ns <= impl_->applied_target_duration_ns &&
      actual_cpu_duration_ns >
          static_cast<int64_t>(impl_->applied_target_duration_ns *
                               kWorkloadSpikeFactor) &&
      (now_ns - impl_->last_up_hint_ns) >= kSendHintCooldownNs) {
    impl_->last_up_hint_ns = now_ns;
    impl_->send_hint(impl_->session, kSessionHintCpuLoadUp);
  }

  if (actual_gpu_duration_ns > 0) {
    const int64_t prev_gpu_duration_ns = impl_->last_gpu_duration_ns;
    impl_->last_gpu_duration_ns = actual_gpu_duration_ns;
    if (ModeEnablesHints(impl_->mode) && impl_->send_hint &&
        impl_->applied_target_duration_ns > 0 && prev_gpu_duration_ns > 0 &&
        prev_gpu_duration_ns <= impl_->applied_target_duration_ns &&
        actual_gpu_duration_ns >
            static_cast<int64_t>(impl_->applied_target_duration_ns *
                                 kWorkloadSpikeFactor) &&
        (now_ns - impl_->last_gpu_up_hint_ns) >= kSendHintCooldownNs) {
      impl_->last_gpu_up_hint_ns = now_ns;
      impl_->send_hint(impl_->session, kSessionHintGpuLoadUp);
    }
  }

  if (impl_->work_duration && impl_->report_actual_work_duration2) {
    impl_->set_work_period_start(impl_->work_duration, work_period_start_ns);
    impl_->set_actual_total_duration(impl_->work_duration,
                                     actual_total_duration_ns);
    impl_->set_actual_cpu_duration(impl_->work_duration,
                                   actual_cpu_duration_ns);
    impl_->set_actual_gpu_duration(impl_->work_duration,
                                   actual_gpu_duration_ns);

    int result = impl_->report_actual_work_duration2(impl_->session,
                                                     impl_->work_duration);
    if (result != 0) {
      if (result == EPIPE) {
        FML_LOG(WARNING)
            << "ADPF session disconnected (EPIPE). Closing session.";
        impl_->CloseSessionLocked();
      } else {
        FML_DLOG(WARNING)
            << "APerformanceHint_reportActualWorkDuration2 returned " << result;
      }
    }
  } else if (impl_->report_actual_work_duration) {
    const int64_t duration_to_report = actual_cpu_duration_ns > 0
                                           ? actual_cpu_duration_ns
                                           : actual_total_duration_ns;
    int result =
        impl_->report_actual_work_duration(impl_->session, duration_to_report);
    if (result != 0 && result == EPIPE) {
      FML_LOG(WARNING) << "ADPF session disconnected (EPIPE). Closing session.";
      impl_->CloseSessionLocked();
    }
  }
}

void AndroidPerformanceHintManager::UpdateTargetWorkDuration(
    int64_t target_duration_ns) {
  if (!impl_ || target_duration_ns <= 0) {
    return;
  }
  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (impl_->applied_target_duration_ns > 0) {
    int64_t delta =
        std::abs(target_duration_ns - impl_->applied_target_duration_ns);
    // Suppress microsecond timer jitter to avoid issuing a synchronous Binder
    // IPC to the vendor PowerHAL on the raster thread every frame. Only update
    // when target duration shifts by more than 15% (e.g. 144Hz <-> 120Hz <->
    // 60Hz).
    constexpr double kTargetChangeThreshold = 0.15;
    if (delta < static_cast<int64_t>(impl_->applied_target_duration_ns *
                                     kTargetChangeThreshold)) {
      return;
    }
  }
  if (impl_->session && impl_->update_target_work_duration) {
    int result =
        impl_->update_target_work_duration(impl_->session, target_duration_ns);
    if (result != 0) {
      if (result == EPIPE) {
        FML_LOG(WARNING)
            << "ADPF session disconnected (EPIPE). Closing session.";
        impl_->CloseSessionLocked();
      } else {
        FML_DLOG(WARNING)
            << "APerformanceHint_updateTargetWorkDuration returned " << result;
      }
    } else {
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
