// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

#include "flutter/common/task_runners.h"
#include "flutter/display_list/dl_builder.h"
#include "flutter/display_list/dl_color.h"
#include "flutter/display_list/dl_paint.h"
#include "flutter/flow/compositor_context.h"
#include "flutter/flow/layers/container_layer.h"
#include "flutter/flow/layers/display_list_layer.h"
#include "flutter/flow/layers/layer_tree.h"
#include "flutter/flow/layers/platform_view_layer.h"
#include "flutter/flow/layers/texture_layer.h"
#include "flutter/flow/layers/transform_layer.h"
#include "flutter/flow/surface.h"
#include "flutter/flow/surface_frame.h"
#include "flutter/fml/message_loop_impl.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/task_runner.h"
#include "flutter/fml/thread.h"
#include "flutter/shell/platform/android/context/android_context.h"
#include "flutter/shell/platform/android/external_view_embedder/external_view_embedder_2.h"
#include "flutter/shell/platform/android/jni/jni_mock.h"
#include "flutter/shell/platform/android/surface/android_native_window.h"
#include "flutter/shell/platform/android/surface/android_surface.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"

namespace flutter {
namespace testing {
namespace {

using ::testing::_;
using ::testing::AllOf;
using ::testing::AnyNumber;
using ::testing::Gt;
using ::testing::InSequence;
using ::testing::Le;
using ::testing::Return;

class CountingTaskRunner final : public fml::TaskRunner {
 public:
  static fml::RefPtr<CountingTaskRunner> Make(
      fml::RefPtr<fml::TaskRunner> delegate) {
    return fml::AdoptRef(new CountingTaskRunner(std::move(delegate)));
  }

  void PostTask(const fml::closure& task) override {
    post_count_.fetch_add(1, std::memory_order_relaxed);
    delegate_->PostTask(task);
  }

  void PostTaskForTime(const fml::closure& task,
                       fml::TimePoint target_time) override {
    post_count_.fetch_add(1, std::memory_order_relaxed);
    delegate_->PostTaskForTime(task, target_time);
  }

  void PostDelayedTask(const fml::closure& task,
                       fml::TimeDelta delay) override {
    post_count_.fetch_add(1, std::memory_order_relaxed);
    delegate_->PostDelayedTask(task, delay);
  }

  bool RunsTasksOnCurrentThread() override {
    return delegate_->RunsTasksOnCurrentThread();
  }

  void Flush() {
    fml::AutoResetWaitableEvent latch;
    delegate_->PostTask([&latch]() { latch.Signal(); });
    latch.Wait();
  }

  int ResetAndGetPostCount() {
    return post_count_.exchange(0, std::memory_order_relaxed);
  }

 private:
  explicit CountingTaskRunner(fml::RefPtr<fml::TaskRunner> delegate)
      : fml::TaskRunner(nullptr), delegate_(std::move(delegate)) {}

  fml::RefPtr<fml::TaskRunner> delegate_;
  std::atomic<int> post_count_{0};
};

// Manual single-threaded task runner used to deterministically hold and drain
// posted platform tasks in exact interleavings with raster-thread submissions.
class ManualTaskRunner final : public fml::TaskRunner {
 public:
  static fml::RefPtr<ManualTaskRunner> Make() {
    return fml::AdoptRef(new ManualTaskRunner());
  }

  void PostTask(const fml::closure& task) override { Enqueue(task); }

  void PostTaskForTime(const fml::closure& task,
                       fml::TimePoint /*target_time*/) override {
    Enqueue(task);
  }

  void PostDelayedTask(const fml::closure& task,
                       fml::TimeDelta /*delay*/) override {
    Enqueue(task);
  }

  bool RunsTasksOnCurrentThread() override {
    return std::this_thread::get_id() == owner_thread_id_;
  }

  size_t PendingCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return tasks_.size();
  }

  void WaitForPendingCount(size_t min_count) {
    std::unique_lock<std::mutex> lock(mutex_);
    cv_.wait(lock, [&]() { return tasks_.size() >= min_count; });
  }

  void RunNext() {
    fml::closure task;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      ASSERT_FALSE(tasks_.empty());
      task = std::move(tasks_.front());
      tasks_.pop();
    }
    task();
  }

  void DrainAll() {
    while (PendingCount() > 0) {
      RunNext();
    }
  }

 private:
  ManualTaskRunner()
      : fml::TaskRunner(nullptr),
        owner_thread_id_(std::this_thread::get_id()) {}

  void Enqueue(const fml::closure& task) {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      tasks_.push(task);
    }
    cv_.notify_all();
  }

  std::thread::id owner_thread_id_;
  mutable std::mutex mutex_;
  std::condition_variable cv_;
  std::queue<fml::closure> tasks_;
};

class FakeOverlayGpuSurface final : public Surface {
 public:
  bool IsValid() override { return true; }

  std::unique_ptr<SurfaceFrame> AcquireFrame(const DlISize& size) override {
    return std::make_unique<SurfaceFrame>(
        nullptr, SurfaceFrame::FramebufferInfo{},
        [](SurfaceFrame&, DlCanvas*) { return true; },
        [](SurfaceFrame&) { return true; }, size, nullptr,
        /*display_list_fallback=*/true);
  }

