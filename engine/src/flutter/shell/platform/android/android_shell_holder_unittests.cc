// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <sched.h>

#include <memory>
#include <thread>

#include "flutter/shell/platform/android/android_shell_holder.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "shell/platform/android/jni/platform_view_android_jni.h"

namespace flutter {
namespace testing {
namespace {
class MockPlatformViewAndroidJNI : public PlatformViewAndroidJNI {
 public:
  MOCK_METHOD(void,
              FlutterViewHandlePlatformMessage,
              (std::unique_ptr<flutter::PlatformMessage> message,
               int responseId),
              (override));
  MOCK_METHOD(void,
              FlutterViewHandlePlatformMessageResponse,
              (int responseId, std::unique_ptr<fml::Mapping> data),
              (override));
  MOCK_METHOD(void,
              FlutterViewUpdateSemantics,
              (std::vector<uint8_t> buffer,
               std::vector<std::string> strings,
               std::vector<std::vector<uint8_t>> string_attribute_args),
              (override));
  MOCK_METHOD(void,
              FlutterViewSetSemanticsTreeEnabled,
              (bool enabled),
              (override));
  MOCK_METHOD(void,
              FlutterViewSetApplicationLocale,
              (const std::string locale),
              (override));
  MOCK_METHOD(void,
              FlutterViewUpdateCustomAccessibilityActions,
              (std::vector<uint8_t> actions_buffer,
               std::vector<std::string> strings),
              (override));
  MOCK_METHOD(void, FlutterViewOnFirstFrame, (), (override));
  MOCK_METHOD(void, FlutterViewOnPreEngineRestart, (), (override));
  MOCK_METHOD(void,
              SurfaceTextureAttachToGLContext,
              (JavaLocalRef surface_texture, int textureId),
              (override));
  MOCK_METHOD(bool,
              SurfaceTextureShouldUpdate,
              (JavaLocalRef surface_texture),
              (override));
  MOCK_METHOD(void,
              SurfaceTextureUpdateTexImage,
              (JavaLocalRef surface_texture),
              (override));
  MOCK_METHOD(SkM44,
              SurfaceTextureGetTransformMatrix,
              (JavaLocalRef surface_texture),
              (override));
  MOCK_METHOD(void,
              SurfaceTextureDetachFromGLContext,
              (JavaLocalRef surface_texture),
              (override));
  MOCK_METHOD(JavaLocalRef,
              ImageProducerTextureEntryAcquireLatestImage,
              (JavaLocalRef image_texture_entry),
              (override));
  MOCK_METHOD(JavaLocalRef,
              ImageGetHardwareBuffer,
              (JavaLocalRef image),
              (override));
  MOCK_METHOD(void, ImageClose, (JavaLocalRef image), (override));
  MOCK_METHOD(void,
              HardwareBufferClose,
              (JavaLocalRef hardware_buffer),
              (override));
  MOCK_METHOD(void,
              FlutterViewOnDisplayPlatformView,
              (int view_id,
               int x,
               int y,
               int width,
               int height,
               int viewWidth,
               int viewHeight,
               MutatorsStack mutators_stack),
              (override));
  MOCK_METHOD(void,
              FlutterViewDisplayOverlaySurface,
              (int surface_id, int x, int y, int width, int height),
              (override));
  MOCK_METHOD(void, FlutterViewBeginFrame, (), (override));
  MOCK_METHOD(void, FlutterViewEndFrame, (), (override));
  MOCK_METHOD(std::unique_ptr<PlatformViewAndroidJNI::OverlayMetadata>,
              FlutterViewCreateOverlaySurface,
              (),
              (override));
  MOCK_METHOD(void, FlutterViewDestroyOverlaySurfaces, (), (override));
  MOCK_METHOD(ASurfaceTransaction*,
              createTransaction,
              (std::function<void()>&),
              (override));
  MOCK_METHOD(void, sealFrameTransactions, (), (override));
  MOCK_METHOD(void, swapTransaction, (), (override));
  MOCK_METHOD(void, destroyOverlaySurface2, (), (override));
  MOCK_METHOD(std::unique_ptr<PlatformViewAndroidJNI::OverlayMetadata>,
              createOverlaySurface2,
              (),
              (override));
  MOCK_METHOD(void,
              onDisplayPlatformView2,
              (int32_t view_id,
               int32_t x,
               int32_t y,
               int32_t width,
               int32_t height,
               int32_t viewWidth,
               int32_t viewHeight,
               MutatorsStack mutators_stack),
              (override));
  MOCK_METHOD(void, hidePlatformView2, (int32_t view_id), (override));
  MOCK_METHOD(void, onEndFrame2, (), (override));
  MOCK_METHOD(void, showOverlaySurface2, (), (override));
  MOCK_METHOD(void, hideOverlaySurface2, (), (override));
  MOCK_METHOD(std::unique_ptr<std::vector<std::string>>,
              FlutterViewComputePlatformResolvedLocale,
              (std::vector<std::string> supported_locales_data),
              (override));
  MOCK_METHOD(double, GetDisplayRefreshRate, (), (override));
  MOCK_METHOD(double, GetDisplayWidth, (), (override));
  MOCK_METHOD(double, GetDisplayHeight, (), (override));
  MOCK_METHOD(double, GetDisplayDensity, (), (override));
  MOCK_METHOD(bool,
              RequestDartDeferredLibrary,
              (int loading_unit_id),
              (override));
  MOCK_METHOD(double,
              FlutterViewGetScaledFontSize,
              (double font_size, int configuration_id),
              (const, override));
  MOCK_METHOD(void,
              MaybeResizeSurfaceView,
              (int32_t width, int32_t height),
              (const, override));
};

class MockPlatformMessageResponse : public PlatformMessageResponse {
 public:
  static fml::RefPtr<MockPlatformMessageResponse> Create() {
    return fml::AdoptRef(new MockPlatformMessageResponse());
  }
  MOCK_METHOD(void, Complete, (std::unique_ptr<fml::Mapping> data), (override));
  MOCK_METHOD(void, CompleteEmpty, (), (override));
};
}  // namespace

struct AndroidShellHolderTestAccess {
  static const std::shared_ptr<std::atomic<pid_t>>& GetRasterTidCell(
      const AndroidShellHolder& holder) {
    return holder.raster_thread_tid_;
  }

