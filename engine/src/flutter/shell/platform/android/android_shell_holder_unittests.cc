// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

#include "flutter/flow/frame_timings.h"
#include "flutter/fml/logging.h"
#include "flutter/fml/message_loop_task_queues.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/time/time_delta.h"
#include "flutter/fml/time/time_point.h"
#include "flutter/shell/common/rasterizer.h"
#include "flutter/shell/platform/android/android_performance_hint_manager.h"
#include "flutter/shell/platform/android/android_shell_holder.h"
#include "flutter/shell/platform/android/vsync_waiter_android.h"
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

namespace {

// Probes whether `Rasterizer::Setup` has populated `surface_` without invoking
// `Surface::AcquireFrame` (requires viewport metrics on view 0 that exclude
// `DlISize(1, 1)` so `ShouldDiscardLayerTree` discards the task once `surface_`
// is present, returning `kDone`, whereas a null `surface_` returns
// `kNotSetUp`).
DrawStatus ProbeRasterizerSetupStatus(Rasterizer& rasterizer) {
  auto pipeline = std::make_shared<FramePipeline>(1);
  auto recorder = std::make_unique<FrameTimingsRecorder>();
  const fml::TimePoint now = fml::TimePoint::Now();
  recorder->RecordVsync(now, now + fml::TimeDelta::FromSeconds(3600));
  recorder->RecordBuildStart(now);
  recorder->RecordBuildEnd(now);
  std::vector<std::unique_ptr<LayerTreeTask>> tasks;
  tasks.push_back(std::make_unique<LayerTreeTask>(
      /*view_id=*/0,
      std::make_unique<LayerTree>(/*root_layer=*/nullptr, DlISize(1, 1)),
      /*device_pixel_ratio=*/1.0f));
  auto continuation = pipeline->Produce();
  PipelineProduceResult produce_result = continuation.Complete(
      std::make_unique<FrameItem>(std::move(tasks), std::move(recorder)));
  EXPECT_TRUE(produce_result.success);
  return rasterizer.Draw(pipeline);
}

}  // namespace

TEST(AndroidShellHolder, NotifyCreatedAndChangedDoNotBlockPlatformThread) {
  Settings settings;
  settings.enable_software_rendering = false;
#if defined(NDEBUG) || SLIMPELLER
  constexpr AndroidRenderingAPI kRenderingApi =
      AndroidRenderingAPI::kImpellerOpenGLES;
#else
  // In `--unoptimized` (`!NDEBUG`) builds, `AndroidContextGLImpeller` does not
  // create a pbuffer fallback for fake windows, so `CreateGPUSurface` hits a
  // pre-existing `FML_DCHECK(CanReactOnCurrentThread())` in `ReactorGLES`.
  constexpr AndroidRenderingAPI kRenderingApi =
      AndroidRenderingAPI::kSkiaOpenGLES;
  settings.enable_impeller = false;
#endif
  auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
  auto holder =
      std::make_unique<AndroidShellHolder>(settings, jni, kRenderingApi);
  ASSERT_NE(holder, nullptr);
  ASSERT_TRUE(holder->IsValid());
  holder->GetPlatformView()->SetViewportMetrics(
      0, ViewportMetrics{1.0, 100, 100, 22, 0});

  auto raster_runner =
      holder->GetShellForTesting()->GetTaskRunners().GetRasterTaskRunner();
  fml::AutoResetWaitableEvent raster_entered;
  fml::AutoResetWaitableEvent unblock_raster;
  raster_runner->PostTask([&raster_entered, &unblock_raster]() {
    raster_entered.Signal();
    unblock_raster.Wait();
  });
  raster_entered.Wait();

  auto window = fml::MakeRefCounted<AndroidNativeWindow>(
      nullptr, /*is_fake_window=*/true);
  holder->GetPlatformView()->NotifyCreated(window);
  holder->GetPlatformView()->NotifyChanged(DlISize(1080, 1920));

  unblock_raster.Signal();

  DrawStatus status_after_create = DrawStatus::kNotSetUp;
  fml::AutoResetWaitableEvent raster_done;
  raster_runner->PostTask([shell_ptr = holder->GetShellForTesting().get(),
                           &status_after_create, &raster_done]() {
    status_after_create =
        ProbeRasterizerSetupStatus(*shell_ptr->GetRasterizer());
    raster_done.Signal();
  });
  ASSERT_FALSE(raster_done.WaitWithTimeout(fml::TimeDelta::FromSeconds(10)));
  EXPECT_EQ(status_after_create, DrawStatus::kDone);
}