  DlMatrix GetRootTransformation() const override { return {}; }
  GrDirectContext* GetContext() override { return nullptr; }
};

class FakeAndroidSurface final : public AndroidSurface {
 public:
  bool IsValid() const override { return true; }
  void TeardownOnScreenContext() override {}
  std::unique_ptr<Surface> CreateGPUSurface(GrDirectContext*) override {
    return std::make_unique<FakeOverlayGpuSurface>();
  }
  bool OnScreenSurfaceResize(const DlISize&) override { return true; }
  bool ResourceContextMakeCurrent() override { return true; }
  bool ResourceContextClearCurrent() override { return true; }
  bool SetNativeWindow(
      fml::RefPtr<AndroidNativeWindow>,
      const std::shared_ptr<PlatformViewAndroidJNI>&) override {
    return true;
  }
};

class FakeAndroidSurfaceFactory final : public AndroidSurfaceFactory {
 public:
  std::unique_ptr<AndroidSurface> CreateSurface() override {
    return std::make_unique<FakeAndroidSurface>();
  }
};

std::unique_ptr<SurfaceFrame> MakeObservingFrame(
    const DlISize& size,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni,
    bool* out_synced_during_submit) {
  return std::make_unique<SurfaceFrame>(
      nullptr, SurfaceFrame::FramebufferInfo{},
      [](SurfaceFrame&, DlCanvas*) { return true; },
      [jni, out_synced_during_submit](SurfaceFrame&) {
        if (out_synced_during_submit != nullptr) {
          *out_synced_during_submit =
              jni->ShouldSyncTransactionWithPlatformViews();
        }
        return true;
      },
      size, nullptr, /*display_list_fallback=*/true);
}

TEST(AndroidExternalViewEmbedder2Test,
     ZeroPlatformViewsFastPathPresentsOnRasterThreadWithZeroJniOrUiTasks) {
  fml::Thread platform_thread("test-platform");
  fml::Thread raster_thread("test-raster");
  auto counting_platform =
      CountingTaskRunner::Make(platform_thread.GetTaskRunner());
  TaskRunners runners("test", counting_platform, raster_thread.GetTaskRunner(),
                      platform_thread.GetTaskRunner(),
                      platform_thread.GetTaskRunner());

  AndroidContext android_context(AndroidRenderingAPI::kImpellerVulkan);
  auto jni_mock = std::make_shared<JNIMock>();
  auto surface_factory = std::make_shared<FakeAndroidSurfaceFactory>();

  EXPECT_CALL(*jni_mock, MaybeResizeSurfaceView(_, _)).Times(AnyNumber());
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(0);
  EXPECT_CALL(*jni_mock, swapTransaction()).Times(0);
  EXPECT_CALL(*jni_mock, onEndFrame2()).Times(0);
  EXPECT_CALL(*jni_mock, areTransactionsPending2()).Times(0);
  EXPECT_CALL(*jni_mock, waitForPendingTransactions2(_)).Times(0);

  AndroidExternalViewEmbedder2 embedder(android_context, jni_mock,
                                        surface_factory, runners);

  const DlISize kFrameSize(1080, 2400);
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  counting_platform->Flush();
  counting_platform->ResetAndGetPostCount();

  for (int i = 0; i < 3; ++i) {
    embedder.PrepareFlutterView(kFrameSize, 2.0);
    EXPECT_FALSE(jni_mock->ShouldSyncTransactionWithPlatformViews());

    bool synced_during_submit = true;
    auto frame =
        MakeObservingFrame(kFrameSize, jni_mock, &synced_during_submit);
    embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));

    EXPECT_FALSE(synced_during_submit);
    EXPECT_FALSE(jni_mock->ShouldSyncTransactionWithPlatformViews());
    EXPECT_EQ(counting_platform->ResetAndGetPostCount(), 0);
  }
}

