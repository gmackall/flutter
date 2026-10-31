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

  std::atomic_int notify_count{0};
  fml::AutoResetWaitableEvent platform_message_response_latch;
  int64_t second_frame_begin_micros = 0;
  AddFfiNativeCallback(
      "NativeOnBeginFrame", CREATE_FFI_LAMBDA([&](int64_t microseconds) {
        second_frame_begin_micros = microseconds;
      }));
  AddFfiNativeCallback(
      "NotifyNative", CREATE_FFI_LAMBDA([&]() {
        const int count = ++notify_count;
        if (count == 1) {
          // Called inside onDrawFrame; schedule Dart microtasks that:
          // 1. Call onBeginFrameMain() -> notifyNative() + scheduleFrame().
          // 2. Call testSkiaResourceCacheSendsResponse() ->
          //    sendPlatformMessage('flutter/skia', ...) whose response callback
          //    calls notifyNative().
          Dart_Handle schedule_frame_closure = Dart_GetField(
              Dart_RootLibrary(), tonic::ToDart("onBeginFrameMain"));
          ASSERT_FALSE(tonic::CheckAndHandleError(schedule_frame_closure));
          UIDartState::Current()->ScheduleMicrotask(schedule_frame_closure);

          Dart_Handle platform_message_closure = Dart_GetField(
              Dart_RootLibrary(),
              tonic::ToDart("testSkiaResourceCacheSendsResponse"));
          ASSERT_FALSE(tonic::CheckAndHandleError(platform_message_closure));
          UIDartState::Current()->ScheduleMicrotask(platform_message_closure);
        } else if (count == 3) {
          platform_message_response_latch.Signal();
        }
      }));

  auto configuration = RunConfiguration::InferFromSettings(settings);
  configuration.SetEntrypoint("renderSingleViewAndCallAfterOnDrawFrame");
  RunEngine(shell.get(), std::move(configuration));

  int notify_count_after_sync_vsync = 0;
  int await_vsync_after_sync_vsync = 0;
  fml::AutoResetWaitableEvent latch;
  task_runner->PostTask([&]() {
    EXPECT_EQ(raw_vsync_waiter->await_vsync_call_count_.load(), 1);
    const auto start = fml::TimePoint::Now();
    const auto target = start + fml::TimeDelta::FromNanoseconds(8333333);
    raw_vsync_waiter->TriggerVsync(start, target,
                                   /*can_target_current_thread=*/true);
    notify_count_after_sync_vsync = notify_count.load();
    await_vsync_after_sync_vsync =
        raw_vsync_waiter->await_vsync_call_count_.load();
    latch.Signal();
  });
  latch.Wait();

  // Both microtasks drained synchronously before TriggerVsync returned:
  // onBeginFrameMain incremented notify_count to 2 and re-armed AwaitVSync.
  EXPECT_EQ(notify_count_after_sync_vsync, 2);
  EXPECT_EQ(await_vsync_after_sync_vsync, 2);

  // The flutter/skia platform message sent from the second microtask completes
  // its response callback on the UI thread.
  platform_message_response_latch.Wait();
  EXPECT_EQ(notify_count.load(), 3);

  // Fire the second VSync that was requested by the microtask during the first
  // frame and verify the onBeginFrame callback registered by that microtask
  // executes.
  fml::AutoResetWaitableEvent second_vsync_latch;
  task_runner->PostTask([&]() {
    const auto start = fml::TimePoint::Now();
    const auto target = start + fml::TimeDelta::FromNanoseconds(16666666);
    raw_vsync_waiter->TriggerVsync(start, target,
                                   /*can_target_current_thread=*/true);
    second_vsync_latch.Signal();
  });
  second_vsync_latch.Wait();
  EXPECT_GT(second_frame_begin_micros, 0);

  PlatformViewNotifyDestroyed(shell.get());
  DestroyShell(std::move(shell), task_runners);
  ASSERT_FALSE(DartVMRef::IsInstanceRunning());
}

