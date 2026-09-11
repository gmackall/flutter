// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/adpf/android_graphics_performance_controller.h"

#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <sstream>

#include "flutter/fml/logging.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/trace_event.h"

namespace flutter {

namespace {

constexpr size_t kUiRole = 0;
constexpr size_t kRasterRole = 1;
constexpr size_t kRoleCount = 2;

using Producer = AndroidOutputProducer;

void SplitOutputs(const AndroidOutputProducerList& outputs,
                  std::vector<ANativeWindow*>& windows,
                  std::vector<ASurfaceControl*>& surface_controls) {
  for (const auto& output : outputs) {
    switch (output->kind()) {
      case Producer::Kind::kNativeWindow:
        windows.push_back(output->window());
        break;
      case Producer::Kind::kSurfaceControl:
        surface_controls.push_back(output->surface_control());
        break;
    }
  }
}

bool SameOutputs(const AndroidOutputProducerList& a,
                 const AndroidOutputProducerList& b) {
  if (a.size() != b.size()) {
    return false;
  }
  for (size_t i = 0; i < a.size(); i++) {
    if (a[i]->id() != b[i]->id()) {
      return false;
    }
  }
  return true;
}

bool ContainsOutput(const AndroidOutputProducerList& outputs, uint64_t id) {
  return std::any_of(outputs.begin(), outputs.end(),
                     [id](const auto& output) { return output->id() == id; });
}

const char* ErrnoName(int error) {
  switch (error) {
    case EINVAL:
      return "EINVAL";
    case EPIPE:
      return "EPIPE";
    case ENOTSUP:
      return "ENOTSUP";
    case EBUSY:
      return "EBUSY";
    case ENOMEM:
      return "ENOMEM";
    default:
      return "unknown";
  }
}

}  // namespace

struct AndroidGraphicsPerformanceController::ThreadCapture {
  uint64_t generation = 0;
  std::array<std::optional<pid_t>, kRoleCount> tids;