TEST(
    AndroidExternalViewEmbedder2Test,
    ActiveHcppViewSyncsCleanupFrameAndReturnsToFastPathAfterTransactionsDrain) {
  fml::Thread platform_thread("test-platform");
  fml::Thread raster_thread("test-raster");
  auto counting_platform =
      CountingTaskRunner::Make(platform_thread.GetTaskRunner());
  TaskRunners runners("test", counting_platform, raster_thread.GetTaskRunner(),
                      platform_thread.GetTaskRunner(),
                      platform_thread.GetTaskRunner());

  AndroidContext android_context(AndroidRenderingAPI::kImpellerVulkan);
  auto jni_mock = std::make_shared<JNIMock>();
  auto surface_factory = std::make_shared<FakeAndroidSurfaceFactory>();

  EXPECT_CALL(*jni_mock, MaybeResizeSurfaceView(_, _)).Times(AnyNumber());
  EXPECT_CALL(*jni_mock, createOverlaySurface2()).Times(AnyNumber());
  ON_CALL(*jni_mock, createOverlaySurface2()).WillByDefault([]() {
    auto fake_window = fml::MakeRefCounted<AndroidNativeWindow>(
        nullptr, /*is_fake_window=*/true);
    return std::make_unique<PlatformViewAndroidJNI::OverlayMetadata>(
        0, std::move(fake_window));
  });
  ON_CALL(*jni_mock, areTransactionsPending2()).WillByDefault(Return(false));

  AndroidExternalViewEmbedder2 embedder(android_context, jni_mock,
                                        surface_factory, runners);
  const DlISize kFrameSize(1080, 2400);
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  counting_platform->Flush();
  counting_platform->ResetAndGetPostCount();

  // Frame 1: HCPP view 42 enters (e.g. dynamic upgrade from TLHC).
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
  EXPECT_CALL(*jni_mock, onDisplayPlatformView2(42, _, _, _, _, _, _, _))
      .Times(1);
  EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
  EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);

  bool synced_active = false;
  fml::AutoResetWaitableEvent active_done;
  raster_thread.GetTaskRunner()->PostTask([&]() {
    embedder.PrepareFlutterView(kFrameSize, 2.0);
    MutatorsStack mutators;
    auto params = std::make_unique<EmbeddedViewParams>(
        DlMatrix(), DlSize(200, 150), mutators);
    embedder.PrerollCompositeEmbeddedView(42, std::move(params));

    auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_active);
    embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
    EXPECT_FALSE(jni_mock->ShouldSyncTransactionWithPlatformViews());
    active_done.Signal();
  });
  active_done.Wait();
  counting_platform->Flush();
  EXPECT_TRUE(synced_active);
  counting_platform->ResetAndGetPostCount();
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame 2: View 42 removed -> cleanup frame syncs 1 transaction to atomically
  // hide view 42 and present the un-punched Flutter backdrop.
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
  EXPECT_CALL(*jni_mock, hidePlatformView2(42)).Times(1);
  EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
  EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);

  bool synced_cleanup = false;
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  auto cleanup_frame =
      MakeObservingFrame(kFrameSize, jni_mock, &synced_cleanup);
  embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(cleanup_frame));
  counting_platform->Flush();
  EXPECT_TRUE(synced_cleanup);
  EXPECT_FALSE(jni_mock->ShouldSyncTransactionWithPlatformViews());
  EXPECT_EQ(counting_platform->ResetAndGetPostCount(), 1);
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame 3: Cleanup transactions drain via waitForPendingTransactions2(32)
  // once, then both Frame 3 and Frame 4 take the steady-state fast path with 0
  // UI tasks and 0 further JNI calls.
  EXPECT_CALL(*jni_mock, waitForPendingTransactions2(32))
      .Times(1)
      .WillOnce(Return(true));
  EXPECT_CALL(*jni_mock, areTransactionsPending2()).Times(0);
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(0);
  EXPECT_CALL(*jni_mock, swapTransaction()).Times(0);
  EXPECT_CALL(*jni_mock, onEndFrame2()).Times(0);

  for (int i = 0; i < 2; ++i) {
    bool synced_steady = true;
    embedder.PrepareFlutterView(kFrameSize, 2.0);
    auto steady_frame =
        MakeObservingFrame(kFrameSize, jni_mock, &synced_steady);
    embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(steady_frame));
    EXPECT_FALSE(synced_steady);
    EXPECT_EQ(counting_platform->ResetAndGetPostCount(), 0);
  }

  EXPECT_CALL(*jni_mock, destroyOverlaySurface2()).Times(1);
  fml::AutoResetWaitableEvent teardown_done;
  raster_thread.GetTaskRunner()->PostTask([&]() {
    embedder.Teardown();
    teardown_done.Signal();
  });
  teardown_done.Wait();
}

