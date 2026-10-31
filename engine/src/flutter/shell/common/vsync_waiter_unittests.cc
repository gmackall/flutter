// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
#define FML_USED_ON_EMBEDDER

#include <atomic>
#include <initializer_list>
#include <memory>

#include "flutter/common/settings.h"
#include "flutter/common/task_runners.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/thread.h"
#include "flutter/lib/ui/ui_dart_state.h"
#include "flutter/runtime/dart_vm_lifecycle.h"
#include "flutter/shell/common/shell_test.h"
#include "flutter/shell/common/shell_test_platform_view.h"
#include "flutter/shell/common/switches.h"
#include "flutter/shell/common/thread_host.h"
#include "flutter/shell/common/vsync_waiter.h"
#include "gtest/gtest.h"
#include "third_party/tonic/converter/dart_converter.h"
#include "third_party/tonic/logging/dart_error.h"

namespace flutter {
namespace testing {

class TestVsyncWaiter : public VsyncWaiter {
 public:
  explicit TestVsyncWaiter(const TaskRunners& task_runners)
      : VsyncWaiter(task_runners) {}

  std::atomic_int await_vsync_call_count_{0};

  void TriggerVsync(fml::TimePoint frame_start_time,
                    fml::TimePoint frame_target_time,
                    bool can_target_current_thread = false) {
    FireCallback(frame_start_time, frame_target_time,
                 can_target_current_thread);
  }