#if !SLIMPELLER
TEST(AndroidShellHolder, SupersededNotifyCreatedAndNotifyChangedAreSkipped) {
  Settings settings;
  settings.enable_software_rendering = false;
  settings.enable_impeller = false;
  auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kSkiaOpenGLES);
  ASSERT_NE(holder, nullptr);
  ASSERT_TRUE(holder->IsValid());

  auto raster_runner =
      holder->GetShellForTesting()->GetTaskRunners().GetRasterTaskRunner();
  fml::AutoResetWaitableEvent raster_entered;
  fml::AutoResetWaitableEvent unblock_raster;
  raster_runner->PostTask([&raster_entered, &unblock_raster]() {
    raster_entered.Signal();
    unblock_raster.Wait();
  });
  raster_entered.Wait();

  // `AndroidContextGLSkia` backs fake windows with a valid pbuffer surface, so
  // the first `NotifyCreated` would populate `GetGrContext()` if it were not
  // superseded before running on the raster thread.
  auto window = fml::MakeRefCounted<AndroidNativeWindow>(
      nullptr, /*is_fake_window=*/true);
  holder->GetPlatformView()->NotifyCreated(window);
  holder->GetPlatformView()->NotifyChanged(DlISize(1080, 1920));

  bool setup_ran_for_superseded_generation = true;
  raster_runner->PostTask([shell_ptr = holder->GetShellForTesting().get(),
                           &setup_ran_for_superseded_generation]() {
    setup_ran_for_superseded_generation =
        shell_ptr->GetRasterizer()->GetGrContext() != nullptr;
  });

  holder->GetPlatformView()->NotifyCreated(window);
  unblock_raster.Signal();

  bool setup_ran_for_active_generation = false;
  fml::AutoResetWaitableEvent raster_done;
  raster_runner->PostTask([shell_ptr = holder->GetShellForTesting().get(),
                           &setup_ran_for_active_generation, &raster_done]() {
    setup_ran_for_active_generation =
        shell_ptr->GetRasterizer()->GetGrContext() != nullptr;
    raster_done.Signal();
  });
  ASSERT_FALSE(raster_done.WaitWithTimeout(fml::TimeDelta::FromSeconds(10)));
  EXPECT_FALSE(setup_ran_for_superseded_generation);
  EXPECT_TRUE(setup_ran_for_active_generation);

  holder->GetPlatformView()->NotifyDestroyed();
}