TEST(
    AndroidExternalViewEmbedder2Test,
    PostCleanupZeroPvFrameDoesNotOvertakePendingCleanupUiTaskOrStagedTraversal) {
  auto manual_platform = ManualTaskRunner::Make();
  fml::Thread raster_thread("test-raster");
  TaskRunners runners("test", manual_platform, raster_thread.GetTaskRunner(),
                      manual_platform, manual_platform);

  AndroidContext android_context(AndroidRenderingAPI::kImpellerVulkan);
  auto jni_mock = std::make_shared<JNIMock>();
  auto surface_factory = std::make_shared<FakeAndroidSurfaceFactory>();

  EXPECT_CALL(*jni_mock, MaybeResizeSurfaceView(_, _)).Times(AnyNumber());
  EXPECT_CALL(*jni_mock, createOverlaySurface2()).Times(AnyNumber());
  ON_CALL(*jni_mock, createOverlaySurface2()).WillByDefault([]() {
    auto fake_window = fml::MakeRefCounted<AndroidNativeWindow>(
        nullptr, /*is_fake_window=*/true);
    return std::make_unique<PlatformViewAndroidJNI::OverlayMetadata>(
        0, std::move(fake_window));
  });

  AndroidExternalViewEmbedder2 embedder(android_context, jni_mock,
                                        surface_factory, runners);
  const DlISize kFrameSize(1080, 2400);
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  manual_platform->DrainAll();

  // Frame 1: HCPP view 7 active. Pre-create overlay layer cleanly on a separate
  // raster thread while pumping manual_platform.
  {
    fml::AutoResetWaitableEvent f1_done;
    bool synced_f1 = false;
    raster_thread.GetTaskRunner()->PostTask([&]() {
      embedder.PrepareFlutterView(kFrameSize, 2.0);
      MutatorsStack mutators;
      auto params = std::make_unique<EmbeddedViewParams>(
          DlMatrix(), DlSize(100, 100), mutators);
      embedder.PrerollCompositeEmbeddedView(7, std::move(params));
      auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_f1);
      embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
      f1_done.Signal();
    });
    // Wait until SurfacePool overlay creation task is posted, run it, then wait
    // for SubmitFlutterView to finish.
    manual_platform->WaitForPendingCount(1);
    manual_platform->RunNext();
    f1_done.Wait();
    EXPECT_TRUE(synced_f1);
    manual_platform->DrainAll();
  }
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N (cleanup): view 7 removed. Submit on raster thread WITHOUT draining
  // manual_platform yet, simulating UI thread busy before onEndFrame2 runs.
  bool synced_cleanup_n = false;
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  embedder.SubmitFlutterView(
      0, nullptr, nullptr,
      MakeObservingFrame(kFrameSize, jni_mock, &synced_cleanup_n));
  EXPECT_TRUE(synced_cleanup_n);
  ASSERT_EQ(manual_platform->PendingCount(), 1u);

  // Frame N+1 (0 PVs) arrives BEFORE N's UI task has executed:
  // MUST NOT take the fast path (`synced_n_plus_1` must be true so N+1 is
  // queued in FIFO order behind N rather than overtaking N via direct apply).
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
  bool synced_n_plus_1 = false;
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  embedder.SubmitFlutterView(
      0, nullptr, nullptr,
      MakeObservingFrame(kFrameSize, jni_mock, &synced_n_plus_1));
  EXPECT_TRUE(synced_n_plus_1);
  ASSERT_EQ(manual_platform->PendingCount(), 2u);

  // Drain both UI tasks in strict FIFO order (N hides view 7 first, then N+1).
  {
    InSequence seq;
    EXPECT_CALL(*jni_mock, hidePlatformView2(7)).Times(1);
    EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
    EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);
    EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
    EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);
  }
  manual_platform->DrainAll();
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N+2 (0 PVs) arrives while Choreographer traversal for N/N+1 is STILL
  // staged (`areTransactionsPending2() == true`): MUST still stay synced!
  EXPECT_CALL(*jni_mock, areTransactionsPending2())
      .Times(1)
      .WillOnce(Return(true));
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
  bool synced_n_plus_2 = false;
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  embedder.SubmitFlutterView(
      0, nullptr, nullptr,
      MakeObservingFrame(kFrameSize, jni_mock, &synced_n_plus_2));
  EXPECT_TRUE(synced_n_plus_2);
  manual_platform->DrainAll();
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N+3 (0 PVs) arrives AFTER Choreographer traversal completes
  // (`areTransactionsPending2() == false`): NOW takes the fast path!
  EXPECT_CALL(*jni_mock, areTransactionsPending2())
      .Times(1)
      .WillOnce(Return(false));
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(0);
  bool synced_n_plus_3 = true;
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  embedder.SubmitFlutterView(
      0, nullptr, nullptr,
      MakeObservingFrame(kFrameSize, jni_mock, &synced_n_plus_3));
  EXPECT_FALSE(synced_n_plus_3);
  EXPECT_EQ(manual_platform->PendingCount(), 0u);

  embedder.Teardown();
}

