// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/adpf/android_graphics_performance_controller.h"

#include <unistd.h>

#include <cerrno>
#include <memory>

#include "flutter/common/task_runners.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/thread.h"
#include "flutter/shell/platform/android/adpf/android_performance_hint_fake.h"
#include "flutter/shell/platform/android/adpf/android_performance_hint_registry.h"
#include "flutter/shell/platform/android/surface/android_output_producer.h"
#include "gtest/gtest.h"

namespace flutter {
namespace testing {

namespace {

using Controller = AndroidGraphicsPerformanceController;
using State = Controller::State;
using Reason = Controller::InactiveReason;
using Producer = AndroidOutputProducer;

constexpr int kSupportedApiLevel = 36;

std::shared_ptr<const Producer> MakeWindow(uintptr_t handle) {
  return Producer::MakeForTesting(Producer::Kind::kNativeWindow,
                                  reinterpret_cast<void*>(handle));
}

std::shared_ptr<const Producer> MakeSurfaceControl(uintptr_t handle) {
  return Producer::MakeForTesting(Producer::Kind::kSurfaceControl,
                                  reinterpret_cast<void*>(handle));
}

pid_t ThreadIdOf(const fml::RefPtr<fml::TaskRunner>& runner) {
  fml::AutoResetWaitableEvent latch;
  pid_t tid = 0;
  runner->PostTask([&]() {
    tid = gettid();
    latch.Signal();
  });
  latch.Wait();
  return tid;
}

//------------------------------------------------------------------------------
/// Owns the execution group's threads, a fake platform and a test registry.
/// The test thread is never one of the execution group's threads, so waiting
/// on the controller cannot deadlock.
///
class ControllerFixture {
 public:
  explicit ControllerFixture(int api_level = kSupportedApiLevel,
                             AndroidDeviceIdentity identity = {})
      : platform_thread_("test.platform"),
        ui_thread_("test.ui"),
        raster_thread_("test.raster"),
        io_thread_("test.io"),
        management_thread_("test.adpf"),
        api_(std::make_shared<FakePerformanceHintApi>()),
        registry_([api = api_]() { return api; },
                  std::move(identity),
                  api_level,
                  management_thread_.GetTaskRunner()) {}

  ~ControllerFixture() {
    // Controllers close their sessions on the management sequence; let that
    // happen before the thread is joined.
    registry_.FlushManagementRunnerForTesting();
  }

  TaskRunners MakeTaskRunners(bool merged_ui = false) const {
    return TaskRunners("test",                             //
                       platform_thread_.GetTaskRunner(),   //
                       raster_thread_.GetTaskRunner(),     //
                       merged_ui ? platform_thread_.GetTaskRunner()
                                 : ui_thread_.GetTaskRunner(),  //
                       io_thread_.GetTaskRunner());
  }

  std::shared_ptr<Controller> MakeController(
      bool enabled = true,
      bool ignore_denylist = false,
      bool merged_ui = false) {
    AutomaticAdpfPolicy policy;
    policy.enabled = enabled;
    policy.ignore_denylist = ignore_denylist;
    return Controller::Create(policy, MakeTaskRunners(merged_ui), &registry_);
  }

  FakePerformanceHintApi& api() { return *api_; }
  AndroidPerformanceHintRegistry& registry() { return registry_; }

  pid_t ui_tid() { return ThreadIdOf(ui_thread_.GetTaskRunner()); }
  pid_t raster_tid() { return ThreadIdOf(raster_thread_.GetTaskRunner()); }
  pid_t platform_tid() { return ThreadIdOf(platform_thread_.GetTaskRunner()); }

 private:
  fml::Thread platform_thread_;
  fml::Thread ui_thread_;
  fml::Thread raster_thread_;
  fml::Thread io_thread_;
  fml::Thread management_thread_;
  std::shared_ptr<FakePerformanceHintApi> api_;
  AndroidPerformanceHintRegistry registry_;
};

// Attaches a visible main window and waits for the controller to settle.
std::unique_ptr<Controller::Registration> AttachVisibleEngine(
    Controller& controller,
    const std::shared_ptr<const Producer>& main_output) {
  auto registration = controller.RegisterEngine();
  registration->SetMainOutput(main_output);
  registration->SetVisible(true);
  controller.WaitForIdleForTesting();
  return registration;
}

}  // namespace

//------------------------------------------------------------------------------
// Eligibility
//------------------------------------------------------------------------------

TEST(AndroidGraphicsPerformanceController, DisabledByPolicyCreatesNothing) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController(/*enabled=*/false);
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kIneligible);
  EXPECT_EQ(diagnostics.reason, Reason::kDisabledByPolicy);
  EXPECT_EQ(fixture.api().created_session_count(), 0u);
  // Nothing on the platform was even queried.
  EXPECT_EQ(fixture.api().feature_queries(), 0);
}