// With hybrid composition the raster task queue can be merged onto the platform
// thread while surface setup or an earlier resize is still queued. A resize
// requested on the platform thread at that point must queue behind them rather
// than run inline.
TEST(AndroidShellHolder, NotifyChangedStaysBehindQueuedSetupWhenThreadsMerge) {
  // Declared before `holder` so they outlive the raster thread on every exit
  // path.
  fml::AutoResetWaitableEvent raster_entered;
  fml::AutoResetWaitableEvent unblock_raster;
  fml::AutoResetWaitableEvent raster_done;
  bool setup_ran_before_resize = false;

  Settings settings;
  settings.enable_software_rendering = false;
  settings.enable_impeller = false;
  auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kSkiaOpenGLES);
  ASSERT_NE(holder, nullptr);
  ASSERT_TRUE(holder->IsValid());

  const TaskRunners& task_runners =
      holder->GetShellForTesting()->GetTaskRunners();
  auto raster_runner = task_runners.GetRasterTaskRunner();
  const fml::TaskQueueId platform_queue_id =
      task_runners.GetPlatformTaskRunner()->GetTaskQueueId();
  const fml::TaskQueueId raster_queue_id = raster_runner->GetTaskQueueId();
  auto* task_queues = fml::MessageLoopTaskQueues::GetInstance();

  raster_runner->PostTask([&raster_entered, &unblock_raster]() {
    raster_entered.Signal();
    unblock_raster.Wait();
  });
  raster_entered.Wait();

  // Surface setup is now queued behind the blocked raster task.
  auto window = fml::MakeRefCounted<AndroidNativeWindow>(
      nullptr, /*is_fake_window=*/true);
  holder->GetPlatformView()->NotifyCreated(window);

  // While merged, the raster task runner reports that it runs tasks on this
  // thread, so a run-now-or-post resize would execute here, ahead of setup.
  // `AndroidSurfaceGLSkia::OnScreenSurfaceResize` requires the onscreen surface
  // that setup creates, so a resize that runs first crashes the test.
  const bool merged = task_queues->Merge(platform_queue_id, raster_queue_id);
  const bool raster_runs_on_platform =
      raster_runner->RunsTasksOnCurrentThread();
  holder->GetPlatformView()->NotifyChanged(DlISize(1080, 1920));
  const bool unmerged =
      task_queues->Unmerge(platform_queue_id, raster_queue_id);

  raster_runner->PostTask([shell_ptr = holder->GetShellForTesting().get(),
                           &setup_ran_before_resize, &raster_done]() {
    setup_ran_before_resize =
        shell_ptr->GetRasterizer()->GetGrContext() != nullptr;
    raster_done.Signal();
  });
  unblock_raster.Signal();

  EXPECT_TRUE(merged);
  EXPECT_TRUE(raster_runs_on_platform);
  EXPECT_TRUE(unmerged);
  EXPECT_FALSE(raster_done.WaitWithTimeout(fml::TimeDelta::FromSeconds(10)));
  EXPECT_TRUE(setup_ran_before_resize);

  holder->GetPlatformView()->NotifyDestroyed();
}

TEST(AndroidShellHolder,
     NotifyCreatedRunsSetupWhenSetNativeWindowFailsButSurfaceIsValid) {
  Settings settings;
  settings.enable_software_rendering = true;
  settings.enable_impeller = false;
  auto jni = std::make_shared<MockPlatformViewAndroidJNI>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kSoftware);
  ASSERT_NE(holder, nullptr);
  ASSERT_TRUE(holder->IsValid());
  holder->GetPlatformView()->SetViewportMetrics(
      0, ViewportMetrics{1.0, 100, 100, 22, 0});

  auto raster_runner =
      holder->GetShellForTesting()->GetTaskRunners().GetRasterTaskRunner();
  DrawStatus status_before_create = DrawStatus::kDone;
  fml::AutoResetWaitableEvent initial_probe_done;
  raster_runner->PostTask([shell_ptr = holder->GetShellForTesting().get(),
                           &status_before_create, &initial_probe_done]() {
    status_before_create =
        ProbeRasterizerSetupStatus(*shell_ptr->GetRasterizer());
    initial_probe_done.Signal();
  });
  ASSERT_FALSE(
      initial_probe_done.WaitWithTimeout(fml::TimeDelta::FromSeconds(10)));
  EXPECT_EQ(status_before_create, DrawStatus::kNotSetUp);

  // `AndroidSurfaceSoftware::SetNativeWindow` returns false for a fake window,
  // yet `CreateRenderingSurface` returns a valid `GPUSurfaceSoftware` so
  // `Rasterizer::Setup` must still run (preserving attempt-and-validate
  // semantics).
  auto invalid_window = fml::MakeRefCounted<AndroidNativeWindow>(
      nullptr, /*is_fake_window=*/true);
  holder->GetPlatformView()->NotifyCreated(invalid_window);
  holder->GetPlatformView()->NotifyChanged(DlISize(1080, 1920));

  DrawStatus status_after_create = DrawStatus::kNotSetUp;
  fml::AutoResetWaitableEvent raster_done;
  raster_runner->PostTask([shell_ptr = holder->GetShellForTesting().get(),
                           &status_after_create, &raster_done]() {
    status_after_create =
        ProbeRasterizerSetupStatus(*shell_ptr->GetRasterizer());
    raster_done.Signal();
  });
  ASSERT_FALSE(raster_done.WaitWithTimeout(fml::TimeDelta::FromSeconds(10)));
  EXPECT_EQ(status_after_create, DrawStatus::kDone);
}
#endif  // !SLIMPELLER