TEST(AndroidExternalViewEmbedder2Test,
     DedicatedRasterThreadWaitsForCleanupTraversalAndBreaksDominoChain) {
  auto manual_platform = ManualTaskRunner::Make();
  fml::Thread raster_thread("test-raster");
  TaskRunners runners("test", manual_platform, raster_thread.GetTaskRunner(),
                      manual_platform, manual_platform);

  AndroidContext android_context(AndroidRenderingAPI::kImpellerVulkan);
  auto jni_mock = std::make_shared<JNIMock>();
  auto surface_factory = std::make_shared<FakeAndroidSurfaceFactory>();

  EXPECT_CALL(*jni_mock, MaybeResizeSurfaceView(_, _)).Times(AnyNumber());
  EXPECT_CALL(*jni_mock, createOverlaySurface2()).Times(AnyNumber());
  ON_CALL(*jni_mock, createOverlaySurface2()).WillByDefault([]() {
    auto fake_window = fml::MakeRefCounted<AndroidNativeWindow>(
        nullptr, /*is_fake_window=*/true);
    return std::make_unique<PlatformViewAndroidJNI::OverlayMetadata>(
        0, std::move(fake_window));
  });

  AndroidExternalViewEmbedder2 embedder(android_context, jni_mock,
                                        surface_factory, runners);
  const DlISize kFrameSize(1080, 2400);
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  manual_platform->DrainAll();

  // Frame 1: HCPP view 9 active on dedicated raster thread.
  {
    fml::AutoResetWaitableEvent f1_done;
    bool synced_f1 = false;
    raster_thread.GetTaskRunner()->PostTask([&]() {
      embedder.PrepareFlutterView(kFrameSize, 2.0);
      MutatorsStack mutators;
      auto params = std::make_unique<EmbeddedViewParams>(
          DlMatrix(), DlSize(100, 100), mutators);
      embedder.PrerollCompositeEmbeddedView(9, std::move(params));
      auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_f1);
      embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
      f1_done.Signal();
    });
    manual_platform->WaitForPendingCount(1);
    manual_platform->RunNext();
    f1_done.Wait();
    EXPECT_TRUE(synced_f1);
    manual_platform->DrainAll();
  }
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N (cleanup): view 9 removed on raster_thread; hold its UI task in
  // manual_platform so both pending_platform_end_frames_ and staged traversal
  // are still in flight when continuous 60Hz/120Hz Frame N+1 starts.
  {
    fml::AutoResetWaitableEvent fn_done;
    bool synced_fn = false;
    EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
    raster_thread.GetTaskRunner()->PostTask([&]() {
      embedder.PrepareFlutterView(kFrameSize, 2.0);
      auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_fn);
      embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
      fn_done.Signal();
    });
    fn_done.Wait();
    EXPECT_TRUE(synced_fn);
    ASSERT_EQ(manual_platform->PendingCount(), 1u);
  }
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N+1 (0 PVs, continuous animation) starts on raster_thread while N's
  // cleanup UI task is still queued AND Choreographer traversal is staged.
  fml::AutoResetWaitableEvent started_fn1;
  fml::AutoResetWaitableEvent entered_wait_for_tx;
  fml::AutoResetWaitableEvent traversal_complete;
  fml::AutoResetWaitableEvent fn1_done;
  std::atomic<bool> fn1_submitted{false};
  bool synced_fn1 = true;

  EXPECT_CALL(*jni_mock, hidePlatformView2(9)).Times(1);
  EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
  EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);
  // N+1 MUST NOT call sealFrameTransactions() or post another onEndFrame2()!
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(0);
  EXPECT_CALL(*jni_mock, waitForPendingTransactions2(AllOf(Gt(0), Le(32))))
      .Times(1)
      .WillOnce([&](int64_t) {
        entered_wait_for_tx.Signal();
        traversal_complete.Wait();
        return true;
      });

  raster_thread.GetTaskRunner()->PostTask([&]() {
    embedder.PrepareFlutterView(kFrameSize, 2.0);
    auto frame = std::make_unique<SurfaceFrame>(
        nullptr, SurfaceFrame::FramebufferInfo{},
        [](SurfaceFrame&, DlCanvas*) { return true; },
        [&](SurfaceFrame&) {
          synced_fn1 = jni_mock->ShouldSyncTransactionWithPlatformViews();
          fn1_submitted.store(true, std::memory_order_release);
          return true;
        },
        kFrameSize, nullptr, /*display_list_fallback=*/true);
    started_fn1.Signal();
    embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
    fn1_done.Signal();
  });

  // Before N's UI task runs, N+1 cannot have submitted yet.
  started_fn1.Wait();
  EXPECT_FALSE(fn1_submitted.load(std::memory_order_acquire));

  // Drain N's UI task -> unblocks PendingPlatformEndFrames, enters
  // waitForPendingTransactions2(32), still blocked on Choreographer traversal.
  manual_platform->RunNext();
  entered_wait_for_tx.Wait();
  EXPECT_FALSE(fn1_submitted.load(std::memory_order_acquire));

  // Complete Choreographer traversal -> N+1 immediately submits via the
  // decoupled fast path (synced_fn1 == false) and posts ZERO UI tasks!
  traversal_complete.Signal();
  fn1_done.Wait();
  EXPECT_TRUE(fn1_submitted.load(std::memory_order_acquire));
  EXPECT_FALSE(synced_fn1);
  EXPECT_EQ(manual_platform->PendingCount(), 0u);
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N+2 (0 PVs): zero waits, zero JNI calls, zero UI tasks.
  EXPECT_CALL(*jni_mock, waitForPendingTransactions2(_)).Times(0);
  EXPECT_CALL(*jni_mock, areTransactionsPending2()).Times(0);
  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(0);
  EXPECT_CALL(*jni_mock, onEndFrame2()).Times(0);
  fml::AutoResetWaitableEvent fn2_done;
  bool synced_fn2 = true;
  raster_thread.GetTaskRunner()->PostTask([&]() {
    embedder.PrepareFlutterView(kFrameSize, 2.0);
    auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_fn2);
    embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
    fn2_done.Signal();
  });
  fn2_done.Wait();
  EXPECT_FALSE(synced_fn2);
  EXPECT_EQ(manual_platform->PendingCount(), 0u);

  EXPECT_CALL(*jni_mock, destroyOverlaySurface2()).Times(1);
  embedder.Teardown();
}