 protected:
  void AwaitVSync() override { await_vsync_call_count_++; }
};

TEST(VsyncWaiterTest, FiresCallbackSynchronouslyOnUIThreadWhenAllowed) {
  bool callback_invoked_synchronously = false;
  fml::TimePoint start;
  fml::TimePoint target;
  fml::TimePoint recorded_start;
  fml::TimePoint recorded_target;
  fml::AutoResetWaitableEvent latch;

  fml::Thread ui_thread("vsync_waiter_sync_test_ui");
  auto task_runner = ui_thread.GetTaskRunner();
  const TaskRunners task_runners("vsync_waiter_sync_test", task_runner,
                                 task_runner, task_runner, task_runner);
  TestVsyncWaiter vsync_waiter(task_runners);

  task_runner->PostTask([&]() {
    bool callback_invoked = false;
    vsync_waiter.AsyncWaitForVsync(
        [&](std::unique_ptr<FrameTimingsRecorder> recorder) {
          callback_invoked = true;
          recorded_start = recorder->GetVsyncStartTime();
          recorded_target = recorder->GetVsyncTargetTime();
        });
    EXPECT_EQ(vsync_waiter.await_vsync_call_count_.load(), 1);

    start = fml::TimePoint::Now();
    target = start + fml::TimeDelta::FromNanoseconds(8333333);
    vsync_waiter.TriggerVsync(start, target,
                              /*can_target_current_thread=*/true);
    callback_invoked_synchronously = callback_invoked;
    latch.Signal();
  });
  latch.Wait();

  EXPECT_TRUE(callback_invoked_synchronously);
  EXPECT_EQ(recorded_start, start);
  EXPECT_EQ(recorded_target, target);
}

TEST(VsyncWaiterTest, PostsCallbackToUIThreadByDefault) {
  bool callback_invoked = false;
  bool callback_invoked_during_trigger = true;
  bool callback_invoked_on_ui_thread = false;
  fml::AutoResetWaitableEvent callback_latch;

  fml::Thread ui_thread("vsync_waiter_post_test_ui");
  auto task_runner = ui_thread.GetTaskRunner();
  const TaskRunners task_runners("vsync_waiter_post_test", task_runner,
                                 task_runner, task_runner, task_runner);
  TestVsyncWaiter vsync_waiter(task_runners);

  task_runner->PostTask([&]() {
    vsync_waiter.AsyncWaitForVsync(
        [&](std::unique_ptr<FrameTimingsRecorder> recorder) {
          callback_invoked = true;
          callback_invoked_on_ui_thread =
              task_runner->RunsTasksOnCurrentThread();
          callback_latch.Signal();
        });

    const auto start = fml::TimePoint::Now();
    const auto target = start + fml::TimeDelta::FromNanoseconds(8333333);
    vsync_waiter.TriggerVsync(start, target,
                              /*can_target_current_thread=*/false);
    callback_invoked_during_trigger = callback_invoked;
  });
  callback_latch.Wait();

  EXPECT_FALSE(callback_invoked_during_trigger);
  EXPECT_TRUE(callback_invoked);
  EXPECT_TRUE(callback_invoked_on_ui_thread);
}

TEST(VsyncWaiterTest, PostsCallbackWhenAllowedFromNonUIThread) {
  bool ran_on_ui_thread = false;
  fml::AutoResetWaitableEvent callback_latch;

  fml::Thread ui_thread("vsync_waiter_non_ui_test_ui");
  auto ui_task_runner = ui_thread.GetTaskRunner();
  const TaskRunners task_runners("vsync_waiter_non_ui_test", ui_task_runner,
                                 ui_task_runner, ui_task_runner,
                                 ui_task_runner);
  TestVsyncWaiter vsync_waiter(task_runners);

  vsync_waiter.AsyncWaitForVsync(
      [&](std::unique_ptr<FrameTimingsRecorder> recorder) {
        ran_on_ui_thread = ui_task_runner->RunsTasksOnCurrentThread();
        callback_latch.Signal();
      });

  const auto start = fml::TimePoint::Now();
  const auto target = start + fml::TimeDelta::FromNanoseconds(8333333);
  // Trigger from the test thread (not ui_thread) with
  // can_target_current_thread = true; it must still post to ui_task_runner.
  EXPECT_FALSE(ui_task_runner->RunsTasksOnCurrentThread());
  vsync_waiter.TriggerVsync(start, target, /*can_target_current_thread=*/true);
  callback_latch.Wait();

  EXPECT_TRUE(ran_on_ui_thread);
}

TEST(VsyncWaiterTest, AllowsSynchronousReArmingInsideCallback) {
  int callback_count = 0;
  fml::AutoResetWaitableEvent latch;

  fml::Thread ui_thread("vsync_waiter_rearm_test_ui");
  auto task_runner = ui_thread.GetTaskRunner();
  const TaskRunners task_runners("vsync_waiter_rearm_test", task_runner,
                                 task_runner, task_runner, task_runner);
  TestVsyncWaiter vsync_waiter(task_runners);

  task_runner->PostTask([&]() {
    vsync_waiter.AsyncWaitForVsync(
        [&](std::unique_ptr<FrameTimingsRecorder> recorder) {
          callback_count++;
          vsync_waiter.AsyncWaitForVsync(
              [&](std::unique_ptr<FrameTimingsRecorder> second_recorder) {
                callback_count++;
              });
        });
    EXPECT_EQ(vsync_waiter.await_vsync_call_count_.load(), 1);

    const auto start = fml::TimePoint::Now();
    const auto target = start + fml::TimeDelta::FromNanoseconds(8333333);
    vsync_waiter.TriggerVsync(start, target,
                              /*can_target_current_thread=*/true);
    EXPECT_EQ(callback_count, 1);
    EXPECT_EQ(vsync_waiter.await_vsync_call_count_.load(), 2);

    vsync_waiter.TriggerVsync(start, target,
                              /*can_target_current_thread=*/true);
    EXPECT_EQ(callback_count, 2);
    latch.Signal();
  });
  latch.Wait();

  EXPECT_EQ(callback_count, 2);
}

TEST_F(ShellTest, SynchronousVsyncCallbackFlushesMicrotasksBeforeReturning) {
  Settings settings = CreateSettingsForFixture();
  ThreadHost thread_host("io.flutter.test." + GetCurrentTestName() + ".",
                         ThreadHost::Type::kPlatform);
  auto task_runner = thread_host.platform_thread->GetTaskRunner();
  TaskRunners task_runners("test", task_runner, task_runner, task_runner,
                           task_runner);

  TestVsyncWaiter* raw_vsync_waiter = nullptr;
  auto shell = CreateShell({
      .settings = settings,
      .task_runners = task_runners,
      .platform_view_create_callback =
          [&raw_vsync_waiter](Shell& shell) {
            const TaskRunners& runners = shell.GetTaskRunners();
            const auto vsync_clock = std::make_shared<ShellTestVsyncClock>();
            CreateVsyncWaiter create_vsync_waiter = [&raw_vsync_waiter,
                                                     runners]() {
              auto waiter = std::make_unique<TestVsyncWaiter>(runners);
              raw_vsync_waiter = waiter.get();
              return static_cast<std::unique_ptr<VsyncWaiter>>(
                  std::move(waiter));
            };
            return ShellTestPlatformView::Create(
                ShellTestPlatformView::DefaultBackendType(), shell, runners,
                vsync_clock, create_vsync_waiter, nullptr,
                shell.GetIsGpuDisabledSyncSwitch());
          },
  });
  ASSERT_NE(raw_vsync_waiter, nullptr);

  PlatformViewNotifyCreated(shell.get());

  int notify_count = 0;
  AddFfiNativeCallback("NotifyNative", CREATE_FFI_LAMBDA([&]() {
                         notify_count++;
                         if (notify_count == 1) {
                           // Called inside onDrawFrame; schedule a Dart
                           // microtask that calls mainNotifyNative() ->
                           // notifyNative() and verify it drains before the
                           // synchronous VSync callback returns.
                           Dart_Handle closure =
                               Dart_GetField(Dart_RootLibrary(),
                                             tonic::ToDart("mainNotifyNative"));
                           ASSERT_FALSE(tonic::CheckAndHandleError(closure));
                           UIDartState::Current()->ScheduleMicrotask(closure);
                         }
                       }));

  auto configuration = RunConfiguration::InferFromSettings(settings);
  configuration.SetEntrypoint("renderSingleViewAndCallAfterOnDrawFrame");
  RunEngine(shell.get(), std::move(configuration));

  int notify_count_after_sync_vsync = 0;
  fml::AutoResetWaitableEvent latch;
  task_runner->PostTask([&]() {
    const auto start = fml::TimePoint::Now();
    const auto target = start + fml::TimeDelta::FromNanoseconds(8333333);
    raw_vsync_waiter->TriggerVsync(start, target,
                                   /*can_target_current_thread=*/true);
    notify_count_after_sync_vsync = notify_count;
    latch.Signal();
  });
  latch.Wait();

  EXPECT_EQ(notify_count_after_sync_vsync, 2);

  PlatformViewNotifyDestroyed(shell.get());
  DestroyShell(std::move(shell), task_runners);
  ASSERT_FALSE(DartVMRef::IsInstanceRunning());
}

}  // namespace testing
}  // namespace flutter