  static std::unique_ptr<AndroidShellHolder> CreateSharedThreadHostHolder(
      const AndroidShellHolder& parent,
      const std::shared_ptr<PlatformViewAndroidJNI>& child_jni) {
    TaskRunners task_runners = parent.shell_->GetTaskRunners();

    fml::WeakPtr<PlatformViewAndroid> weak_platform_view;
    Shell::CreateCallback<PlatformView> on_create_platform_view =
        [&child_jni, &weak_platform_view](Shell& shell) {
          auto platform_view_android = std::make_unique<PlatformViewAndroid>(
              shell, shell.GetTaskRunners(), child_jni,
              AndroidRenderingAPI::kImpellerOpenGLES);
          weak_platform_view = platform_view_android->GetWeakPtr();
          return platform_view_android;
        };
    Shell::CreateCallback<Rasterizer> on_create_rasterizer = [](Shell& shell) {
      return std::make_unique<Rasterizer>(shell);
    };

    PlatformData platform_data;
    platform_data.lifecycle_state = "AppLifecycleState.detached";
    auto child_shell =
        Shell::Create(platform_data, task_runners, parent.GetSettings(),
                      on_create_platform_view, on_create_rasterizer);
    if (!child_shell) {
      return nullptr;
    }

    return std::unique_ptr<AndroidShellHolder>(new AndroidShellHolder(
        parent.GetSettings(), child_jni, parent.raster_thread_tid_,
        parent.thread_host_, std::move(child_shell), nullptr,
        weak_platform_view, AndroidRenderingAPI::kImpellerOpenGLES));
  }
};

TEST(AndroidShellHolder, Create) {
  Settings settings;
  settings.enable_software_rendering = false;
  auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kImpellerOpenGLES);
  EXPECT_NE(holder.get(), nullptr);
  EXPECT_TRUE(holder->IsValid());
  EXPECT_NE(holder->GetPlatformView().get(), nullptr);
  auto window = fml::MakeRefCounted<AndroidNativeWindow>(
      nullptr, /*is_fake_window=*/true);
  holder->GetPlatformView()->NotifyCreated(window);
}

TEST(AndroidShellHolder, HandlePlatformMessage) {
  Settings settings;
  settings.enable_software_rendering = false;
  auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kImpellerOpenGLES);
  EXPECT_NE(holder.get(), nullptr);
  EXPECT_TRUE(holder->IsValid());
  EXPECT_NE(holder->GetPlatformView().get(), nullptr);
  auto window = fml::MakeRefCounted<AndroidNativeWindow>(
      nullptr, /*is_fake_window=*/true);
  holder->GetPlatformView()->NotifyCreated(window);
  EXPECT_TRUE(holder->GetPlatformMessageHandler());
  size_t data_size = 4;
  fml::MallocMapping bytes =
      fml::MallocMapping(static_cast<uint8_t*>(malloc(data_size)), data_size);
  fml::RefPtr<MockPlatformMessageResponse> response =
      MockPlatformMessageResponse::Create();
  auto message = std::make_unique<PlatformMessage>(
      /*channel=*/"foo", /*data=*/std::move(bytes), /*response=*/response);
  int response_id = 1;
  EXPECT_CALL(*jni,
              FlutterViewHandlePlatformMessage(::testing::_, response_id));
  EXPECT_CALL(*response, CompleteEmpty());
  holder->GetPlatformMessageHandler()->HandlePlatformMessage(
      std::move(message));
  holder->GetPlatformMessageHandler()
      ->InvokePlatformMessageEmptyResponseCallback(response_id);
}