TEST(AndroidGraphicsPerformanceController, OldApiLevelIsIneligible) {
  ControllerFixture fixture(/*api_level=*/35);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kIneligible);
  EXPECT_EQ(diagnostics.reason, Reason::kApiLevelTooLow);
  EXPECT_EQ(fixture.api().created_session_count(), 0u);
  EXPECT_EQ(fixture.api().feature_queries(), 0);
}

TEST(AndroidGraphicsPerformanceController, MissingSymbolsAreIneligible) {
  ControllerFixture fixture;
  fixture.api().set_available(false);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kIneligible);
  EXPECT_EQ(diagnostics.reason, Reason::kApiUnavailable);
  EXPECT_EQ(fixture.api().created_session_count(), 0u);
}

TEST(AndroidGraphicsPerformanceController, NullManagerIsIneligible) {
  ControllerFixture fixture;
  fixture.api().set_manager_available(false);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  EXPECT_EQ(controller->GetDiagnostics().reason, Reason::kManagerUnavailable);
  EXPECT_EQ(fixture.api().created_session_count(), 0u);
}

TEST(AndroidGraphicsPerformanceController, EachMissingCapabilityIsIneligible) {
  using Feature = AndroidPerformanceHintApi::Feature;
  for (auto feature : {Feature::kSessions, Feature::kSurfaceBinding,
                       Feature::kGraphicsPipeline, Feature::kAutoCpu,
                       Feature::kAutoGpu}) {
    ControllerFixture fixture;
    fixture.api().set_feature_supported(feature, false);
    auto controller = fixture.MakeController();
    auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

    auto diagnostics = controller->GetDiagnostics();
    EXPECT_EQ(diagnostics.state, State::kIneligible)
        << static_cast<int>(feature);
    EXPECT_EQ(diagnostics.reason, Reason::kMissingCapability)
        << static_cast<int>(feature);
    EXPECT_EQ(fixture.api().created_session_count(), 0u);
  }
}

TEST(AndroidGraphicsPerformanceController,
     PowerEfficiencyIsNotRequiredAndZeroThreadLimitIs) {
  {
    ControllerFixture fixture;
    fixture.api().set_feature_supported(
        AndroidPerformanceHintApi::Feature::kPowerEfficiency, false);
    auto controller = fixture.MakeController();
    auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
    EXPECT_EQ(controller->GetDiagnostics().state, State::kActive);
  }
  {
    ControllerFixture fixture;
    fixture.api().set_max_graphics_pipeline_threads(0);
    auto controller = fixture.MakeController();
    auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
    EXPECT_EQ(controller->GetDiagnostics().reason, Reason::kMissingCapability);
  }
}

TEST(AndroidGraphicsPerformanceController, CapabilitiesAreEvaluatedOnce) {
  ControllerFixture fixture;
  fixture.api().set_feature_supported(
      AndroidPerformanceHintApi::Feature::kAutoGpu, false);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
  const int queries = fixture.api().feature_queries();
  EXPECT_GT(queries, 0);

  // Overlay churn on an unsupported device never re-queries or retries.
  for (int i = 0; i < 5; i++) {
    registration->SetOverlayOutputs({MakeWindow(0x100 + i)});
    controller->WaitForIdleForTesting();
  }
  auto second = fixture.MakeController();
  auto second_registration = AttachVisibleEngine(*second, MakeWindow(0x20));

  EXPECT_EQ(fixture.api().feature_queries(), queries);
  EXPECT_EQ(fixture.api().created_session_count(), 0u);
}

//------------------------------------------------------------------------------
// Session creation
//------------------------------------------------------------------------------