namespace {

struct FakeAdpfWorkReport {
  int64_t start_ns = 0;
  int64_t total_ns = 0;
  int64_t cpu_ns = 0;
  int64_t gpu_ns = 0;
};

struct FakeAdpfSession {
  std::vector<int32_t> tids;
  int64_t initial_target_ns = 0;
  std::vector<int64_t> updated_targets;
  std::vector<FakeAdpfWorkReport> reports;
  bool closed = false;
  bool all_tids_alive_at_close = false;
};

struct FakeAdpfRegistry {
  FakeAdpfRegistry() {
    FML_CHECK(active == nullptr);
    active = this;
  }
  ~FakeAdpfRegistry() { active = nullptr; }

  AndroidPerformanceHintManager::Api MakeApi() const {
    return {
        .get_manager = &GetManager,
        .create_session = &CreateSession,
        .report_actual_work_duration2 = &ReportActualWorkDuration2,
        .update_target_work_duration = &UpdateTargetWorkDuration,
        .close_session = &CloseSession,
        .create_work_duration = &CreateWorkDuration,
        .release_work_duration = &ReleaseWorkDuration,
        .set_work_period_start = &SetField<&FakeAdpfWorkReport::start_ns>,
        .set_actual_total_duration = &SetField<&FakeAdpfWorkReport::total_ns>,
        .set_actual_cpu_duration = &SetField<&FakeAdpfWorkReport::cpu_ns>,
        .set_actual_gpu_duration = &SetField<&FakeAdpfWorkReport::gpu_ns>,
    };
  }

  AndroidShellHolder::PerformanceHintManagerFactory MakeFactory() const {
    auto api = MakeApi();
    return [api](const std::vector<int32_t>& tids, int64_t target_duration_ns) {
      return AndroidPerformanceHintManager::CreateForTesting(
          tids, target_duration_ns, api);
    };
  }

  std::mutex mutex;
  int manager_token = 0;
  std::vector<std::unique_ptr<FakeAdpfSession>> sessions;
  static FakeAdpfRegistry* active;

  static APerformanceHintManager* GetManager() {
    return reinterpret_cast<APerformanceHintManager*>(&active->manager_token);
  }
  static APerformanceHintSession* CreateSession(APerformanceHintManager* mgr,
                                                const int32_t* tids,
                                                size_t size,
                                                int64_t target_ns) {
    std::lock_guard<std::mutex> lock(active->mutex);
    auto session = std::make_unique<FakeAdpfSession>();
    session->tids.assign(tids, tids + size);
    session->initial_target_ns = target_ns;
    auto* raw = reinterpret_cast<APerformanceHintSession*>(session.get());
    active->sessions.push_back(std::move(session));
    return raw;
  }
  static int ReportActualWorkDuration2(APerformanceHintSession* s,
                                       AWorkDuration* wd) {
    std::lock_guard<std::mutex> lock(active->mutex);
    auto* session = reinterpret_cast<FakeAdpfSession*>(s);
    auto* report = reinterpret_cast<FakeAdpfWorkReport*>(wd);
    session->reports.push_back(*report);
    return 0;
  }
  static int UpdateTargetWorkDuration(APerformanceHintSession* s,
                                      int64_t target_ns) {
    std::lock_guard<std::mutex> lock(active->mutex);
    auto* session = reinterpret_cast<FakeAdpfSession*>(s);
    session->updated_targets.push_back(target_ns);
    return 0;
  }
  static void CloseSession(APerformanceHintSession* s) {
    std::lock_guard<std::mutex> lock(active->mutex);
    auto* session = reinterpret_cast<FakeAdpfSession*>(s);
    session->closed = true;
    bool all_alive = !session->tids.empty();
    const pid_t pid = getpid();
    for (int32_t tid : session->tids) {
      if (syscall(SYS_tgkill, pid, tid, 0) != 0) {
        all_alive = false;
      }
    }
    session->all_tids_alive_at_close = all_alive;
  }
  static AWorkDuration* CreateWorkDuration() {
    return reinterpret_cast<AWorkDuration*>(new FakeAdpfWorkReport());
  }
  static void ReleaseWorkDuration(AWorkDuration* wd) {
    delete reinterpret_cast<FakeAdpfWorkReport*>(wd);
  }
  template <int64_t FakeAdpfWorkReport::* Field>
  static void SetField(AWorkDuration* wd, int64_t value) {
    reinterpret_cast<FakeAdpfWorkReport*>(wd)->*Field = value;
  }
};

FakeAdpfRegistry* FakeAdpfRegistry::active = nullptr;

}  // namespace