TEST(AndroidShellHolder, CreateWithMergedPlatformAndUIThread) {
  Settings settings;
  auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kImpellerOpenGLES);
  auto window = fml::MakeRefCounted<AndroidNativeWindow>(
      nullptr, /*is_fake_window=*/true);
  holder->GetPlatformView()->NotifyCreated(window);

  EXPECT_EQ(
      holder->GetShellForTesting()->GetTaskRunners().GetUITaskRunner(),
      holder->GetShellForTesting()->GetTaskRunners().GetPlatformTaskRunner());
}

TEST(AndroidShellHolder, CreateWithUnMergedPlatformAndUIThread) {
  Settings settings;
  settings.merged_platform_ui_thread =
      Settings::MergedPlatformUIThread::kDisabled;
  auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kImpellerOpenGLES);
  auto window = fml::MakeRefCounted<AndroidNativeWindow>(
      nullptr, /*is_fake_window=*/true);
  holder->GetPlatformView()->NotifyCreated(window);

  EXPECT_NE(
      holder->GetShellForTesting()->GetTaskRunners().GetUITaskRunner(),
      holder->GetShellForTesting()->GetTaskRunners().GetPlatformTaskRunner());
}

TEST(AndroidShellHolder,
     PerEngineRasterThreadTidIsolationSharedSpawnAndTeardownOrders) {
  Settings settings;

  // Test both teardown orders: (1) B destroyed before A, and (2) A destroyed
  // before B while both engines are alive concurrently.
  {
    auto jni_a = std::make_shared<MockPlatformViewAndroidJNI>();
    auto holder_a = std::make_unique<AndroidShellHolder>(
        settings, jni_a, AndroidRenderingAPI::kImpellerOpenGLES);
    const pid_t tid_a = holder_a->GetRasterThreadTid();
    EXPECT_GT(tid_a, 0);

    auto jni_b = std::make_shared<MockPlatformViewAndroidJNI>();
    auto holder_b = std::make_unique<AndroidShellHolder>(
        settings, jni_b, AndroidRenderingAPI::kImpellerOpenGLES);
    const pid_t tid_b = holder_b->GetRasterThreadTid();
    EXPECT_GT(tid_b, 0);
    EXPECT_NE(tid_a, tid_b);

    // While both engines are alive simultaneously, each instance reports its
    // own distinct raster thread TID (never overwritten by the newer engine).
    EXPECT_EQ(holder_a->GetRasterThreadTid(), tid_a);
    EXPECT_EQ(holder_b->GetRasterThreadTid(), tid_b);

    // Order B -> A: destroying B leaves A's TID intact.
    holder_b.reset();
    EXPECT_EQ(holder_a->GetRasterThreadTid(), tid_a);
    holder_a.reset();
  }

  {
    auto jni_a = std::make_shared<MockPlatformViewAndroidJNI>();
    auto holder_a = std::make_unique<AndroidShellHolder>(
        settings, jni_a, AndroidRenderingAPI::kImpellerOpenGLES);
    const pid_t tid_a = holder_a->GetRasterThreadTid();
    EXPECT_GT(tid_a, 0);

    auto jni_b = std::make_shared<MockPlatformViewAndroidJNI>();
    auto holder_b = std::make_unique<AndroidShellHolder>(
        settings, jni_b, AndroidRenderingAPI::kImpellerOpenGLES);
    const pid_t tid_b = holder_b->GetRasterThreadTid();
    EXPECT_GT(tid_b, 0);
    EXPECT_NE(tid_a, tid_b);

    // Order A -> B: destroying A leaves B's TID intact.
    holder_a.reset();
    EXPECT_EQ(holder_b->GetRasterThreadTid(), tid_b);
    holder_b.reset();
  }

  // Verify shared ThreadHost lifetime across spawned AndroidShellHolders:
  // 1. Spawned child sharing parent A's ThreadHost reports parent A's raster
  //    thread TID.
  // 2. Destroying parent A first keeps the child's raster thread TID non-zero.
  // 3. The shared atomic TID cell reads 0 only after the last holder is gone.
  {
    auto jni_parent = std::make_shared<MockPlatformViewAndroidJNI>();
    auto parent = std::make_unique<AndroidShellHolder>(
        settings, jni_parent, AndroidRenderingAPI::kImpellerOpenGLES);
    const pid_t parent_raster_tid = parent->GetRasterThreadTid();
    ASSERT_GT(parent_raster_tid, 0);
    auto shared_tid_cell =
        AndroidShellHolderTestAccess::GetRasterTidCell(*parent);
    ASSERT_NE(shared_tid_cell, nullptr);

    auto jni_child = std::make_shared<MockPlatformViewAndroidJNI>();
    auto child = AndroidShellHolderTestAccess::CreateSharedThreadHostHolder(
        *parent, jni_child);
    ASSERT_NE(child, nullptr);
    EXPECT_EQ(child->GetRasterThreadTid(), parent_raster_tid);

    parent.reset();
    EXPECT_EQ(child->GetRasterThreadTid(), parent_raster_tid);
    EXPECT_GT(child->GetRasterThreadTid(), 0);
    EXPECT_EQ(shared_tid_cell->load(std::memory_order_acquire),
              parent_raster_tid);

    child.reset();
    EXPECT_EQ(shared_tid_cell->load(std::memory_order_acquire), 0);
  }
}

