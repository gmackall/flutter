// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#define FML_USED_ON_EMBEDDER

#include "flutter/shell/common/animator.h"

#include <functional>
#include <future>
#include <memory>

#include "flutter/shell/common/shell_test.h"
#include "flutter/shell/common/shell_test_platform_view.h"
#include "flutter/testing/post_task_sync.h"
#include "flutter/testing/testing.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"

// CREATE_FFI_LAMBDA is leaky by design
// NOLINTBEGIN(clang-analyzer-core.StackAddressEscape)

namespace flutter {
namespace testing {

constexpr int64_t kImplicitViewId = 0;

class FakeAnimatorDelegate : public Animator::Delegate {
 public:
  MOCK_METHOD(void,
              OnAnimatorBeginFrame,
              (fml::TimePoint frame_target_time, uint64_t frame_number),
              (override));

  void OnAnimatorNotifyIdle(fml::TimeDelta deadline) override {
    notify_idle_called_ = true;
    notify_idle_count_++;
  }

  MOCK_METHOD(void,
              OnAnimatorUpdateLatestFrameTargetTime,
              (fml::TimePoint frame_target_time),
              (override));

  MOCK_METHOD(void,
              OnAnimatorDraw,
              (std::shared_ptr<FramePipeline> pipeline),
              (override));

  void OnAnimatorDrawLastLayerTrees(
      std::unique_ptr<FrameTimingsRecorder> frame_timings_recorder) override {
    draw_last_layer_trees_count_++;
  }

  bool notify_idle_called_ = false;
  int notify_idle_count_ = 0;
  int draw_last_layer_trees_count_ = 0;
};

class CountingVsyncWaiter : public VsyncWaiter {
 public:
  explicit CountingVsyncWaiter(const TaskRunners& task_runners)
      : VsyncWaiter(task_runners) {}

  int await_vsync_call_count() const { return await_vsync_call_count_; }

  void TriggerVsync(fml::TimePoint frame_start_time,
                    fml::TimePoint frame_target_time) {
    FireCallback(frame_start_time, frame_target_time);
  }

 protected:
  void AwaitVSync() override { await_vsync_call_count_++; }