TEST(AndroidGraphicsPerformanceController, CreatesFullyAutomaticSession) {
  ControllerFixture fixture;
  fixture.registry().SetDisplayRefreshRate(120);
  auto controller = fixture.MakeController();
  auto main_window = MakeWindow(0x10);
  auto registration = AttachVisibleEngine(*controller, main_window);

  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kActive);
  EXPECT_EQ(diagnostics.reason, Reason::kNone);
  EXPECT_EQ(diagnostics.sessions_created, 1u);
  EXPECT_EQ(diagnostics.thread_count, 2u);
  EXPECT_EQ(diagnostics.window_count, 1u);
  EXPECT_EQ(diagnostics.surface_control_count, 0u);

  auto sessions = fixture.api().sessions();
  ASSERT_EQ(sessions.size(), 1u);
  const auto& config = sessions[0].config;
  EXPECT_TRUE(config.graphics_pipeline);
  EXPECT_TRUE(config.auto_cpu_timing);
  EXPECT_TRUE(config.auto_gpu_timing);
  ASSERT_EQ(config.windows.size(), 1u);
  EXPECT_EQ(config.windows[0], main_window->window());
  EXPECT_TRUE(config.surface_controls.empty());

  // The complete critical path: the threads that execute the UI and raster
  // runners, and nothing else.
  std::vector<pid_t> expected = {fixture.ui_tid(), fixture.raster_tid()};
  std::sort(expected.begin(), expected.end());
  EXPECT_EQ(config.tids, expected);

  // The intended cadence was published on the output.
  auto votes = fixture.api().frame_rate_votes();
  ASSERT_EQ(votes.size(), 1u);
  EXPECT_EQ(votes[0].handle, main_window->window());
  EXPECT_FALSE(votes[0].is_surface_control);
  EXPECT_EQ(votes[0].frame_rate, 120);
  EXPECT_EQ(diagnostics.published_frame_rate, 120);
}

TEST(AndroidGraphicsPerformanceController, SurfaceControlProducersAreControls) {
  ControllerFixture fixture;
  fixture.registry().SetDisplayRefreshRate(90);
  auto controller = fixture.MakeController();
  auto main_control = MakeSurfaceControl(0x30);
  auto registration = AttachVisibleEngine(*controller, main_control);

  auto sessions = fixture.api().sessions();
  ASSERT_EQ(sessions.size(), 1u);
  EXPECT_TRUE(sessions[0].config.windows.empty());
  ASSERT_EQ(sessions[0].config.surface_controls.size(), 1u);
  EXPECT_EQ(sessions[0].config.surface_controls[0],
            main_control->surface_control());

  auto votes = fixture.api().frame_rate_votes();
  ASSERT_EQ(votes.size(), 1u);
  EXPECT_TRUE(votes[0].is_surface_control);
  EXPECT_EQ(votes[0].frame_rate, 90);
}

TEST(AndroidGraphicsPerformanceController, MergedThreadsAreCountedOnce) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController(/*enabled=*/true,
                                           /*ignore_denylist=*/false,
                                           /*merged_ui=*/true);
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  auto sessions = fixture.api().sessions();
  ASSERT_EQ(sessions.size(), 1u);
  std::vector<pid_t> expected = {fixture.platform_tid(), fixture.raster_tid()};
  std::sort(expected.begin(), expected.end());
  EXPECT_EQ(sessions[0].config.tids, expected);
  EXPECT_EQ(controller->GetDiagnostics().thread_count, 2u);
}

TEST(AndroidGraphicsPerformanceController, WaitsForOutputBeforeCreating) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController();
  auto registration = controller->RegisterEngine();
  registration->SetVisible(true);
  controller->WaitForIdleForTesting();

  EXPECT_EQ(controller->GetDiagnostics().state, State::kWaitingForOutput);
  EXPECT_EQ(controller->GetDiagnostics().reason, Reason::kNoOutput);
  EXPECT_EQ(fixture.api().created_session_count(), 0u);

  registration->SetMainOutput(MakeWindow(0x10));
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().state, State::kActive);
}

TEST(AndroidGraphicsPerformanceController, HiddenEngineContributesNothing) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController();
  auto registration = controller->RegisterEngine();
  registration->SetMainOutput(MakeWindow(0x10));
  registration->SetOverlayOutputs({MakeWindow(0x11)});
  controller->WaitForIdleForTesting();

  // Surfaces alone, without visibility, are not a workload.
  EXPECT_EQ(controller->GetDiagnostics().state, State::kWaitingForOutput);
  EXPECT_EQ(fixture.api().created_session_count(), 0u);
}