  bool IsComplete() const {
    return std::all_of(tids.begin(), tids.end(),
                       [](const auto& tid) { return tid.has_value(); });
  }
};

//------------------------------------------------------------------------------
// SessionHolder
//------------------------------------------------------------------------------

void AndroidGraphicsPerformanceController::SessionHolder::PublishFrameRate(
    double frame_rate) {
  if (!session) {
    return;
  }
  for (const auto& output : session->outputs) {
    auto published = session->published_frame_rates.find(output->id());
    const bool unchanged = published != session->published_frame_rates.end()
                               ? published->second == frame_rate
                               : frame_rate == 0;
    if (unchanged) {
      continue;
    }
    bool accepted = false;
    switch (output->kind()) {
      case Producer::Kind::kNativeWindow:
        accepted = api->SetNativeWindowFrameRate(
            output->window(), static_cast<float>(frame_rate));
        break;
      case Producer::Kind::kSurfaceControl:
        accepted = api->SetSurfaceControlFrameRate(
            output->surface_control(), static_cast<float>(frame_rate));
        break;
    }
    if (accepted) {
      session->published_frame_rates[output->id()] = frame_rate;
    }
  }
}

void AndroidGraphicsPerformanceController::SessionHolder::Close() {
  if (!session) {
    return;
  }
  // Withdraw the cadence votes before the session goes away so that the
  // outputs fall back to the platform's default behavior.
  PublishFrameRate(0);
  api->CloseSession(session->session);
  registry->ReleaseGraphicsPipelineThreads(this);
  // The retained outputs are released only now, after the session that was
  // associated with them is closed.
  session.reset();
}

//------------------------------------------------------------------------------
// Registration
//------------------------------------------------------------------------------

AndroidGraphicsPerformanceController::Registration::Registration(
    std::weak_ptr<AndroidGraphicsPerformanceController> controller,
    uint64_t id)
    : controller_(std::move(controller)), id_(id) {}

AndroidGraphicsPerformanceController::Registration::~Registration() {
  if (auto controller = controller_.lock()) {
    controller->RemoveContribution(id_);
  }
}

void AndroidGraphicsPerformanceController::Registration::SetVisible(
    bool visible) {
  if (auto controller = controller_.lock()) {
    controller->UpdateContribution(
        id_, [visible](Contribution& contribution) {
          contribution.visible = visible;
        });
  }
}

void AndroidGraphicsPerformanceController::Registration::SetMainOutput(
    std::shared_ptr<const AndroidOutputProducer> output) {
  if (auto controller = controller_.lock()) {
    controller->UpdateContribution(
        id_, [&output](Contribution& contribution) {
          contribution.main_output = std::move(output);
        });
  }
}

void AndroidGraphicsPerformanceController::Registration::SetOverlayOutputs(
    AndroidOutputProducerList outputs) {
  if (auto controller = controller_.lock()) {
    controller->UpdateContribution(
        id_, [&outputs](Contribution& contribution) {
          contribution.overlay_outputs = std::move(outputs);
        });
  }
}

//------------------------------------------------------------------------------
// AndroidGraphicsPerformanceController
//------------------------------------------------------------------------------

const char* AndroidGraphicsPerformanceController::StateName(State state) {
  switch (state) {
    case State::kIneligible:
      return "Ineligible";
    case State::kWaitingForOutput:
      return "WaitingForOutput";
    case State::kActive:
      return "Active";
    case State::kReconfiguring:
      return "Reconfiguring";
    case State::kFailed:
      return "Failed";
    case State::kClosed:
      return "Closed";
  }
  return "Unknown";
}

const char* AndroidGraphicsPerformanceController::ReasonName(
    InactiveReason reason) {
  switch (reason) {
    case InactiveReason::kNone:
      return "none";
    case InactiveReason::kDisabledByPolicy:
      return "disabled by policy";
    case InactiveReason::kApiLevelTooLow:
      return "API level too low";
    case InactiveReason::kDenylisted:
      return "device denylisted";
    case InactiveReason::kApiUnavailable:
      return "API unavailable";
    case InactiveReason::kManagerUnavailable:
      return "hint manager unavailable";
    case InactiveReason::kMissingCapability:
      return "missing capability";
    case InactiveReason::kNoOutput:
      return "no output";
    case InactiveReason::kThreadCaptureFailed:
      return "thread capture failed";
    case InactiveReason::kThreadLimitExceeded:
      return "graphics pipeline thread limit exceeded";
    case InactiveReason::kSessionCreationFailed:
      return "session creation failed";
    case InactiveReason::kRetryBudgetExhausted:
      return "retry budget exhausted";
    case InactiveReason::kClosed:
      return "closed";
  }
  return "unknown";
}

std::shared_ptr<AndroidGraphicsPerformanceController>
AndroidGraphicsPerformanceController::Create(
    const AutomaticAdpfPolicy& policy,
    const TaskRunners& task_runners,
    AndroidPerformanceHintRegistry* registry) {
  if (registry == nullptr) {
    registry = &AndroidPerformanceHintRegistry::GetProcessInstance();
  }
  auto controller = std::shared_ptr<AndroidGraphicsPerformanceController>(
      new AndroidGraphicsPerformanceController(policy, task_runners,
                                               registry));
  controller->EvaluateStaticEligibility();
  return controller;
}

AndroidGraphicsPerformanceController::AndroidGraphicsPerformanceController(
    const AutomaticAdpfPolicy& policy,
    const TaskRunners& task_runners,
    AndroidPerformanceHintRegistry* registry)
    : policy_(policy),
      task_runners_(task_runners),
      registry_(registry),
      holder_(std::make_shared<SessionHolder>()) {
  holder_->registry = registry_;
}

AndroidGraphicsPerformanceController::~AndroidGraphicsPerformanceController() {
  {
    std::scoped_lock lock(mutex_);
    closed_ = true;
    diagnostics_.state = State::kClosed;
    diagnostics_.reason = InactiveReason::kClosed;
  }
  if (refresh_rate_observer_token_ != 0) {
    registry_->RemoveRefreshRateObserver(refresh_rate_observer_token_);
  }
  if (management_runner_) {
    // The session lives on the management sequence. Close it there, after any
    // reconciliation that is still running, without referencing this
    // controller: nothing can resurrect a session once the holder is closed.
    management_runner_->PostTask([holder = holder_]() { holder->Close(); });
  }
}

void AndroidGraphicsPerformanceController::EvaluateStaticEligibility() {
  if (!policy_.enabled) {
    std::scoped_lock lock(mutex_);
    eligible_ = false;
    diagnostics_.state = State::kIneligible;
    diagnostics_.reason = InactiveReason::kDisabledByPolicy;
    return;
  }

  const int api_level = registry_->GetDeviceApiLevel();
  if (api_level < kAndroidAdpfMinimumApiLevel) {
    std::scoped_lock lock(mutex_);
    eligible_ = false;
    diagnostics_.state = State::kIneligible;
    diagnostics_.reason = InactiveReason::kApiLevelTooLow;
    FML_LOG(INFO) << "Android ADPF: enabled by policy but API level "
                  << api_level << " is below " << kAndroidAdpfMinimumApiLevel
                  << "; no session will be created.";
    return;
  }

  const auto rule = MatchAndroidAdpfDenylist(registry_->GetDeviceIdentity(),
                                             GetAndroidAdpfDenylist());
  if (rule.has_value()) {
    std::scoped_lock lock(mutex_);
    diagnostics_.denylist_rule = std::string(rule.value());
    if (!policy_.ignore_denylist) {
      eligible_ = false;
      diagnostics_.state = State::kIneligible;
      diagnostics_.reason = InactiveReason::kDenylisted;
      FML_LOG(INFO) << "Android ADPF: disabled by denylist rule "
                    << rule.value() << ".";
      return;
    }
    FML_LOG(WARNING) << "Android ADPF: denylist rule " << rule.value()
                     << " ignored by developer override.";
  }

  management_runner_ = registry_->GetManagementRunner();
  refresh_rate_observer_token_ = registry_->AddRefreshRateObserver(
      [weak = weak_from_this(),
       management = management_runner_](double frame_rate) {
        management->PostTask([weak, frame_rate]() {
          if (auto self = weak.lock()) {
            self->OnDisplayRefreshRateChanged(frame_rate);
          }
        });
      });
  SetStatus(State::kWaitingForOutput, InactiveReason::kNoOutput);
}

std::unique_ptr<AndroidGraphicsPerformanceController::Registration>
AndroidGraphicsPerformanceController::RegisterEngine() {
  std::scoped_lock lock(mutex_);
  const uint64_t id = next_registration_id_++;
  contributions_[id] = Contribution{};
  return std::unique_ptr<Registration>(
      new Registration(weak_from_this(), id));
}

void AndroidGraphicsPerformanceController::RemoveContribution(uint64_t id) {
  std::scoped_lock lock(mutex_);
  if (contributions_.erase(id) == 0 || closed_ || !eligible_) {
    return;
  }
  ScheduleReconcileLocked();
}

void AndroidGraphicsPerformanceController::UpdateContribution(
    uint64_t id,
    const std::function<void(Contribution&)>& update) {
  std::scoped_lock lock(mutex_);
  if (closed_ || !eligible_) {
    return;
  }
  auto found = contributions_.find(id);
  if (found == contributions_.end()) {
    return;
  }
  update(found->second);
  ScheduleReconcileLocked();
}

void AndroidGraphicsPerformanceController::ScheduleReconcileLocked() {
  generation_++;
  FML_DCHECK(management_runner_);
  management_runner_->PostTask([weak = weak_from_this()]() {
    if (auto self = weak.lock()) {
      self->Reconcile();
    }
  });
}

void AndroidGraphicsPerformanceController::NotifyThreadTopologyChanged() {
  std::scoped_lock lock(mutex_);
  if (closed_ || !eligible_) {
    return;
  }
  // A new generation always re-captures the thread ids, so the membership
  // is re-derived from the threads that actually run the task runners now.
  ScheduleReconcileLocked();
}

AndroidGraphicsPerformanceController::Diagnostics
AndroidGraphicsPerformanceController::GetDiagnostics() const {
  std::scoped_lock lock(mutex_);
  return diagnostics_;
}

//------------------------------------------------------------------------------
// Management sequence
//------------------------------------------------------------------------------

void AndroidGraphicsPerformanceController::Reconcile() {
  uint64_t generation = 0;
  AndroidOutputProducerList outputs;
  {
    std::scoped_lock lock(mutex_);
    if (closed_) {
      return;
    }
    generation = generation_;
    if (generation == reconciled_generation_) {
      // Coalesced: an earlier reconciliation already applied this snapshot.
      return;
    }
    for (const auto& [id, contribution] : contributions_) {
      if (!contribution.visible) {
        continue;
      }
      if (contribution.main_output) {
        outputs.push_back(contribution.main_output);
      }
      for (const auto& overlay : contribution.overlay_outputs) {
        if (overlay && !ContainsOutput(outputs, overlay->id())) {
          outputs.push_back(overlay);
        }
      }
    }
  }
  reconciling_generation_ = generation;

  TRACE_EVENT0("flutter", "AndroidAdpfReconcile");

  const auto& capabilities = registry_->EvaluateCapabilities();
  if (!holder_->api) {
    holder_->api = registry_->GetApi();
  }
  if (!capabilities.api_available) {
    CloseSession();
    SetStatus(State::kIneligible, InactiveReason::kApiUnavailable);
    reconciled_generation_ = generation;
    return;
  }
  if (!capabilities.manager_available) {
    CloseSession();
    SetStatus(State::kIneligible, InactiveReason::kManagerUnavailable);
    reconciled_generation_ = generation;
    return;
  }
  if (!capabilities.IsComplete()) {
    CloseSession();
    SetStatus(State::kIneligible, InactiveReason::kMissingCapability,
              capabilities.DescribeMissing());
    reconciled_generation_ = generation;
    return;
  }

  if (outputs.empty()) {
    // The last visible output went away. A surface surviving in a cache does
    // not keep a hidden workload active.
    CloseSession();
    SetStatus(State::kWaitingForOutput, InactiveReason::kNoOutput);
    reconciled_generation_ = generation;
    return;
  }

  if (captured_generation_ != generation) {
    // Membership is derived from the threads that execute the task runners
    // right now, not from the threads they started on.
    RequestThreadCapture(generation);
    return;
  }
  const std::vector<pid_t> tids = captured_tids_;
  if (tids.empty()) {
    CloseSession();
    SetStatus(State::kFailed, InactiveReason::kThreadCaptureFailed);
    reconciled_generation_ = generation;
    return;
  }

  if (holder_->session) {
    if (holder_->session->tids == tids) {
      if (SameOutputs(holder_->session->outputs, outputs)) {
        PublishFrameRate();
        SetStatus(State::kActive, InactiveReason::kNone);
        reconciled_generation_ = generation;
        return;
      }
      SetStatus(State::kReconfiguring, InactiveReason::kNone,
                "outputs changed");
      if (ApplyOutputsToSession(outputs)) {
        SetStatus(State::kActive, InactiveReason::kNone);
        reconciled_generation_ = generation;
        return;
      }
      // The session was retired; fall through to recreation.
    } else {
      SetStatus(State::kReconfiguring, InactiveReason::kNone,
                "thread topology changed");
      CloseSession();
    }
  }

  if (!rejected_tids_.empty() && rejected_tids_ == tids) {
    SetStatus(State::kIneligible, InactiveReason::kThreadLimitExceeded,
              "the platform rejected this thread topology");
    reconciled_generation_ = generation;
    return;
  }
  if (operational_failures_ >= kMaxOperationalFailures) {
    SetStatus(State::kFailed, InactiveReason::kRetryBudgetExhausted);
    reconciled_generation_ = generation;
    return;
  }

  CreateSession(tids, outputs);
  reconciled_generation_ = generation;
}

void AndroidGraphicsPerformanceController::RequestThreadCapture(
    uint64_t generation) {
  if (capture_in_flight_ && capture_in_flight_->generation == generation) {
    return;
  }
  auto capture = std::make_shared<ThreadCapture>();
  capture->generation = generation;
  capture_in_flight_ = capture;

  std::array<fml::RefPtr<fml::TaskRunner>, kRoleCount> runners;
  runners[kUiRole] = task_runners_.GetUITaskRunner();
  runners[kRasterRole] = task_runners_.GetRasterTaskRunner();
  for (size_t role = 0; role < kRoleCount; role++) {
    runners[role]->PostTask([weak = weak_from_this(), capture, role,
                             management = management_runner_]() {
      // On the thread that currently executes the runner. If the runner has
      // been merged into another thread, this is that thread.
      const pid_t tid = gettid();
      management->PostTask([weak, capture, role, tid]() {
        if (auto self = weak.lock()) {
          self->OnThreadCaptured(capture, role, tid);
        }
      });
    });
  }
}

void AndroidGraphicsPerformanceController::OnThreadCaptured(
    const std::shared_ptr<ThreadCapture>& capture,
    size_t role,
    pid_t tid) {
  capture->tids[role] = tid;
  if (!capture->IsComplete()) {
    return;
  }
  if (capture_in_flight_ != capture) {
    // Superseded by a newer generation's capture.
    return;
  }
  capture_in_flight_.reset();

  std::vector<pid_t> tids;
  for (const auto& captured : capture->tids) {
    if (captured.value() > 0) {
      tids.push_back(captured.value());
    }
  }
  // Roles that share a thread (merged UI and platform, merged raster and
  // platform) contribute a single thread id.
  std::sort(tids.begin(), tids.end());
  tids.erase(std::unique(tids.begin(), tids.end()), tids.end());

  captured_tids_ = std::move(tids);
  captured_generation_ = capture->generation;
  Reconcile();
}

bool AndroidGraphicsPerformanceController::ApplyOutputsToSession(
    const AndroidOutputProducerList& outputs) {
  FML_DCHECK(holder_->session);
  if (surface_replacement_unsupported_) {
    CloseSession();
    return false;
  }
  std::vector<ANativeWindow*> windows;
  std::vector<ASurfaceControl*> surface_controls;
  SplitOutputs(outputs, windows, surface_controls);

  TRACE_EVENT0("flutter", "AndroidAdpfUpdateOutputs");
  const int error = holder_->api->SetNativeSurfaces(
      holder_->session->session, windows, surface_controls);
  if (error == 0) {
    ActiveSession& session = *holder_->session;
    // Withdraw votes from producers that left the session while they are
    // still retained.
    for (const auto& previous : session.outputs) {
      if (ContainsOutput(outputs, previous->id())) {
        continue;
      }
      auto published = session.published_frame_rates.find(previous->id());
      if (published == session.published_frame_rates.end()) {
        continue;
      }
      if (published->second != 0) {
        switch (previous->kind()) {
          case Producer::Kind::kNativeWindow:
            holder_->api->SetNativeWindowFrameRate(previous->window(), 0);
            break;
          case Producer::Kind::kSurfaceControl:
            holder_->api->SetSurfaceControlFrameRate(
                previous->surface_control(), 0);
            break;
        }
      }
      session.published_frame_rates.erase(published);
    }
    session.outputs = outputs;
    {
      std::scoped_lock lock(mutex_);
      diagnostics_.output_updates++;
    }
    PublishFrameRate();
    return true;
  }

  if (error == ENOTSUP) {
    // Replacement is unsupported on this device; recreate from now on.
    surface_replacement_unsupported_ = true;
  } else {
    operational_failures_++;
  }
  FML_LOG(WARNING) << "Android ADPF: updating session outputs failed ("
                   << ErrnoName(error) << "); recreating the session.";
  CloseSession();
  return false;
}

void AndroidGraphicsPerformanceController::CreateSession(
    const std::vector<pid_t>& tids,
    const AndroidOutputProducerList& outputs) {
  FML_DCHECK(!holder_->session);
  const auto& capabilities = registry_->EvaluateCapabilities();
  const auto limit =
      static_cast<size_t>(capabilities.max_graphics_pipeline_threads);
  if (tids.size() > limit) {
    // Do not drop threads to fit: an incomplete critical path would be
    // measured incorrectly. Stay inactive and say why.
    std::ostringstream detail;
    detail << tids.size() << " threads exceed the device limit of " << limit;
    SetStatus(State::kIneligible, InactiveReason::kThreadLimitExceeded,
              detail.str());
    return;
  }
  if (!registry_->ReserveGraphicsPipelineThreads(holder_.get(), tids)) {
    SetStatus(State::kIneligible, InactiveReason::kThreadLimitExceeded,
              "other execution groups already use the application's "
              "graphics pipeline thread allowance");
    return;
  }

  AndroidPerformanceHintApi::SessionConfig config;
  config.tids = tids;
  SplitOutputs(outputs, config.windows, config.surface_controls);
  config.graphics_pipeline = true;
  config.auto_cpu_timing = true;
  config.auto_gpu_timing = true;

  auto result =
      holder_->api->CreateSession(registry_->GetManager(), config);

  if (result.error == 0 && result.session != nullptr) {
    holder_->session = ActiveSession{
        .session = result.session,
        .tids = tids,
        .outputs = outputs,
    };
    {
      std::scoped_lock lock(mutex_);
      diagnostics_.sessions_created++;
    }
    PublishFrameRate();
    SetStatus(State::kActive, InactiveReason::kNone);
    return;
  }

  // Every failure path: nothing may be left open, and the reservation is
  // returned to the process.
  if (result.session != nullptr) {
    holder_->api->CloseSession(result.session);
  }
  registry_->ReleaseGraphicsPipelineThreads(holder_.get());

  switch (result.error) {
    case EBUSY:
      // The platform counted more graphics pipeline threads in the process
      // than the limit allows (for example the framework's own). Sessions in
      // this state have undefined behavior, so none is kept. Do not retry
      // until the thread topology changes.
      rejected_tids_ = tids;
      SetStatus(State::kIneligible, InactiveReason::kThreadLimitExceeded,
                "EBUSY: too many graphics pipeline threads in the process");
      return;
    case ENOTSUP:
      // Cached for the process: an unsupported device never retries.
      registry_->MarkAutomaticTimingUnsupported();
      SetStatus(State::kIneligible, InactiveReason::kMissingCapability,
                "ENOTSUP from session creation");
      return;
    case EINVAL:
      // A configuration the platform considers invalid is an engine defect,
      // not a transient condition.
      operational_failures_ = kMaxOperationalFailures;
      FML_LOG(ERROR) << "Android ADPF: the platform rejected the session "
                        "configuration as invalid (EINVAL).";
      SetStatus(State::kFailed, InactiveReason::kSessionCreationFailed,
                "EINVAL");
      return;
    default:
      operational_failures_++;
      {
        std::scoped_lock lock(mutex_);
        diagnostics_.operational_failures = operational_failures_;
      }
      SetStatus(State::kFailed, InactiveReason::kSessionCreationFailed,
                ErrnoName(result.error));
      return;
  }
}

void AndroidGraphicsPerformanceController::CloseSession() {
  if (!holder_->session) {
    return;
  }
  TRACE_EVENT0("flutter", "AndroidAdpfCloseSession");
  holder_->Close();
  std::scoped_lock lock(mutex_);
  diagnostics_.sessions_closed++;
  diagnostics_.published_frame_rate = 0;
}

void AndroidGraphicsPerformanceController::PublishFrameRate() {
  if (!holder_->session) {
    return;
  }
  const double frame_rate = registry_->GetDisplayRefreshRate();
  if (frame_rate <= 0) {
    // The embedding has not reported a cadence; publish nothing rather than
    // a guess.
    return;
  }
  holder_->PublishFrameRate(frame_rate);
  std::scoped_lock lock(mutex_);
  diagnostics_.published_frame_rate = frame_rate;
}

void AndroidGraphicsPerformanceController::OnDisplayRefreshRateChanged(
    double /* frame_rate */) {
  if (!holder_->session) {
    return;
  }
  TRACE_EVENT0("flutter", "AndroidAdpfCadenceChanged");
  PublishFrameRate();
}

void AndroidGraphicsPerformanceController::SetStatus(
    State state,
    InactiveReason reason,
    const std::string& detail) {
  size_t thread_count = 0;
  size_t window_count = 0;
  size_t surface_control_count = 0;
  if (holder_->session) {
    thread_count = holder_->session->tids.size();
    for (const auto& output : holder_->session->outputs) {
      if (output->kind() == Producer::Kind::kNativeWindow) {
        window_count++;
      } else {
        surface_control_count++;
      }
    }
  }

  bool changed = false;
  {
    std::scoped_lock lock(mutex_);
    changed = diagnostics_.state != state || diagnostics_.reason != reason;
    diagnostics_.state = state;
    diagnostics_.reason = reason;
    diagnostics_.generation = reconciling_generation_;
    diagnostics_.thread_count = thread_count;
    diagnostics_.window_count = window_count;
    diagnostics_.surface_control_count = surface_control_count;
    diagnostics_.operational_failures = operational_failures_;
  }
  if (!changed) {
    return;
  }

  TRACE_EVENT_INSTANT1("flutter", "AndroidAdpfState", "state",
                       StateName(state));
  std::ostringstream message;
  message << "Android ADPF: " << StateName(state);
  if (reason != InactiveReason::kNone) {
    message << " (" << ReasonName(reason) << ")";
  }
  if (!detail.empty()) {
    message << ": " << detail;
  }
  message << " [generation " << reconciling_generation_ << ", threads "
          << thread_count << ", windows " << window_count
          << ", surface controls " << surface_control_count << "]";
  FML_LOG(INFO) << message.str();
}

bool AndroidGraphicsPerformanceController::IsIdleOnManagementSequence() {
  std::scoped_lock lock(mutex_);
  return !capture_in_flight_ &&
         (closed_ || reconciled_generation_ == generation_);
}

void AndroidGraphicsPerformanceController::WaitForIdleForTesting() {
  if (!management_runner_) {
    return;
  }
  // A marker that follows the same path work takes (management, UI, raster,
  // management) lands behind everything queued before it. Repeat until a
  // round trip observes no pending capture or reconciliation.
  for (int attempt = 0; attempt < 1000; attempt++) {
    fml::AutoResetWaitableEvent latch;
    bool idle = false;
    auto self = shared_from_this();
    auto management = management_runner_;
    auto ui = task_runners_.GetUITaskRunner();
    auto raster = task_runners_.GetRasterTaskRunner();
    management->PostTask([&, self, management, ui, raster]() {
      ui->PostTask([&, self, management, raster]() {
        raster->PostTask([&, self, management]() {
          management->PostTask([&, self]() {
            idle = self->IsIdleOnManagementSequence();
            latch.Signal();
          });
        });
      });
    });
    latch.Wait();
    if (idle) {
      return;
    }
  }
  FML_LOG(ERROR) << "Android ADPF: the controller did not become idle.";
}

}  // namespace flutter