TEST(AndroidExternalViewEmbedder2Test,
     TimeoutWaitingForTransactionCommitStaysOnSyncedPathUntilCommitArrives) {
  fml::Thread platform_thread("test-platform");
  fml::Thread raster_thread("test-raster");
  auto counting_platform =
      CountingTaskRunner::Make(platform_thread.GetTaskRunner());
  TaskRunners runners("test", counting_platform, raster_thread.GetTaskRunner(),
                      platform_thread.GetTaskRunner(),
                      platform_thread.GetTaskRunner());

  AndroidContext android_context(AndroidRenderingAPI::kImpellerVulkan);
  auto jni_mock = std::make_shared<JNIMock>();
  auto surface_factory = std::make_shared<FakeAndroidSurfaceFactory>();

  EXPECT_CALL(*jni_mock, MaybeResizeSurfaceView(_, _)).Times(AnyNumber());
  EXPECT_CALL(*jni_mock, createOverlaySurface2()).Times(AnyNumber());
  ON_CALL(*jni_mock, createOverlaySurface2()).WillByDefault([]() {
    auto fake_window = fml::MakeRefCounted<AndroidNativeWindow>(
        nullptr, /*is_fake_window=*/true);
    return std::make_unique<PlatformViewAndroidJNI::OverlayMetadata>(
        0, std::move(fake_window));
  });

  AndroidExternalViewEmbedder2 embedder(android_context, jni_mock,
                                        surface_factory, runners);
  const DlISize kFrameSize(1080, 2400);
  embedder.PrepareFlutterView(kFrameSize, 2.0);
  counting_platform->Flush();
  counting_platform->ResetAndGetPostCount();

  // Frame 1: HCPP view 11 active.
  {
    fml::AutoResetWaitableEvent f1_done;
    bool synced_f1 = false;
    EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
    EXPECT_CALL(*jni_mock, onDisplayPlatformView2(11, _, _, _, _, _, _, _))
        .Times(1);
    EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
    EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);
    raster_thread.GetTaskRunner()->PostTask([&]() {
      embedder.PrepareFlutterView(kFrameSize, 2.0);
      MutatorsStack mutators;
      auto params = std::make_unique<EmbeddedViewParams>(
          DlMatrix(), DlSize(120, 80), mutators);
      embedder.PrerollCompositeEmbeddedView(11, std::move(params));
      auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_f1);
      embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
      f1_done.Signal();
    });
    f1_done.Wait();
    counting_platform->Flush();
    EXPECT_TRUE(synced_f1);
    counting_platform->ResetAndGetPostCount();
  }
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N (cleanup): view 11 removed -> UI task runs onEndFrame2(), staging
  // Frame N's transaction for SurfaceFlinger commit.
  {
    fml::AutoResetWaitableEvent fn_done;
    bool synced_fn = false;
    EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
    EXPECT_CALL(*jni_mock, hidePlatformView2(11)).Times(1);
    EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
    EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);
    raster_thread.GetTaskRunner()->PostTask([&]() {
      embedder.PrepareFlutterView(kFrameSize, 2.0);
      auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_fn);
      embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
      fn_done.Signal();
    });
    fn_done.Wait();
    counting_platform->Flush();
    EXPECT_TRUE(synced_fn);
    EXPECT_EQ(counting_platform->ResetAndGetPostCount(), 1);
  }
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N+1 (0 PVs): SurfaceFlinger commit of Frame N is delayed past the
  // 32ms timeout (`waitForPendingTransactions2(32) == false`). Frame N+1 MUST
  // stay on the UI-synced path rather than overtaking Frame N via fast path!
  {
    fml::AutoResetWaitableEvent fn1_done;
    bool synced_fn1 = false;
    EXPECT_CALL(*jni_mock, waitForPendingTransactions2(32))
        .Times(1)
        .WillOnce(Return(false));
    EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
    EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
    EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);
    raster_thread.GetTaskRunner()->PostTask([&]() {
      embedder.PrepareFlutterView(kFrameSize, 2.0);
      auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_fn1);
      embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
      fn1_done.Signal();
    });
    fn1_done.Wait();
    counting_platform->Flush();
    EXPECT_TRUE(synced_fn1);
    EXPECT_EQ(counting_platform->ResetAndGetPostCount(), 1);
  }
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  // Frame N+2 (0 PVs): SurfaceFlinger commit now succeeds (`true`) -> breaks
  // the chain onto the decoupled fast path!
  {
    fml::AutoResetWaitableEvent fn2_done;
    bool synced_fn2 = true;
    EXPECT_CALL(*jni_mock, waitForPendingTransactions2(32))
        .Times(1)
        .WillOnce(Return(true));
    EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(0);
    EXPECT_CALL(*jni_mock, swapTransaction()).Times(0);
    EXPECT_CALL(*jni_mock, onEndFrame2()).Times(0);
    raster_thread.GetTaskRunner()->PostTask([&]() {
      embedder.PrepareFlutterView(kFrameSize, 2.0);
      auto frame = MakeObservingFrame(kFrameSize, jni_mock, &synced_fn2);
      embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
      fn2_done.Signal();
    });
    fn2_done.Wait();
    EXPECT_FALSE(synced_fn2);
    EXPECT_EQ(counting_platform->ResetAndGetPostCount(), 0);
  }
  ::testing::Mock::VerifyAndClearExpectations(jni_mock.get());

  EXPECT_CALL(*jni_mock, destroyOverlaySurface2()).Times(1);
  fml::AutoResetWaitableEvent teardown_done;
  raster_thread.GetTaskRunner()->PostTask([&]() {
    embedder.Teardown();
    teardown_done.Signal();
  });
  teardown_done.Wait();
}