TEST(AndroidShellHolder, PerformanceHintsDisabledBySettings) {
  FakeAdpfRegistry registry;
  Settings settings;
  settings.enable_android_performance_hints = false;
  int built_calls = 0;
  int rasterized_calls = 0;
  settings.frame_built_callback =
      [&built_calls](fml::TimePoint, fml::TimePoint) { ++built_calls; };
  settings.frame_rasterized_callback = [&rasterized_calls](const FrameTiming&) {
    ++rasterized_calls;
  };

  auto jni =
      std::make_shared<::testing::NiceMock<MockPlatformViewAndroidJNI>>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kImpellerOpenGLES,
      registry.MakeFactory());
  ASSERT_TRUE(holder->IsValid());
  EXPECT_TRUE(registry.sessions.empty());

  const Settings& shell_settings = holder->GetShellForTesting()->GetSettings();
  const fml::TimePoint t0 =
      fml::TimePoint::FromEpochDelta(fml::TimeDelta::FromMilliseconds(100));
  const fml::TimePoint t1 =
      fml::TimePoint::FromEpochDelta(fml::TimeDelta::FromMilliseconds(104));
  shell_settings.frame_built_callback(t0, t1);
  FrameTiming timing;
  shell_settings.frame_rasterized_callback(timing);
  EXPECT_EQ(built_calls, 1);
  EXPECT_EQ(rasterized_calls, 1);
}