 private:
  int await_vsync_call_count_ = 0;
};

TEST_F(ShellTest, VSyncTargetTime) {
  // Add native callbacks to listen for window.onBeginFrame
  int64_t target_time;
  fml::AutoResetWaitableEvent on_target_time_latch;
  auto nativeOnBeginFrame = [&on_target_time_latch,
                             &target_time](int64_t microseconds) {
    target_time = microseconds;
    on_target_time_latch.Signal();
  };
  AddFfiNativeCallback("NativeOnBeginFrame",
                       CREATE_FFI_LAMBDA(nativeOnBeginFrame));
  AddFfiNativeCallback("NotifyNative", CREATE_FFI_LAMBDA([&]() {}));

  // Create all te prerequisites for a shell.
  ASSERT_FALSE(DartVMRef::IsInstanceRunning());
  auto settings = CreateSettingsForFixture();

  std::unique_ptr<Shell> shell;

  TaskRunners task_runners = GetTaskRunnersForFixture();
  // this is not used as we are not using simulated events.
  const auto vsync_clock = std::make_shared<ShellTestVsyncClock>();
  CreateVsyncWaiter create_vsync_waiter = [&]() {
    return static_cast<std::unique_ptr<VsyncWaiter>>(
        std::make_unique<ConstantFiringVsyncWaiter>(task_runners));
  };

  // create a shell with a constant firing vsync waiter.
  auto platform_task = std::async(std::launch::async, [&]() {
    fml::MessageLoop::EnsureInitializedForCurrentThread();

    shell = Shell::Create(
        flutter::PlatformData(), task_runners, settings,
        [vsync_clock, &create_vsync_waiter](Shell& shell) {
          return ShellTestPlatformView::Create(
              ShellTestPlatformView::DefaultBackendType(), shell,
              shell.GetTaskRunners(), vsync_clock, create_vsync_waiter, nullptr,
              shell.GetIsGpuDisabledSyncSwitch());
        },
        [](Shell& shell) { return std::make_unique<Rasterizer>(shell); });
    ASSERT_TRUE(DartVMRef::IsInstanceRunning());

    auto configuration = RunConfiguration::InferFromSettings(settings);
    ASSERT_TRUE(configuration.IsValid());
    configuration.SetEntrypoint("onBeginFrameMain");

    RunEngine(shell.get(), std::move(configuration));
  });
  platform_task.wait();
  on_target_time_latch.Wait();
  const auto vsync_waiter_target_time =
      ConstantFiringVsyncWaiter::kFrameTargetTime;
  ASSERT_EQ(vsync_waiter_target_time.ToEpochDelta().ToMicroseconds(),
            target_time);

  // validate that the latest target time has also been updated.
  ASSERT_EQ(GetLatestFrameTargetTime(shell.get()), vsync_waiter_target_time);

  // teardown.
  DestroyShell(std::move(shell), task_runners);
  ASSERT_FALSE(DartVMRef::IsInstanceRunning());
}

TEST_F(ShellTest, RequestFrameAwaitsVsyncSynchronously) {
  FakeAnimatorDelegate delegate;

  fml::MessageLoop::EnsureInitializedForCurrentThread();
  auto task_runner = fml::MessageLoop::GetCurrent().GetTaskRunner();
  TaskRunners task_runners("test", task_runner, task_runner, task_runner,
                           task_runner);

  auto vsync_waiter = std::make_unique<CountingVsyncWaiter>(task_runners);
  const auto* counting_vsync_waiter = vsync_waiter.get();
  Animator animator(delegate, task_runners, std::move(vsync_waiter));

  animator.RequestFrame(/*regenerate_layer_trees=*/false);

  EXPECT_EQ(counting_vsync_waiter->await_vsync_call_count(), 1);
}

TEST_F(ShellTest, TextureOnlyRequestFrameHeadroomAndFlowIdCleanup) {
  FakeAnimatorDelegate delegate;
  TaskRunners task_runners = {
      "test",
      CreateNewThread(),  // platform
      CreateNewThread(),  // raster
      CreateNewThread(),  // ui
      CreateNewThread()   // io
  };

  std::unique_ptr<Animator> animator;
  CountingVsyncWaiter* counting_vsync_waiter = nullptr;

  PostTaskSync(task_runners.GetUITaskRunner(), [&] {
    auto vsync_waiter = std::make_unique<CountingVsyncWaiter>(task_runners);
    counting_vsync_waiter = vsync_waiter.get();
    animator = std::make_unique<Animator>(delegate, task_runners,
                                          std::move(vsync_waiter));

    EXPECT_CALL(delegate, OnAnimatorBeginFrame).WillOnce([&] {
      auto layer_tree = std::make_unique<LayerTree>(nullptr, DlISize(600, 800));
      animator->Render(kImplicitViewId, std::move(layer_tree), 1.0);
    });
    EXPECT_CALL(delegate, OnAnimatorUpdateLatestFrameTargetTime).Times(1);
    EXPECT_CALL(delegate, OnAnimatorDraw).Times(1);

    // 1. Render initial frame with VSync window [now - 9ms, now - 1ms] (8ms
    // period), leaving 7ms >= 4ms headroom in the current VSync [now - 1ms,
    // now + 7ms].
    const auto now = fml::TimePoint::Now();
    animator->RequestFrame(/*regenerate_layer_trees=*/true);
    EXPECT_EQ(counting_vsync_waiter->await_vsync_call_count(), 1);
    counting_vsync_waiter->TriggerVsync(
        now - fml::TimeDelta::FromMilliseconds(9),
        now - fml::TimeDelta::FromMilliseconds(1));
  });

  // Flush the posted UI task from TriggerVsync (BeginFrame + Render + EndFrame)
  // and then request a texture-only frame with sufficient headroom.
  PostTaskSync(task_runners.GetUITaskRunner(), [&] {
    animator->EnqueueTraceFlowId(42);
    animator->RequestFrame(/*regenerate_layer_trees=*/false);
    // Should dispatch DrawLastLayerTrees via UI task without calling
    // AwaitVSync.
    EXPECT_EQ(counting_vsync_waiter->await_vsync_call_count(), 1);
    animator->MaybeCleanTraceFlowIds();
  });

  // Flush the posted UI task that executes DrawLastLayerTrees and
  // OnAnimatorNotifyIdle.
  PostTaskSync(task_runners.GetUITaskRunner(), [&] {
    EXPECT_EQ(delegate.draw_last_layer_trees_count_, 1);
    EXPECT_EQ(delegate.notify_idle_count_, 1);
    EXPECT_EQ(counting_vsync_waiter->await_vsync_call_count(), 1);

    // 2. Render a frame whose VSync window [now - 14ms, now - 6ms] leaves only
    // 2ms < 4ms (period / 2) headroom in the current VSync [now - 6ms,
    // now + 2ms], then verify RequestFrame(false) falls back to AwaitVSync().
    EXPECT_CALL(delegate, OnAnimatorBeginFrame).WillOnce([&] {
      auto layer_tree = std::make_unique<LayerTree>(nullptr, DlISize(600, 800));
      animator->Render(kImplicitViewId, std::move(layer_tree), 1.0);
    });
    EXPECT_CALL(delegate, OnAnimatorUpdateLatestFrameTargetTime).Times(1);

    const auto now = fml::TimePoint::Now();
    animator->RequestFrame(/*regenerate_layer_trees=*/true);
    EXPECT_EQ(counting_vsync_waiter->await_vsync_call_count(), 2);
    counting_vsync_waiter->TriggerVsync(
        now - fml::TimeDelta::FromMilliseconds(14),
        now - fml::TimeDelta::FromMilliseconds(6));
  });

  PostTaskSync(task_runners.GetUITaskRunner(), [&] {
    // RequestFrame(true) notified idle once for the new dart_frame_deadline_.
    EXPECT_EQ(delegate.notify_idle_count_, 2);

    // Late in current VSync (only ~2ms remaining of 8ms period < period / 2):
    // should fall back to AwaitVSync() to re-lock to the next hardware VSync,
    // and should not post a redundant OnAnimatorNotifyIdle task when
    // dart_frame_deadline_ has not changed.
    animator->RequestFrame(/*regenerate_layer_trees=*/false);
    EXPECT_EQ(counting_vsync_waiter->await_vsync_call_count(), 3);
  });

  PostTaskSync(task_runners.GetUITaskRunner(), [&] {
    EXPECT_EQ(delegate.notify_idle_count_, 2);
    animator.reset();
  });
}

TEST_F(ShellTest, AnimatorDoesNotNotifyIdleBeforeRender) {
  FakeAnimatorDelegate delegate;
  TaskRunners task_runners = {
      "test",
      CreateNewThread(),  // platform
      CreateNewThread(),  // raster
      CreateNewThread(),  // ui
      CreateNewThread()   // io
  };

  auto clock = std::make_shared<ShellTestVsyncClock>();
  fml::AutoResetWaitableEvent latch;
  std::shared_ptr<Animator> animator;

  auto flush_vsync_task = [&] {
    fml::AutoResetWaitableEvent ui_latch;
    task_runners.GetUITaskRunner()->PostTask([&] { ui_latch.Signal(); });
    do {
      clock->SimulateVSync();
    } while (ui_latch.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(1)));
    latch.Signal();
  };

