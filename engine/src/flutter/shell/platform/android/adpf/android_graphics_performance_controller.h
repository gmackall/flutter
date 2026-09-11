// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_GRAPHICS_PERFORMANCE_CONTROLLER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_GRAPHICS_PERFORMANCE_CONTROLLER_H_

#include <sys/types.h>

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "flutter/common/task_runners.h"
#include "flutter/fml/macros.h"
#include "flutter/fml/task_runner.h"
#include "flutter/shell/platform/android/adpf/android_adpf_policy.h"
#include "flutter/shell/platform/android/adpf/android_performance_hint_api.h"
#include "flutter/shell/platform/android/adpf/android_performance_hint_registry.h"
#include "flutter/shell/platform/android/surface/android_output_producer.h"

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      Owns the automatic ADPF graphics-pipeline hint session for one
///             execution group: the engines that share a `ThreadHost` and
///             therefore the same UI and raster threads.
///
///             Engines register a contribution (visibility, main output,
///             overlay outputs). The controller reconciles the union of active
///             contributions with the platform: it captures the thread ids
///             that currently execute the UI and raster task runners,
///             associates every live Flutter output producer, and creates one
///             session in graphics-pipeline mode with automatic CPU and GPU
///             timing. It never reports durations or targets.
///
///             All NDK calls run on the registry's management sequence.
///             Callers never block on session work. Updates are coalesced by
///             generation; stale reconciliations are discarded.
///
///             State transitions are logged once per change and exposed
///             through `GetDiagnostics()`.
///
class AndroidGraphicsPerformanceController final
    : public std::enable_shared_from_this<
          AndroidGraphicsPerformanceController> {
 public:
  enum class State {
    /// Disabled by policy, API level, capabilities, topology or denylist.
    kIneligible,
    /// Eligible, but no visible engine has an associated output.
    kWaitingForOutput,
    /// A session matches the current output and thread generation.
    kActive,
    /// The old session was retired; the latest snapshot is being applied.
    kReconfiguring,
    /// Creation or an update failed; no session. Retried at the next
    /// lifecycle change within the attempt budget.
    kFailed,
    /// The controller was destroyed; no further operations are accepted.
    kClosed,
  };

  enum class InactiveReason {
    kNone,
    kDisabledByPolicy,
    kApiLevelTooLow,
    kDenylisted,
    kApiUnavailable,
    kManagerUnavailable,
    kMissingCapability,
    kNoOutput,
    kThreadCaptureFailed,
    kThreadLimitExceeded,
    kSessionCreationFailed,
    kRetryBudgetExhausted,
    kClosed,
  };

  static const char* StateName(State state);

  static const char* ReasonName(InactiveReason reason);

  struct Diagnostics {
    State state = State::kIneligible;
    InactiveReason reason = InactiveReason::kNone;
    /// The denylist rule that matched, if any.
    std::string denylist_rule;
    /// The workload generation the current state describes.
    uint64_t generation = 0;
    size_t thread_count = 0;
    size_t window_count = 0;
    size_t surface_control_count = 0;
    uint32_t sessions_created = 0;
    uint32_t sessions_closed = 0;
    uint32_t output_updates = 0;
    uint32_t operational_failures = 0;
    /// The intended cadence last published on the session's outputs, in
    /// frames per second. Zero when nothing is published.
    double published_frame_rate = 0;
  };

  //----------------------------------------------------------------------------
  /// @brief      One engine's membership in the execution group's workload.
  ///             Destroying the registration removes the contribution.
  ///
  ///             All methods may be called from any thread and never block on
  ///             session work.
  ///
  class Registration {
   public:
    ~Registration();

    /// Whether the engine currently presents. Hidden engines contribute
    /// nothing, even when their surfaces survive in a cache.
    void SetVisible(bool visible);

    /// The producer of the engine's main window. Null when detached.
    void SetMainOutput(std::shared_ptr<const AndroidOutputProducer> output);

    /// The complete set of live Flutter-rendered overlay producers.
    void SetOverlayOutputs(AndroidOutputProducerList outputs);

   private:
    friend class AndroidGraphicsPerformanceController;

    Registration(std::weak_ptr<AndroidGraphicsPerformanceController> controller,
                 uint64_t id);

    const std::weak_ptr<AndroidGraphicsPerformanceController> controller_;
    const uint64_t id_;

    FML_DISALLOW_COPY_AND_ASSIGN(Registration);
  };

  //----------------------------------------------------------------------------
  /// @param[in]  policy        The resolved application policy.
  /// @param[in]  task_runners  The execution group's task runners. The UI and
  ///                           raster runners define the critical path.
  /// @param[in]  registry      Process-wide shared state. Null selects the
  ///                           process instance.
  ///
  static std::shared_ptr<AndroidGraphicsPerformanceController> Create(
      const AutomaticAdpfPolicy& policy,
      const TaskRunners& task_runners,
      AndroidPerformanceHintRegistry* registry = nullptr);

  ~AndroidGraphicsPerformanceController();

  std::unique_ptr<Registration> RegisterEngine();

  //----------------------------------------------------------------------------
  /// @brief      Notifies the controller that the threads executing the UI or
  ///             raster task runners may have changed: the platform/UI merge
  ///             after launch, or a raster/platform merge or unmerge. The
  ///             session is retired and recreated from the new membership.
  ///
  void NotifyThreadTopologyChanged();

  Diagnostics GetDiagnostics() const;

  //----------------------------------------------------------------------------
  /// @brief      Blocks until every pending capture and reconciliation has
  ///             completed. Tests only; requires that the calling thread is
  ///             not one of the execution group's threads.
  ///
  void WaitForIdleForTesting();

 private:
  struct Contribution {
    bool visible = false;
    std::shared_ptr<const AndroidOutputProducer> main_output;
    AndroidOutputProducerList overlay_outputs;
  };

  struct ThreadCapture;

  struct ActiveSession {
    APerformanceHintSession* session = nullptr;
    std::vector<pid_t> tids;
    AndroidOutputProducerList outputs;
    /// Frame rate published per output id.
    std::map<uint64_t, double> published_frame_rates;
  };

  // Session ownership lives outside the controller so that shutdown can close
  // the session on the management sequence after the controller is gone.
  struct SessionHolder {
    std::shared_ptr<AndroidPerformanceHintApi> api;
    AndroidPerformanceHintRegistry* registry = nullptr;
    std::optional<ActiveSession> session;

    void Close();
    void PublishFrameRate(double frame_rate);
  };

  static constexpr uint32_t kMaxOperationalFailures = 3;

  const AutomaticAdpfPolicy policy_;
  const TaskRunners task_runners_;
  AndroidPerformanceHintRegistry* const registry_;
  fml::RefPtr<fml::TaskRunner> management_runner_;
  const std::shared_ptr<SessionHolder> holder_;
  uint64_t refresh_rate_observer_token_ = 0;

  // Guarded by mutex_.
  mutable std::mutex mutex_;
  bool closed_ = false;
  bool eligible_ = true;
  uint64_t next_registration_id_ = 1;
  std::map<uint64_t, Contribution> contributions_;
  uint64_t generation_ = 0;
  Diagnostics diagnostics_;

  // Management sequence only.
  uint64_t reconciling_generation_ = 0;
  uint64_t reconciled_generation_ = 0;
  std::vector<pid_t> captured_tids_;
  uint64_t captured_generation_ = 0;
  std::shared_ptr<ThreadCapture> capture_in_flight_;
  std::vector<pid_t> rejected_tids_;
  uint32_t operational_failures_ = 0;
  bool surface_replacement_unsupported_ = false;

  AndroidGraphicsPerformanceController(
      const AutomaticAdpfPolicy& policy,
      const TaskRunners& task_runners,
      AndroidPerformanceHintRegistry* registry);

  void EvaluateStaticEligibility();

  void RemoveContribution(uint64_t id);

  void UpdateContribution(uint64_t id,
                          const std::function<void(Contribution&)>& update);

  void ScheduleReconcileLocked();

  // Management sequence.
  void Reconcile();

  void RequestThreadCapture(uint64_t generation);

  void OnThreadCaptured(const std::shared_ptr<ThreadCapture>& capture,
                        size_t role,
                        pid_t tid);

  bool ApplyOutputsToSession(const AndroidOutputProducerList& outputs);

  void CreateSession(const std::vector<pid_t>& tids,
                     const AndroidOutputProducerList& outputs);

  void CloseSession();

  void PublishFrameRate();

  void OnDisplayRefreshRateChanged(double frame_rate);

  void SetStatus(State state,
                 InactiveReason reason,
                 const std::string& detail = "");

  bool IsIdleOnManagementSequence();

  FML_DISALLOW_COPY_AND_ASSIGN(AndroidGraphicsPerformanceController);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_GRAPHICS_PERFORMANCE_CONTROLLER_H_