TEST_F(ShellTest,
       PostedVsyncCallbackFlushesMicrotasksAndIsIdempotentWithTaskObserver) {
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

  std::atomic_int notify_count{0};
  fml::AutoResetWaitableEvent platform_message_response_latch;
  int64_t second_frame_begin_micros = 0;
  AddFfiNativeCallback(
      "NativeOnBeginFrame", CREATE_FFI_LAMBDA([&](int64_t microseconds) {
        second_frame_begin_micros = microseconds;
      }));
  AddFfiNativeCallback(
      "NotifyNative", CREATE_FFI_LAMBDA([&]() {
        const int count = ++notify_count;
        if (count == 1) {
          Dart_Handle schedule_frame_closure = Dart_GetField(
              Dart_RootLibrary(), tonic::ToDart("onBeginFrameMain"));
          ASSERT_FALSE(tonic::CheckAndHandleError(schedule_frame_closure));
          UIDartState::Current()->ScheduleMicrotask(schedule_frame_closure);

          Dart_Handle platform_message_closure = Dart_GetField(
              Dart_RootLibrary(),
              tonic::ToDart("testSkiaResourceCacheSendsResponse"));
          ASSERT_FALSE(tonic::CheckAndHandleError(platform_message_closure));
          UIDartState::Current()->ScheduleMicrotask(platform_message_closure);
        } else if (count == 3) {
          platform_message_response_latch.Signal();
        }
      }));

  auto configuration = RunConfiguration::InferFromSettings(settings);
  configuration.SetEntrypoint("renderSingleViewAndCallAfterOnDrawFrame");
  RunEngine(shell.get(), std::move(configuration));

  int notify_count_during_trigger = -1;
  int await_vsync_after_posted_vsync = 0;
  fml::AutoResetWaitableEvent posted_vsync_latch;
  task_runner->PostTask([&]() {
    EXPECT_EQ(raw_vsync_waiter->await_vsync_call_count_.load(), 1);
    const auto start = fml::TimePoint::Now();
    const auto target = start + fml::TimeDelta::FromNanoseconds(8333333);
    raw_vsync_waiter->TriggerVsync(start, target,
                                   /*can_target_current_thread=*/false);
    notify_count_during_trigger = notify_count.load();
    // Queue a task immediately after the posted VSync task to inspect state
    // right after Animator::EndFrame and the message-loop task observer run.
    task_runner->PostTask([&]() {
      await_vsync_after_posted_vsync =
          raw_vsync_waiter->await_vsync_call_count_.load();
      posted_vsync_latch.Signal();
    });
  });
  posted_vsync_latch.Wait();
  platform_message_response_latch.Wait();

  EXPECT_EQ(notify_count_during_trigger, 0);
  EXPECT_EQ(await_vsync_after_posted_vsync, 2);
  // 1 from onDrawFrame + 1 from onBeginFrameMain microtask + 1 from
  // flutter/skia platform message response callback (not duplicated by the
  // task observer after Engine::BeginFrame).
  EXPECT_EQ(notify_count.load(), 3);

  fml::AutoResetWaitableEvent second_vsync_latch;
  task_runner->PostTask([&]() {
    const auto start = fml::TimePoint::Now();
    const auto target = start + fml::TimeDelta::FromNanoseconds(16666666);
    raw_vsync_waiter->TriggerVsync(start, target,
                                   /*can_target_current_thread=*/false);
    task_runner->PostTask([&]() { second_vsync_latch.Signal(); });
  });
  second_vsync_latch.Wait();
  EXPECT_GT(second_frame_begin_micros, 0);

  PlatformViewNotifyDestroyed(shell.get());
  DestroyShell(std::move(shell), task_runners);
  ASSERT_FALSE(DartVMRef::IsInstanceRunning());
}

}  // namespace testing
}  // namespace flutter