TEST(AndroidShellHolder,
     PerformanceHintsCreateDualSessionsAndUpdateDisplayMetrics) {
  {
    FakeAdpfRegistry registry;
    Settings settings;
    auto jni =
        std::make_shared<::testing::NiceMock<MockPlatformViewAndroidJNI>>();
    ON_CALL(*jni, GetDisplayRefreshRate())
        .WillByDefault(::testing::Return(120.0));

    auto holder = std::make_unique<AndroidShellHolder>(
        settings, jni, AndroidRenderingAPI::kImpellerOpenGLES,
        registry.MakeFactory());
    ASSERT_TRUE(holder->IsValid());
    ASSERT_EQ(registry.sessions.size(), 2u);

    const FakeAdpfSession& ui_session = *registry.sessions[0];
    const FakeAdpfSession& raster_session = *registry.sessions[1];
    EXPECT_EQ(ui_session.tids, std::vector<int32_t>{gettid()});
    ASSERT_EQ(raster_session.tids.size(), 1u);
    EXPECT_NE(raster_session.tids[0], gettid());
    EXPECT_EQ(ui_session.initial_target_ns, 8'333'333);
    EXPECT_EQ(raster_session.initial_target_ns, 8'333'333);

    ON_CALL(*jni, GetDisplayRefreshRate())
        .WillByDefault(::testing::Return(60.0));
    holder->UpdateDisplayMetrics();
    EXPECT_EQ(ui_session.updated_targets, std::vector<int64_t>{16'666'666});
    EXPECT_EQ(raster_session.updated_targets, std::vector<int64_t>{16'666'666});
  }

  {
    FakeAdpfRegistry registry;
    Settings settings;
    settings.merged_platform_ui_thread =
        Settings::MergedPlatformUIThread::kDisabled;
    auto jni =
        std::make_shared<::testing::NiceMock<MockPlatformViewAndroidJNI>>();
    ON_CALL(*jni, GetDisplayRefreshRate()).WillByDefault(::testing::Return(0));

    auto holder = std::make_unique<AndroidShellHolder>(
        settings, jni, AndroidRenderingAPI::kImpellerOpenGLES,
        registry.MakeFactory());
    ASSERT_TRUE(holder->IsValid());
    ASSERT_EQ(registry.sessions.size(), 2u);

    const FakeAdpfSession& ui_session = *registry.sessions[0];
    const FakeAdpfSession& raster_session = *registry.sessions[1];
    ASSERT_EQ(ui_session.tids.size(), 1u);
    ASSERT_EQ(raster_session.tids.size(), 1u);
    EXPECT_NE(ui_session.tids[0], gettid());
    EXPECT_NE(raster_session.tids[0], gettid());
    EXPECT_NE(ui_session.tids[0], raster_session.tids[0]);
    // Falls back to 60 Hz when JNI reports a non-positive refresh rate.
    EXPECT_EQ(ui_session.initial_target_ns, 16'666'666);
    EXPECT_EQ(raster_session.initial_target_ns, 16'666'666);
  }

  {
    FakeAdpfRegistry registry;
    Settings settings;
    settings.merged_platform_ui_thread =
        Settings::MergedPlatformUIThread::kMergeAfterLaunch;
    auto jni =
        std::make_shared<::testing::NiceMock<MockPlatformViewAndroidJNI>>();
    ON_CALL(*jni, GetDisplayRefreshRate())
        .WillByDefault(::testing::Return(120.0));

    auto holder = std::make_unique<AndroidShellHolder>(
        settings, jni, AndroidRenderingAPI::kImpellerOpenGLES,
        registry.MakeFactory());
    ASSERT_TRUE(holder->IsValid());
    ASSERT_EQ(registry.sessions.size(), 2u);

    const FakeAdpfSession& ui_session = *registry.sessions[0];
    const FakeAdpfSession& raster_session = *registry.sessions[1];
    ASSERT_EQ(ui_session.tids.size(), 2u);
    EXPECT_NE(ui_session.tids[0], gettid());
    EXPECT_EQ(ui_session.tids[1], gettid());
    ASSERT_EQ(raster_session.tids.size(), 1u);
    EXPECT_NE(raster_session.tids[0], ui_session.tids[0]);
    EXPECT_NE(raster_session.tids[0], gettid());
  }
}