TEST(AndroidExternalViewEmbedder2Test,
     CompositorContextPreservesPartialRepaintAndResetsDamageOnDynamicUpgrade) {
  fml::Thread platform_thread("test-platform");
  fml::Thread raster_thread("test-raster");
  TaskRunners runners(
      "test", platform_thread.GetTaskRunner(), raster_thread.GetTaskRunner(),
      platform_thread.GetTaskRunner(), platform_thread.GetTaskRunner());

  AndroidContext android_context(AndroidRenderingAPI::kImpellerVulkan);
  auto jni_mock = std::make_shared<JNIMock>();
  auto surface_factory = std::make_shared<FakeAndroidSurfaceFactory>();

  EXPECT_CALL(*jni_mock, MaybeResizeSurfaceView(_, _)).Times(AnyNumber());
  EXPECT_CALL(*jni_mock, createOverlaySurface2()).Times(AnyNumber());
  ON_CALL(*jni_mock, createOverlaySurface2()).WillByDefault([]() {
    auto fake_window = fml::MakeRefCounted<AndroidNativeWindow>(
        nullptr, /*is_fake_window=*/true);
    return std::make_unique<PlatformViewAndroidJNI::OverlayMetadata>(
        0, std::move(fake_window));
  });

  AndroidExternalViewEmbedder2 embedder(android_context, jni_mock,
                                        surface_factory, runners);
  CompositorContext compositor_context;
  const DlISize kSize(1000, 1000);

  auto make_rect_dl = [](const DlRect& rect, DlColor color) {
    DisplayListBuilder builder;
    DlPaint paint(color);
    builder.DrawRect(rect, paint);
    return builder.Build();
  };

  auto bg_dl = make_rect_dl(DlRect::MakeWH(1000, 1000), DlColor::kBlue());

  // Frame 1: Initial 0-PV frame establishes baseline LayerTree.
  auto root_1 = std::make_shared<ContainerLayer>();
  root_1->Add(
      std::make_shared<DisplayListLayer>(DlPoint(0, 0), bg_dl, false, false));
  root_1->Add(std::make_shared<DisplayListLayer>(
      DlPoint(0, 0),
      make_rect_dl(DlRect::MakeXYWH(10, 10, 80, 80), DlColor::kRed()), false,
      false));
  LayerTree tree_1(root_1, kSize);

  {
    embedder.PrepareFlutterView(kSize, 1.0);
    DisplayListBuilder canvas_builder;
    auto scoped = compositor_context.AcquireFrame(nullptr, &canvas_builder,
                                                  &embedder, DlMatrix(), false,
                                                  true, nullptr, nullptr);
    FrameDamage damage_1;
    EXPECT_EQ(scoped->Raster(tree_1, /*ignore_raster_cache=*/true, &damage_1),
              RasterStatus::kSuccess);
    ASSERT_TRUE(damage_1.GetBufferDamage().has_value());
    EXPECT_EQ(*damage_1.GetBufferDamage(), DlIRect::MakeWH(1000, 1000));
  }

  // Frame 2: Steady-state 0-PV frame with only an 80x80 region updated ->
  // partial repaint MUST be preserved (80x80, not 1000x1000).
  auto root_2 = std::make_shared<ContainerLayer>();
  root_2->Add(
      std::make_shared<DisplayListLayer>(DlPoint(0, 0), bg_dl, false, false));
  root_2->Add(std::make_shared<DisplayListLayer>(
      DlPoint(0, 0),
      make_rect_dl(DlRect::MakeXYWH(10, 10, 80, 80), DlColor::kGreen()), false,
      false));
  LayerTree tree_2(root_2, kSize);

  {
    embedder.PrepareFlutterView(kSize, 1.0);
    DisplayListBuilder canvas_builder;
    auto scoped = compositor_context.AcquireFrame(nullptr, &canvas_builder,
                                                  &embedder, DlMatrix(), false,
                                                  true, nullptr, nullptr);
    FrameDamage damage_2;
    damage_2.SetPreviousLayerTree(&tree_1);
    damage_2.AddAdditionalDamage(DlIRect::MakeXYWH(10, 10, 80, 80));
    EXPECT_EQ(scoped->Raster(tree_2, /*ignore_raster_cache=*/true, &damage_2),
              RasterStatus::kSuccess);
    ASSERT_TRUE(damage_2.GetBufferDamage().has_value());
    EXPECT_EQ(*damage_2.GetBufferDamage(), DlIRect::MakeXYWH(10, 10, 80, 80));
  }

  // Frame 2.5: TLHC platform view enters at (300, 300, 200, 150) via
  // TextureLayer (0 PlatformViewLayers, so embedder stays on fast path).
  auto root_tlhc = std::make_shared<ContainerLayer>();
  root_tlhc->Add(
      std::make_shared<DisplayListLayer>(DlPoint(0, 0), bg_dl, false, false));
  root_tlhc->Add(std::make_shared<DisplayListLayer>(
      DlPoint(0, 0),
      make_rect_dl(DlRect::MakeXYWH(10, 10, 80, 80), DlColor::kGreen()), false,
      false));
  auto tlhc_transform =
      std::make_shared<TransformLayer>(DlMatrix::MakeTranslation({300, 300}));
  tlhc_transform->Add(std::make_shared<TextureLayer>(
      DlPoint(0, 0), DlSize(200, 150), /*texture_id=*/7, /*freeze=*/false,
      DlImageSampling::kLinear));
  root_tlhc->Add(tlhc_transform);
  LayerTree tree_tlhc(root_tlhc, kSize);

  {
    embedder.PrepareFlutterView(kSize, 1.0);
    DisplayListBuilder canvas_builder;
    auto scoped = compositor_context.AcquireFrame(nullptr, &canvas_builder,
                                                  &embedder, DlMatrix(), false,
                                                  true, nullptr, nullptr);
    FrameDamage damage_tlhc;
    damage_tlhc.SetPreviousLayerTree(&tree_2);
    EXPECT_EQ(
        scoped->Raster(tree_tlhc, /*ignore_raster_cache=*/true, &damage_tlhc),
        RasterStatus::kSuccess);
    ASSERT_TRUE(damage_tlhc.GetBufferDamage().has_value());
    EXPECT_EQ(*damage_tlhc.GetBufferDamage(),
              DlIRect::MakeXYWH(300, 300, 200, 150));
    bool synced_tlhc = true;
    auto frame_tlhc = MakeObservingFrame(kSize, jni_mock, &synced_tlhc);
    embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame_tlhc));
    EXPECT_FALSE(synced_tlhc);
  }

  // Frame 3: Dynamic TLHC -> HCPP upgrade transition frame!
  // Removing the previous TextureLayer diffs its 200x150 bounds into
  // FrameDamage (clearing the old TLHC pixels on the root surface), while
  // PlatformViewLayer registers view 99 with AndroidExternalViewEmbedder2,
  // syncing the frame through PVC2.
  auto root_3 = std::make_shared<ContainerLayer>();
  root_3->Add(
      std::make_shared<DisplayListLayer>(DlPoint(0, 0), bg_dl, false, false));
  root_3->Add(std::make_shared<DisplayListLayer>(
      DlPoint(0, 0),
      make_rect_dl(DlRect::MakeXYWH(10, 10, 80, 80), DlColor::kGreen()), false,
      false));
  auto pv_transform =
      std::make_shared<TransformLayer>(DlMatrix::MakeTranslation({300, 300}));
  pv_transform->Add(
      std::make_shared<PlatformViewLayer>(DlPoint(0, 0), DlSize(200, 150), 99));
  root_3->Add(pv_transform);
  LayerTree tree_3(root_3, kSize);

  EXPECT_CALL(*jni_mock, sealFrameTransactions()).Times(1);
  EXPECT_CALL(*jni_mock,
              onDisplayPlatformView2(99, 300, 300, 200, 150, 200, 150, _))
      .Times(1);
  EXPECT_CALL(*jni_mock, swapTransaction()).Times(1);
  EXPECT_CALL(*jni_mock, onEndFrame2()).Times(1);

  fml::AutoResetWaitableEvent transition_done;
  raster_thread.GetTaskRunner()->PostTask([&]() {
    embedder.PrepareFlutterView(kSize, 1.0);

    DisplayListBuilder canvas_builder;
    auto scoped = compositor_context.AcquireFrame(nullptr, &canvas_builder,
                                                  &embedder, DlMatrix(), false,
                                                  true, nullptr, nullptr);
    FrameDamage damage_3;
    damage_3.SetPreviousLayerTree(&tree_tlhc);

    EXPECT_EQ(scoped->Raster(tree_3, /*ignore_raster_cache=*/true, &damage_3),
              RasterStatus::kSuccess);
    ASSERT_TRUE(damage_3.GetBufferDamage().has_value());
    EXPECT_EQ(*damage_3.GetBufferDamage(),
              DlIRect::MakeXYWH(300, 300, 200, 150));

    bool synced = false;
    auto frame = MakeObservingFrame(kSize, jni_mock, &synced);
    embedder.SubmitFlutterView(0, nullptr, nullptr, std::move(frame));
    EXPECT_TRUE(synced);
    transition_done.Signal();
  });
  transition_done.Wait();

  fml::AutoResetWaitableEvent flush_platform;
  platform_thread.GetTaskRunner()->PostTask(
      [&flush_platform]() { flush_platform.Signal(); });
  flush_platform.Wait();

  EXPECT_CALL(*jni_mock, destroyOverlaySurface2()).Times(1);
  fml::AutoResetWaitableEvent teardown_done;
  raster_thread.GetTaskRunner()->PostTask([&]() {
    embedder.Teardown();
    teardown_done.Signal();
  });
  teardown_done.Wait();
}

}  // namespace
}  // namespace testing
}  // namespace flutter