  // Create the animator on the UI task runner.
  task_runners.GetUITaskRunner()->PostTask([&] {
    auto vsync_waiter = static_cast<std::unique_ptr<VsyncWaiter>>(
        std::make_unique<ShellTestVsyncWaiter>(task_runners, clock));
    animator = std::make_unique<Animator>(delegate, task_runners,
                                          std::move(vsync_waiter));
    latch.Signal();
  });
  latch.Wait();

  // Validate it has not notified idle and start it. This will request a frame.
  task_runners.GetUITaskRunner()->PostTask([&] {
    ASSERT_FALSE(delegate.notify_idle_called_);
    // Immediately request a frame saying it can reuse the last layer tree to
    // avoid more calls to BeginFrame by the animator.
    animator->RequestFrame(false);
    task_runners.GetPlatformTaskRunner()->PostTask(flush_vsync_task);
  });
  latch.Wait();
  ASSERT_FALSE(delegate.notify_idle_called_);

  fml::AutoResetWaitableEvent render_latch;
  // Validate it has not notified idle and try to render.
  task_runners.GetUITaskRunner()->PostDelayedTask(
      [&] {
        ASSERT_FALSE(delegate.notify_idle_called_);
        EXPECT_CALL(delegate, OnAnimatorBeginFrame).WillOnce([&] {
          auto layer_tree =
              std::make_unique<LayerTree>(nullptr, DlISize(600, 800));
          animator->Render(kImplicitViewId, std::move(layer_tree), 1.0);
          render_latch.Signal();
        });
        // Request a frame that builds a layer tree and renders a frame.
        // When the frame is rendered, render_latch will be signaled.
        animator->RequestFrame(true);
        task_runners.GetPlatformTaskRunner()->PostTask(flush_vsync_task);
      },
      // See kNotifyIdleTaskWaitTime in animator.cc.
      fml::TimeDelta::FromMilliseconds(60));
  latch.Wait();
  render_latch.Wait();