TEST(AndroidShellHolder,
     PerformanceHintsReportFrameDurationsAndSkipWhenMerged) {
  // Declared before `holder` so they outlive the raster thread even on timeout.
  FrameTiming timing;
  fml::AutoResetWaitableEvent raster_done;
  FakeAdpfRegistry registry;
  Settings settings;
  int prev_built_calls = 0;
  std::atomic<int> prev_rasterized_calls{0};
  settings.frame_built_callback = [&prev_built_calls](fml::TimePoint,
                                                      fml::TimePoint) {
    ++prev_built_calls;
  };
  settings.frame_rasterized_callback =
      [&prev_rasterized_calls](const FrameTiming&) {
        prev_rasterized_calls.fetch_add(1, std::memory_order_relaxed);
      };

  auto jni =
      std::make_shared<::testing::NiceMock<MockPlatformViewAndroidJNI>>();
  ON_CALL(*jni, GetDisplayRefreshRate())
      .WillByDefault(::testing::Return(120.0));
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kImpellerOpenGLES,
      registry.MakeFactory());
  ASSERT_TRUE(holder->IsValid());
  ASSERT_EQ(registry.sessions.size(), 2u);

  const Settings& shell_settings = holder->GetShellForTesting()->GetSettings();
  const TaskRunners& task_runners =
      holder->GetShellForTesting()->GetTaskRunners();
  const fml::TaskQueueId platform_queue_id =
      task_runners.GetPlatformTaskRunner()->GetTaskQueueId();
  const fml::TaskQueueId raster_queue_id =
      task_runners.GetRasterTaskRunner()->GetTaskQueueId();

  // Unmerged UI build reports to the UI session and chains the previous
  // callback.
  const fml::TimePoint build_start =
      fml::TimePoint::FromEpochDelta(fml::TimeDelta::FromMilliseconds(200));
  const fml::TimePoint build_end =
      fml::TimePoint::FromEpochDelta(fml::TimeDelta::FromMilliseconds(203));
  shell_settings.frame_built_callback(build_start, build_end);
  EXPECT_EQ(prev_built_calls, 1);
  ASSERT_EQ(registry.sessions[0]->reports.size(), 1u);
  EXPECT_EQ(registry.sessions[0]->reports[0].start_ns, 200'000'000);
  EXPECT_EQ(registry.sessions[0]->reports[0].total_ns, 3'000'000);
  EXPECT_EQ(registry.sessions[0]->reports[0].cpu_ns, 3'000'000);
  EXPECT_EQ(registry.sessions[0]->reports[0].gpu_ns, 0);

  // When RasterThreadMerger merges raster onto the platform/UI thread (legacy
  // HC), skip UI ADPF reporting while still chaining the previous callback.
  auto* task_queues = fml::MessageLoopTaskQueues::GetInstance();
  ASSERT_TRUE(task_queues->Merge(platform_queue_id, raster_queue_id));
  shell_settings.frame_built_callback(build_start, build_end);
  ASSERT_TRUE(task_queues->Unmerge(platform_queue_id, raster_queue_id));
  EXPECT_EQ(prev_built_calls, 2);
  EXPECT_EQ(registry.sessions[0]->reports.size(), 1u);

  // Raster callback on the raster thread reports to the raster session.
  timing.Set(
      FrameTiming::kRasterStart,
      fml::TimePoint::FromEpochDelta(fml::TimeDelta::FromMilliseconds(300)));
  timing.Set(
      FrameTiming::kRasterFinish,
      fml::TimePoint::FromEpochDelta(fml::TimeDelta::FromMilliseconds(305)));
  task_runners.GetRasterTaskRunner()->PostTask(
      [callback = shell_settings.frame_rasterized_callback, &timing,
       &raster_done]() {
        callback(timing);
        raster_done.Signal();
      });
  ASSERT_FALSE(raster_done.WaitWithTimeout(fml::TimeDelta::FromSeconds(10)));
  EXPECT_EQ(prev_rasterized_calls.load(std::memory_order_relaxed), 1);
  ASSERT_EQ(registry.sessions[1]->reports.size(), 1u);
  EXPECT_EQ(registry.sessions[1]->reports[0].start_ns, 300'000'000);
  EXPECT_EQ(registry.sessions[1]->reports[0].total_ns, 5'000'000);
  EXPECT_EQ(registry.sessions[1]->reports[0].cpu_ns, 5'000'000);
  EXPECT_EQ(registry.sessions[1]->reports[0].gpu_ns, 0);

  // Raster callback running on the platform thread (legacy HC merged thread)
  // skips ADPF reporting while still chaining the previous callback.
  shell_settings.frame_rasterized_callback(timing);
  EXPECT_EQ(prev_rasterized_calls.load(std::memory_order_relaxed), 2);
  EXPECT_EQ(registry.sessions[1]->reports.size(), 1u);
}

TEST(AndroidShellHolder, PerformanceHintsClosedBeforeThreadHostDestroyed) {
  FakeAdpfRegistry registry;
  Settings settings;
  // Use a dedicated UI thread so both UI and raster sessions register
  // ThreadHost-owned OS threads checked via SYS_tgkill on close.
  settings.merged_platform_ui_thread =
      Settings::MergedPlatformUIThread::kDisabled;
  auto jni =
      std::make_shared<::testing::NiceMock<MockPlatformViewAndroidJNI>>();
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kImpellerOpenGLES,
      registry.MakeFactory());
  ASSERT_TRUE(holder->IsValid());
  ASSERT_EQ(registry.sessions.size(), 2u);
  EXPECT_FALSE(registry.sessions[0]->closed);
  EXPECT_FALSE(registry.sessions[1]->closed);

  holder.reset();

  EXPECT_TRUE(registry.sessions[0]->closed);
  EXPECT_TRUE(registry.sessions[1]->closed);
  EXPECT_TRUE(registry.sessions[0]->all_tids_alive_at_close);
  EXPECT_TRUE(registry.sessions[1]->all_tids_alive_at_close);
}