//------------------------------------------------------------------------------
// Lifecycle
//------------------------------------------------------------------------------

TEST(AndroidGraphicsPerformanceController, DetachClosesAndReattachRecreates) {
  ControllerFixture fixture;
  fixture.registry().SetDisplayRefreshRate(60);
  auto controller = fixture.MakeController();
  auto first_window = MakeWindow(0x10);
  auto registration = AttachVisibleEngine(*controller, first_window);
  ASSERT_EQ(fixture.api().open_session_count(), 1u);

  // Background: the surface is destroyed.
  registration->SetMainOutput(nullptr);
  registration->SetVisible(false);
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().state, State::kWaitingForOutput);
  EXPECT_EQ(fixture.api().open_session_count(), 0u);
  EXPECT_EQ(controller->GetDiagnostics().sessions_closed, 1u);

  // The vote was withdrawn on the output before it was released.
  auto votes = fixture.api().frame_rate_votes();
  ASSERT_EQ(votes.size(), 2u);
  EXPECT_EQ(votes[1].handle, first_window->window());
  EXPECT_EQ(votes[1].frame_rate, 0);

  // Foreground: a new surface, hence a new producer.
  auto second_window = MakeWindow(0x10);
  EXPECT_NE(first_window->id(), second_window->id());
  registration->SetMainOutput(second_window);
  registration->SetVisible(true);
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().state, State::kActive);
  EXPECT_EQ(fixture.api().open_session_count(), 1u);
  EXPECT_EQ(fixture.api().created_session_count(), 2u);
}

TEST(AndroidGraphicsPerformanceController,
     OverlayChangesUpdateOutputsWithoutRecreating) {
  ControllerFixture fixture;
  fixture.registry().SetDisplayRefreshRate(60);
  auto controller = fixture.MakeController();
  auto main_window = MakeWindow(0x10);
  auto registration = AttachVisibleEngine(*controller, main_window);

  auto overlay = MakeSurfaceControl(0x20);
  registration->SetOverlayOutputs({overlay});
  controller->WaitForIdleForTesting();

  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kActive);
  EXPECT_EQ(diagnostics.sessions_created, 1u);
  EXPECT_EQ(diagnostics.output_updates, 1u);
  EXPECT_EQ(diagnostics.window_count, 1u);
  EXPECT_EQ(diagnostics.surface_control_count, 1u);

  auto sessions = fixture.api().sessions();
  ASSERT_EQ(sessions.size(), 1u);
  ASSERT_EQ(sessions[0].surface_updates.size(), 1u);
  EXPECT_EQ(sessions[0].surface_updates[0].first,
            std::vector<ANativeWindow*>{main_window->window()});
  EXPECT_EQ(sessions[0].surface_updates[0].second,
            std::vector<ASurfaceControl*>{overlay->surface_control()});

  // The new output gets the cadence too.
  auto votes = fixture.api().frame_rate_votes();
  ASSERT_EQ(votes.size(), 2u);
  EXPECT_EQ(votes[1].handle, overlay->surface_control());
  EXPECT_EQ(votes[1].frame_rate, 60);

  // Removing the overlay updates again and withdraws its vote.
  registration->SetOverlayOutputs({});
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().output_updates, 2u);
  votes = fixture.api().frame_rate_votes();
  ASSERT_EQ(votes.size(), 3u);
  EXPECT_EQ(votes[2].handle, overlay->surface_control());
  EXPECT_EQ(votes[2].frame_rate, 0);
  EXPECT_EQ(fixture.api().created_session_count(), 1u);
}

TEST(AndroidGraphicsPerformanceController, SameOutputsAreNotReapplied) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController();
  auto main_window = MakeWindow(0x10);
  auto overlay = MakeWindow(0x11);
  auto registration = AttachVisibleEngine(*controller, main_window);
  registration->SetOverlayOutputs({overlay});
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().output_updates, 1u);

  // Reporting the same producers again is a no-op.
  registration->SetOverlayOutputs({overlay});
  registration->SetVisible(true);
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().output_updates, 1u);
  EXPECT_EQ(fixture.api().created_session_count(), 1u);
}