  // A frame has been rendered, and the next frame request will notify idle.
  // But at the moment there isn't another frame request, therefore it still
  // hasn't notified idle.
  task_runners.GetUITaskRunner()->PostTask([&] {
    ASSERT_FALSE(delegate.notify_idle_called_);
    // False to avoid getting cals to BeginFrame that will request more frames
    // before we are ready.
    animator->RequestFrame(false);
    task_runners.GetPlatformTaskRunner()->PostTask(flush_vsync_task);
  });
  latch.Wait();

  // Now it should notify idle. Make sure it is destroyed on the UI thread.
  ASSERT_TRUE(delegate.notify_idle_called_);

  task_runners.GetPlatformTaskRunner()->PostTask(flush_vsync_task);
  latch.Wait();

  task_runners.GetUITaskRunner()->PostTask([&] {
    animator.reset();
    latch.Signal();
  });
  latch.Wait();
}

TEST_F(ShellTest, AnimatorDoesNotNotifyDelegateIfPipelineIsNotEmpty) {
  FakeAnimatorDelegate delegate;
  TaskRunners task_runners = {
      "test",
      CreateNewThread(),  // platform
      CreateNewThread(),  // raster
      CreateNewThread(),  // ui
      CreateNewThread()   // io
  };

  auto clock = std::make_shared<ShellTestVsyncClock>();
  std::shared_ptr<Animator> animator;

  auto flush_vsync_task = [&] {
    fml::AutoResetWaitableEvent ui_latch;
    task_runners.GetUITaskRunner()->PostTask([&] { ui_latch.Signal(); });
    do {
      clock->SimulateVSync();
    } while (ui_latch.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(1)));
  };

  // Create the animator on the UI task runner.
  PostTaskSync(task_runners.GetUITaskRunner(), [&] {
    auto vsync_waiter = static_cast<std::unique_ptr<VsyncWaiter>>(
        std::make_unique<ShellTestVsyncWaiter>(task_runners, clock));
    animator = std::make_unique<Animator>(delegate, task_runners,
                                          std::move(vsync_waiter));
  });

  fml::AutoResetWaitableEvent begin_frame_latch;
  // It must always be called when the method 'Animator::Render' is called,
  // regardless of whether the pipeline is empty or not.
  EXPECT_CALL(delegate, OnAnimatorUpdateLatestFrameTargetTime).Times(2);
  // It will only be called once even though we call the method
  // 'Animator::Render' twice. because it will only be called when the pipeline
  // is empty.
  EXPECT_CALL(delegate, OnAnimatorDraw).Times(1);

  for (int i = 0; i < 2; i++) {
    task_runners.GetUITaskRunner()->PostTask([&] {
      EXPECT_CALL(delegate, OnAnimatorBeginFrame).WillOnce([&] {
        auto layer_tree =
            std::make_unique<LayerTree>(nullptr, DlISize(600, 800));
        animator->Render(kImplicitViewId, std::move(layer_tree), 1.0);
        begin_frame_latch.Signal();
      });
      animator->RequestFrame();
      task_runners.GetPlatformTaskRunner()->PostTask(flush_vsync_task);
    });
    begin_frame_latch.Wait();
  }

  PostTaskSync(task_runners.GetUITaskRunner(), [&] { animator.reset(); });
}

}  // namespace testing
}  // namespace flutter

// NOLINTEND(clang-analyzer-core.StackAddressEscape)