TEST(AndroidShellHolder, VsyncWaiterAndroidNotifiesPerformanceHintManagers) {
  struct ScopedRefreshRateReset {
    ScopedRefreshRateReset() {
      VsyncWaiterAndroid::SetRefreshRateForTesting(0.0f);
    }
    ~ScopedRefreshRateReset() {
      VsyncWaiterAndroid::SetRefreshRateForTesting(0.0f);
    }
  } refresh_rate_guard;

  FakeAdpfRegistry registry;
  Settings settings;
  auto jni =
      std::make_shared<::testing::NiceMock<MockPlatformViewAndroidJNI>>();
  // Use 120 Hz initially so reading the uninitialized 60 Hz fallback would
  // visibly trigger a target update if g_refresh_rate_updated_ regressed.
  ON_CALL(*jni, GetDisplayRefreshRate())
      .WillByDefault(::testing::Return(120.0));
  auto holder = std::make_unique<AndroidShellHolder>(
      settings, jni, AndroidRenderingAPI::kImpellerOpenGLES,
      registry.MakeFactory());
  ASSERT_TRUE(holder->IsValid());
  ASSERT_EQ(registry.sessions.size(), 2u);

  PlatformView* platform_view = holder->GetPlatformView().get();
  ASSERT_NE(platform_view, nullptr);
  std::unique_ptr<VsyncWaiter> waiter = platform_view->CreateVSyncWaiter();
  ASSERT_NE(waiter, nullptr);
  auto* android_waiter = static_cast<VsyncWaiterAndroid*>(waiter.get());

  // Before JNI reports a refresh rate, AwaitVSync must not overwrite the
  // initial 120 Hz target duration with the uninitialized 60 Hz fallback.
  android_waiter->NotifyPerformanceHintManagersForTesting();
  EXPECT_TRUE(registry.sessions[0]->updated_targets.empty());
  EXPECT_TRUE(registry.sessions[1]->updated_targets.empty());

  // Once JNI updates the refresh rate, AwaitVSync updates both sessions'
  // target durations.
  VsyncWaiterAndroid::SetRefreshRateForTesting(60.0f);
  android_waiter->NotifyPerformanceHintManagersForTesting();

  EXPECT_EQ(registry.sessions[0]->updated_targets,
            std::vector<int64_t>{16'666'666});
  EXPECT_EQ(registry.sessions[1]->updated_targets,
            std::vector<int64_t>{16'666'666});

  // Shift both sessions back to 120 Hz via UpdateDisplayMetrics so a repeat
  // call at 60 Hz would bypass the 5% hysteresis deadband if VsyncWaiterAndroid
  // did not cache last_notified_refresh_rate_.
  holder->UpdateDisplayMetrics();
  const std::vector<int64_t> expected_after_metrics = {16'666'666, 8'333'333};
  ASSERT_EQ(registry.sessions[0]->updated_targets, expected_after_metrics);
  ASSERT_EQ(registry.sessions[1]->updated_targets, expected_after_metrics);

  // Calling again at the same 60 Hz refresh rate must not touch either manager.
  android_waiter->NotifyPerformanceHintManagersForTesting();
  EXPECT_EQ(registry.sessions[0]->updated_targets, expected_after_metrics);
  EXPECT_EQ(registry.sessions[1]->updated_targets, expected_after_metrics);

  // Changing to a new refresh rate (90 Hz) notifies both sessions again.
  VsyncWaiterAndroid::SetRefreshRateForTesting(90.0f);
  android_waiter->NotifyPerformanceHintManagersForTesting();
  const std::vector<int64_t> expected_after_90hz = {16'666'666, 8'333'333,
                                                    11'111'111};
  EXPECT_EQ(registry.sessions[0]->updated_targets, expected_after_90hz);
  EXPECT_EQ(registry.sessions[1]->updated_targets, expected_after_90hz);
}

}  // namespace testing
}  // namespace flutter