TEST(AndroidGraphicsPerformanceController,
     ProducerReplacementAtSameAddressIsANewOutput) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  // Same address, new producer instance: the registry must not assume reuse.
  registration->SetMainOutput(MakeWindow(0x10));
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().output_updates, 1u);
}

TEST(AndroidGraphicsPerformanceController,
     UnsupportedOutputReplacementFallsBackToRecreation) {
  ControllerFixture fixture;
  fixture.api().set_surface_update_error(ENOTSUP);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  registration->SetOverlayOutputs({MakeWindow(0x11)});
  controller->WaitForIdleForTesting();

  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kActive);
  EXPECT_EQ(diagnostics.sessions_created, 2u);
  EXPECT_EQ(diagnostics.output_updates, 0u);
  // An unsupported feature is not an operational failure.
  EXPECT_EQ(diagnostics.operational_failures, 0u);
  EXPECT_EQ(fixture.api().open_session_count(), 1u);
}

TEST(AndroidGraphicsPerformanceController, ThreadTopologyChangeRecreates) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
  ASSERT_EQ(fixture.api().created_session_count(), 1u);

  // Threads did not actually move: the fresh capture matches and the session
  // is kept.
  controller->NotifyThreadTopologyChanged();
  controller->WaitForIdleForTesting();
  EXPECT_EQ(fixture.api().created_session_count(), 1u);
  EXPECT_EQ(controller->GetDiagnostics().state, State::kActive);
}

TEST(AndroidGraphicsPerformanceController, RunnerMigrationIsRecaptured) {
  // Two task runner sets over the same threads, differing only in which
  // thread runs the UI runner, model a merge: the controller only ever asks
  // the runners where they execute.
  ControllerFixture fixture;
  auto controller = fixture.MakeController(/*enabled=*/true,
                                           /*ignore_denylist=*/false,
                                           /*merged_ui=*/false);
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
  auto sessions = fixture.api().sessions();
  ASSERT_EQ(sessions.size(), 1u);
  EXPECT_EQ(sessions[0].config.tids.size(), 2u);
  EXPECT_NE(std::find(sessions[0].config.tids.begin(),
                      sessions[0].config.tids.end(), fixture.ui_tid()),
            sessions[0].config.tids.end());
}

TEST(AndroidGraphicsPerformanceController, DestructionClosesExactlyOnce) {
  ControllerFixture fixture;
  {
    auto controller = fixture.MakeController();
    auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
    ASSERT_EQ(fixture.api().open_session_count(), 1u);
    // Drop the registration first, as the platform view goes before the
    // shell holder.
    registration.reset();
    controller->WaitForIdleForTesting();
  }
  fixture.registry().FlushManagementRunnerForTesting();
  EXPECT_EQ(fixture.api().open_session_count(), 0u);
  EXPECT_EQ(fixture.api().created_session_count(), 1u);
  EXPECT_EQ(fixture.registry().GetReservedGraphicsPipelineThreadCount(), 0u);
}

TEST(AndroidGraphicsPerformanceController, ShutdownCannotResurrectASession) {
  ControllerFixture fixture;
  std::unique_ptr<Controller::Registration> registration;
  {
    auto controller = fixture.MakeController();
    registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
  }
  fixture.registry().FlushManagementRunnerForTesting();
  EXPECT_EQ(fixture.api().open_session_count(), 0u);

  // A registration that outlives its controller is inert.
  registration->SetMainOutput(MakeWindow(0x11));
  registration->SetVisible(true);
  fixture.registry().FlushManagementRunnerForTesting();
  EXPECT_EQ(fixture.api().created_session_count(), 1u);
  registration.reset();
}

//------------------------------------------------------------------------------
// Engine groups
//------------------------------------------------------------------------------

TEST(AndroidGraphicsPerformanceController, EngineGroupSharesOneSession) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController();
  auto parent_window = MakeWindow(0x10);
  auto child_window = MakeWindow(0x20);
  auto parent = AttachVisibleEngine(*controller, parent_window);
  auto child = AttachVisibleEngine(*controller, child_window);

  EXPECT_EQ(fixture.api().created_session_count(), 1u);
  auto sessions = fixture.api().sessions();
  ASSERT_EQ(sessions.size(), 1u);
  EXPECT_EQ(sessions[0].config.windows.size(), 2u);

  // Destroying the parent keeps the child's session.
  parent.reset();
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().state, State::kActive);
  EXPECT_EQ(fixture.api().open_session_count(), 1u);
  sessions = fixture.api().sessions();
  EXPECT_EQ(sessions[0].config.windows,
            std::vector<ANativeWindow*>{child_window->window()});

  // Destroying the last engine closes it exactly once.
  child.reset();
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().state, State::kWaitingForOutput);
  EXPECT_EQ(fixture.api().open_session_count(), 0u);
  EXPECT_EQ(controller->GetDiagnostics().sessions_closed, 1u);
}