TEST(AndroidShellHolder,
     ConcurrentConstructionAndScopedCpuAffinitySaveAndRestore) {
  Settings settings;
  cpu_set_t baseline_mask;
  CPU_ZERO(&baseline_mask);
  ASSERT_EQ(::sched_getaffinity(0, sizeof(cpu_set_t), &baseline_mask), 0);

  // Creating and destroying two independent AndroidShellHolders concurrently
  // must neither race on thread_host identity nor mutate calling-thread CPU
  // affinity.
  pid_t tid_1 = 0;
  pid_t tid_2 = 0;
  std::thread t1([&]() {
    auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
    auto holder = std::make_unique<AndroidShellHolder>(
        settings, jni, AndroidRenderingAPI::kImpellerOpenGLES);
    tid_1 = holder->GetRasterThreadTid();
  });
  std::thread t2([&]() {
    auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
    auto holder = std::make_unique<AndroidShellHolder>(
        settings, jni, AndroidRenderingAPI::kImpellerOpenGLES);
    tid_2 = holder->GetRasterThreadTid();
  });
  t1.join();
  t2.join();
  EXPECT_GT(tid_1, 0);
  EXPECT_GT(tid_2, 0);
  EXPECT_NE(tid_1, tid_2);

  cpu_set_t after_holder_mask;
  CPU_ZERO(&after_holder_mask);
  ASSERT_EQ(::sched_getaffinity(0, sizeof(cpu_set_t), &after_holder_mask), 0);
  EXPECT_TRUE(CPU_EQUAL(&baseline_mask, &after_holder_mask));

  // Verify cross-thread TID-targeted affinity save & restore: thread A runs
  // while thread B calls EnterScopedWorkloadAffinityForThread(tid_a) and then
  // RestoreScopedWorkloadAffinityForThread(tid_a), verifying that whenever
  // heterogeneous non-efficiency placement applies, thread A's mask changes
  // during the workload and is restored byte-for-byte to its exact baseline
  // mask (and on homogeneous CPUs where placement is a no-op, the baseline mask
  // remains untouched throughout).
  std::promise<pid_t> worker_tid_promise;
  auto worker_tid_future = worker_tid_promise.get_future();
  std::promise<void> release_worker_promise;
  auto release_worker_future = release_worker_promise.get_future();

  std::thread worker_thread([&]() {
    worker_tid_promise.set_value(static_cast<pid_t>(gettid()));
    release_worker_future.wait();
  });

  const pid_t worker_tid = worker_tid_future.get();
  ASSERT_GT(worker_tid, 0);
  ASSERT_NE(worker_tid, static_cast<pid_t>(gettid()));

  cpu_set_t worker_baseline;
  CPU_ZERO(&worker_baseline);
  ASSERT_EQ(
      ::sched_getaffinity(worker_tid, sizeof(cpu_set_t), &worker_baseline), 0);

  const bool applied =
      AndroidShellHolder::EnterScopedWorkloadAffinityForThread(worker_tid);

  cpu_set_t worker_during;
  CPU_ZERO(&worker_during);
  ASSERT_EQ(::sched_getaffinity(worker_tid, sizeof(cpu_set_t), &worker_during),
            0);
  if (applied) {
    EXPECT_FALSE(CPU_EQUAL(&worker_baseline, &worker_during));
    EXPECT_TRUE(
        AndroidShellHolder::RestoreScopedWorkloadAffinityForThread(worker_tid));
  } else {
    EXPECT_TRUE(CPU_EQUAL(&worker_baseline, &worker_during));
    EXPECT_FALSE(
        AndroidShellHolder::RestoreScopedWorkloadAffinityForThread(worker_tid));
  }

  cpu_set_t worker_restored;
  CPU_ZERO(&worker_restored);
  ASSERT_EQ(
      ::sched_getaffinity(worker_tid, sizeof(cpu_set_t), &worker_restored), 0);
  EXPECT_TRUE(CPU_EQUAL(&worker_baseline, &worker_restored));

  release_worker_promise.set_value();
  worker_thread.join();
}

}  // namespace testing
}  // namespace flutter