TEST(AndroidGraphicsPerformanceController,
     IndependentGroupsShareTheApplicationThreadLimit) {
  ControllerFixture fixture;
  // Both groups run on the same test threads, so their unique thread ids
  // overlap completely and two sessions fit within a limit of two.
  fixture.api().set_max_graphics_pipeline_threads(2);
  auto first = fixture.MakeController();
  auto second = fixture.MakeController();
  auto first_registration = AttachVisibleEngine(*first, MakeWindow(0x10));
  auto second_registration = AttachVisibleEngine(*second, MakeWindow(0x20));
  EXPECT_EQ(first->GetDiagnostics().state, State::kActive);
  EXPECT_EQ(second->GetDiagnostics().state, State::kActive);
  EXPECT_EQ(fixture.registry().GetReservedGraphicsPipelineThreadCount(), 2u);
}

TEST(AndroidGraphicsPerformanceController, ThreadLimitIsNeverFitByDropping) {
  ControllerFixture fixture;
  fixture.api().set_max_graphics_pipeline_threads(1);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kIneligible);
  EXPECT_EQ(diagnostics.reason, Reason::kThreadLimitExceeded);
  EXPECT_EQ(fixture.api().created_session_count(), 0u);
  EXPECT_EQ(fixture.registry().GetReservedGraphicsPipelineThreadCount(), 0u);
}

//------------------------------------------------------------------------------
// Failures
//------------------------------------------------------------------------------

TEST(AndroidGraphicsPerformanceController, BusyResultClosesTheReturnedSession) {
  ControllerFixture fixture;
  fixture.api().QueueCreationError(EBUSY);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kIneligible);
  EXPECT_EQ(diagnostics.reason, Reason::kThreadLimitExceeded);
  EXPECT_EQ(fixture.api().created_session_count(), 1u);
  EXPECT_EQ(fixture.api().open_session_count(), 0u);

  // Output churn does not retry a rejected topology.
  registration->SetOverlayOutputs({MakeWindow(0x11)});
  controller->WaitForIdleForTesting();
  EXPECT_EQ(fixture.api().created_session_count(), 1u);

  // A topology change does.
  controller->NotifyThreadTopologyChanged();
  controller->WaitForIdleForTesting();
  // The threads are the same, so the platform's answer is assumed unchanged.
  EXPECT_EQ(fixture.api().created_session_count(), 1u);
}

TEST(AndroidGraphicsPerformanceController,
     UnsupportedAutomaticTimingIsCachedForTheProcess) {
  ControllerFixture fixture;
  fixture.api().QueueCreationError(ENOTSUP);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
  EXPECT_EQ(controller->GetDiagnostics().reason, Reason::kMissingCapability);

  auto second = fixture.MakeController();
  auto second_registration = AttachVisibleEngine(*second, MakeWindow(0x20));
  EXPECT_EQ(second->GetDiagnostics().reason, Reason::kMissingCapability);
  EXPECT_EQ(fixture.api().created_session_count(), 1u);
}

TEST(AndroidGraphicsPerformanceController, RetryBudgetIsBounded) {
  ControllerFixture fixture;
  for (int i = 0; i < 10; i++) {
    fixture.api().QueueCreationError(EPIPE);
  }
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
  EXPECT_EQ(controller->GetDiagnostics().state, State::kFailed);
  EXPECT_EQ(controller->GetDiagnostics().reason,
            Reason::kSessionCreationFailed);
  EXPECT_EQ(controller->GetDiagnostics().operational_failures, 1u);

  // Each lifecycle change is one attempt, never one per frame.
  for (int i = 0; i < 10; i++) {
    registration->SetOverlayOutputs({MakeWindow(0x100 + i)});
    controller->WaitForIdleForTesting();
  }
  EXPECT_EQ(fixture.api().created_session_count(), 0u);
  EXPECT_EQ(controller->GetDiagnostics().reason, Reason::kRetryBudgetExhausted);
  EXPECT_EQ(controller->GetDiagnostics().operational_failures, 3u);
}

TEST(AndroidGraphicsPerformanceController, TransientFailureRecoversLater) {
  ControllerFixture fixture;
  fixture.api().QueueCreationError(EPIPE);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
  EXPECT_EQ(controller->GetDiagnostics().state, State::kFailed);

  registration->SetOverlayOutputs({MakeWindow(0x11)});
  controller->WaitForIdleForTesting();
  EXPECT_EQ(controller->GetDiagnostics().state, State::kActive);
  EXPECT_EQ(fixture.api().open_session_count(), 1u);
}

TEST(AndroidGraphicsPerformanceController, FailedOutputUpdateRecreates) {
  ControllerFixture fixture;
  fixture.api().set_surface_update_error(EPIPE);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));

  registration->SetOverlayOutputs({MakeWindow(0x11)});
  controller->WaitForIdleForTesting();
  auto diagnostics = controller->GetDiagnostics();
  EXPECT_EQ(diagnostics.state, State::kActive);
  EXPECT_EQ(diagnostics.sessions_created, 2u);
  EXPECT_EQ(diagnostics.operational_failures, 1u);
  EXPECT_EQ(fixture.api().open_session_count(), 1u);
}

//------------------------------------------------------------------------------
// Cadence and denylist
//------------------------------------------------------------------------------

TEST(AndroidGraphicsPerformanceController, CadenceFollowsIntendedRateOnly) {
  ControllerFixture fixture;
  auto controller = fixture.MakeController();
  auto main_window = MakeWindow(0x10);
  auto registration = AttachVisibleEngine(*controller, main_window);

  // Without a reported cadence nothing is published rather than a guess.
  EXPECT_TRUE(fixture.api().frame_rate_votes().empty());
  EXPECT_EQ(controller->GetDiagnostics().published_frame_rate, 0);

  fixture.registry().SetDisplayRefreshRate(120);
  controller->WaitForIdleForTesting();
  fixture.registry().FlushManagementRunnerForTesting();
  auto votes = fixture.api().frame_rate_votes();
  ASSERT_EQ(votes.size(), 1u);
  EXPECT_EQ(votes[0].frame_rate, 120);

  // A display mode change republishes; the same value does not.
  fixture.registry().SetDisplayRefreshRate(120);
  fixture.registry().SetDisplayRefreshRate(90);
  fixture.registry().FlushManagementRunnerForTesting();
  votes = fixture.api().frame_rate_votes();
  ASSERT_EQ(votes.size(), 2u);
  EXPECT_EQ(votes[1].frame_rate, 90);
  EXPECT_EQ(controller->GetDiagnostics().published_frame_rate, 90);
}

TEST(AndroidGraphicsPerformanceController, DenylistWinsUnlessOverridden) {
  AndroidDeviceIdentity identity;
  identity.manufacturer = "acme";
  identity.device = "widget";
  identity.api_level = kSupportedApiLevel;
  AndroidAdpfDenylistEntry entry{
      .id = "acme-widget-36",
      .tracking_issue = "https://github.com/flutter/flutter/issues/0",
      .manufacturer = "acme",
      .device = "widget",
  };
  EXPECT_TRUE(entry.Matches(identity));

  // The compiled list is empty by design; the matcher is covered in the
  // policy tests. This test exercises the controller's precedence rules with
  // the compiled list, which must not match a synthetic device.
  ControllerFixture fixture(kSupportedApiLevel, identity);
  auto controller = fixture.MakeController();
  auto registration = AttachVisibleEngine(*controller, MakeWindow(0x10));
  EXPECT_TRUE(controller->GetDiagnostics().denylist_rule.empty());
  EXPECT_EQ(controller->GetDiagnostics().state, State::kActive);

  // A disable request always wins over an enable override.
  auto disabled = fixture.MakeController(/*enabled=*/false,
                                         /*ignore_denylist=*/true);
  auto disabled_registration =
      AttachVisibleEngine(*disabled, MakeWindow(0x20));
  EXPECT_EQ(disabled->GetDiagnostics().reason, Reason::kDisabledByPolicy);
}

}  // namespace testing
}  // namespace flutter
