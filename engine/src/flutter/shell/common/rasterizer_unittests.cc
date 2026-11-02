// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#define FML_USED_ON_EMBEDDER

#include "flow/surface_frame.h"
#include "flutter/shell/common/rasterizer.h"

#include <memory>
#include <optional>

#include "flutter/flow/frame_timings.h"
#include "flutter/flow/layers/container_layer.h"
#include "flutter/flow/layers/texture_layer.h"
#include "flutter/fml/synchronization/count_down_latch.h"
#include "flutter/fml/time/time_point.h"
#include "flutter/shell/common/thread_host.h"
#include "flutter/testing/testing.h"

#include "third_party/skia/include/core/SkColorSpace.h"
#include "third_party/skia/include/core/SkSurface.h"
#include "third_party/skia/include/gpu/ganesh/GrTypes.h"
#include "third_party/skia/include/gpu/ganesh/SkSurfaceGanesh.h"

#include "gmock/gmock.h"
#if IMPELLER_SUPPORTS_RENDERING
#include "impeller/display_list/aiks_context.h"  // nogncheck
#endif                                           // IMPELLER_SUPPORTS_RENDERING

using testing::_;
using testing::ByMove;
using testing::NiceMock;
using testing::Return;
using testing::ReturnRef;

namespace flutter {
namespace {

constexpr float kDevicePixelRatio = 2.0f;
constexpr int64_t kImplicitViewId = 0;

std::vector<std::unique_ptr<LayerTreeTask>> SingleLayerTreeList(
    int64_t view_id,
    std::unique_ptr<LayerTree> layer_tree,
    float pixel_ratio) {
  std::vector<std::unique_ptr<LayerTreeTask>> tasks;
  tasks.push_back(std::make_unique<LayerTreeTask>(
      view_id, std::move(layer_tree), pixel_ratio));
  return tasks;
}

class MockDelegate : public Rasterizer::Delegate {
 public:
  MOCK_METHOD(void,
              OnFrameRasterized,
              (const FrameTiming& frame_timing),
              (override));
  MOCK_METHOD(fml::Milliseconds, GetFrameBudget, (), (override));
  MOCK_METHOD(fml::TimePoint, GetLatestFrameTargetTime, (), (const, override));
  MOCK_METHOD(const TaskRunners&, GetTaskRunners, (), (const, override));
  MOCK_METHOD(const fml::RefPtr<fml::RasterThreadMerger>,
              GetParentRasterThreadMerger,
              (),
              (const, override));
  MOCK_METHOD(std::shared_ptr<const fml::SyncSwitch>,
              GetIsGpuDisabledSyncSwitch,
              (),
              (const, override));
  MOCK_METHOD(const Settings&, GetSettings, (), (const, override));
  MOCK_METHOD(bool,
              ShouldDiscardLayerTree,
              (int64_t, const flutter::LayerTree&),
              (override));
};

class MockSurface : public Surface {
 public:
  MOCK_METHOD(bool, IsValid, (), (override));
  MOCK_METHOD(std::unique_ptr<SurfaceFrame>,
              AcquireFrame,
              (const DlISize& size),
              (override));
  MOCK_METHOD(DlMatrix, GetRootTransformation, (), (const, override));
  MOCK_METHOD(GrDirectContext*, GetContext, (), (override));
  MOCK_METHOD(std::unique_ptr<GLContextResult>,
              MakeRenderContextCurrent,
              (),
              (override));
  MOCK_METHOD(bool, ClearRenderContext, (), (override));
  MOCK_METHOD(bool, AllowsDrawingWhenGpuDisabled, (), (const, override));
  MOCK_METHOD(std::shared_ptr<impeller::AiksContext>,
              GetAiksContext,
              (),
              (const, override));
};

class MockExternalViewEmbedder : public ExternalViewEmbedder {
 public:
  MOCK_METHOD(DlCanvas*, GetRootCanvas, (), (override));
  MOCK_METHOD(void, CancelFrame, (), (override));
  MOCK_METHOD(
      void,
      BeginFrame,
      (GrDirectContext * context,
       const fml::RefPtr<fml::RasterThreadMerger>& raster_thread_merger),
      (override));
  MOCK_METHOD(void,
              PrepareFlutterView,
              (DlISize frame_size, double device_pixel_ratio),
              (override));
  MOCK_METHOD(void,
              PrerollCompositeEmbeddedView,
              (int64_t view_id, std::unique_ptr<EmbeddedViewParams> params),
              (override));
  MOCK_METHOD(
      PostPrerollResult,
      PostPrerollAction,
      (const fml::RefPtr<fml::RasterThreadMerger>& raster_thread_merger),
      (override));
  MOCK_METHOD(DlCanvas*, CompositeEmbeddedView, (int64_t view_id), (override));
  MOCK_METHOD(void,
              SubmitFlutterView,
              (int64_t flutter_view_id,
               GrDirectContext* context,
               const std::shared_ptr<impeller::AiksContext>& aiks_context,
               std::unique_ptr<SurfaceFrame> frame),
              (override));
  MOCK_METHOD(
      void,
      EndFrame,
      (bool should_resubmit_frame,
       const fml::RefPtr<fml::RasterThreadMerger>& raster_thread_merger),
      (override));
  MOCK_METHOD(bool, SupportsDynamicThreadMerging, (), (override));
};

#if IMPELLER_SUPPORTS_RENDERING
class MockAiksContext : public impeller::AiksContext {
 public:
  MockAiksContext() : impeller::AiksContext(nullptr, nullptr) {}
  MOCK_METHOD(void, ClearRenderTargetCache, (), (const, override));
  MOCK_METHOD(void, ClearCachedTextures, (), (const, override));
};
#endif  // IMPELLER_SUPPORTS_RENDERING
}  // namespace

TEST(RasterizerTest, create) {
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  EXPECT_TRUE(rasterizer != nullptr);
}

TEST(RasterizerTest, isAiksContextInitialized) {
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  auto rasterizer = std::make_shared<Rasterizer>(delegate);

  EXPECT_TRUE(rasterizer != nullptr);
  std::shared_ptr<SnapshotController::Delegate> snapshot_delegate = rasterizer;

  EXPECT_FALSE(snapshot_delegate->IsAiksContextInitialized());
}

#if IMPELLER_SUPPORTS_RENDERING
TEST(RasterizerTest, NotifyLowMemoryWarningClearsImpellerCache) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  ON_CALL(delegate, GetTaskRunners()).WillByDefault(ReturnRef(task_runners));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();

  auto aiks_context = std::make_shared<NiceMock<MockAiksContext>>();
  EXPECT_CALL(*surface, GetAiksContext()).WillRepeatedly(Return(aiks_context));
  EXPECT_CALL(*surface, MakeRenderContextCurrent()).WillRepeatedly([] {
    return std::make_unique<GLContextDefaultResult>(true);
  });
  EXPECT_CALL(*aiks_context, ClearRenderTargetCache()).Times(1);
  EXPECT_CALL(*aiks_context, ClearCachedTextures()).Times(1);

  rasterizer->Setup(std::move(surface));
  rasterizer->NotifyLowMemoryWarning();
}

TEST(RasterizerTest, ClearRenderTargetCacheClearsImpellerCache) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  ON_CALL(delegate, GetTaskRunners()).WillByDefault(ReturnRef(task_runners));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();

  auto aiks_context = std::make_shared<NiceMock<MockAiksContext>>();
  EXPECT_CALL(*surface, GetAiksContext()).WillRepeatedly(Return(aiks_context));
  EXPECT_CALL(*surface, MakeRenderContextCurrent()).WillRepeatedly([] {
    return std::make_unique<GLContextDefaultResult>(true);
  });
  EXPECT_CALL(*aiks_context, ClearRenderTargetCache()).Times(1);
  EXPECT_CALL(*aiks_context, ClearCachedTextures()).Times(1);

  rasterizer->Setup(std::move(surface));
  rasterizer->ClearRenderTargetCache();
}
#endif  // IMPELLER_SUPPORTS_RENDERING

static std::unique_ptr<FrameTimingsRecorder> CreateFinishedBuildRecorder(
    fml::TimePoint timestamp) {
  std::unique_ptr<FrameTimingsRecorder> recorder =
      std::make_unique<FrameTimingsRecorder>();
  recorder->RecordVsync(timestamp, timestamp);
  recorder->RecordBuildStart(timestamp);
  recorder->RecordBuildEnd(timestamp);
  return recorder;
}

static std::unique_ptr<FrameTimingsRecorder> CreateFinishedBuildRecorder() {
  return CreateFinishedBuildRecorder(fml::TimePoint::Now());
}

TEST(RasterizerTest, drawEmptyPipeline) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  ON_CALL(delegate, GetTaskRunners()).WillByDefault(ReturnRef(task_runners));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));
  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
    latch.Signal();
  });
  latch.Wait();
}

TEST(RasterizerTest,
     drawWithExternalViewEmbedderExternalViewEmbedderSubmitFrameCalled) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();

  std::shared_ptr<NiceMock<MockExternalViewEmbedder>> external_view_embedder =
      std::make_shared<NiceMock<MockExternalViewEmbedder>>();
  rasterizer->SetExternalViewEmbedder(external_view_embedder);

  SurfaceFrame::FramebufferInfo framebuffer_info;
  framebuffer_info.supports_readback = true;

  auto surface_frame = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillOnce(Return(true));
  EXPECT_CALL(*surface, AcquireFrame(DlISize()))
      .WillOnce(Return(ByMove(std::move(surface_frame))));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  EXPECT_CALL(*external_view_embedder,
              BeginFrame(/*context=*/nullptr,
                         /*raster_thread_merger=*/
                         fml::RefPtr<fml::RasterThreadMerger>(nullptr)))
      .Times(1);
  EXPECT_CALL(*external_view_embedder, PrepareFlutterView(
                                           /*frame_size=*/DlISize(),
                                           /*device_pixel_ratio=*/2.0))
      .Times(1);
  EXPECT_CALL(*external_view_embedder,
              SubmitFlutterView(/*flutter_view_id=*/kImplicitViewId, _, _, _))
      .Times(1);
  EXPECT_CALL(
      *external_view_embedder,
      EndFrame(/*should_resubmit_frame=*/false,
               /*raster_thread_merger=*/fml::RefPtr<fml::RasterThreadMerger>(
                   nullptr)))
      .Times(1);

  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(/*root_layer=*/nullptr,
                                                  /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
    latch.Signal();
  });
  latch.Wait();
}

TEST(
    RasterizerTest,
    drawWithExternalViewEmbedderAndThreadMergerNotMergedExternalViewEmbedderSubmitFrameNotCalled) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  std::shared_ptr<NiceMock<MockExternalViewEmbedder>> external_view_embedder =
      std::make_shared<NiceMock<MockExternalViewEmbedder>>();
  rasterizer->SetExternalViewEmbedder(external_view_embedder);
  EXPECT_CALL(*external_view_embedder, SupportsDynamicThreadMerging)
      .WillRepeatedly(Return(true));
  SurfaceFrame::FramebufferInfo framebuffer_info;
  framebuffer_info.supports_readback = true;
  auto surface_frame = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillOnce(Return(true));
  EXPECT_CALL(*surface, AcquireFrame(DlISize()))
      .WillOnce(Return(ByMove(std::move(surface_frame))));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  EXPECT_CALL(*external_view_embedder, BeginFrame(/*context=*/nullptr,
                                                  /*raster_thread_merger=*/_))
      .Times(1);
  EXPECT_CALL(*external_view_embedder, PrepareFlutterView(
                                           /*frame_size=*/DlISize(),
                                           /*device_pixel_ratio=*/2.0))
      .Times(1);
  EXPECT_CALL(*external_view_embedder,
              SubmitFlutterView(/*flutter_view_id=*/kImplicitViewId, _, _, _))
      .Times(0);
  EXPECT_CALL(*external_view_embedder, EndFrame(/*should_resubmit_frame=*/false,
                                                /*raster_thread_merger=*/_))
      .Times(1);

  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
    latch.Signal();
  });
  latch.Wait();
}

TEST(
    RasterizerTest,
    drawWithExternalViewEmbedderAndThreadsMergedExternalViewEmbedderSubmitFrameCalled) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  fml::MessageLoop::EnsureInitializedForCurrentThread();
  TaskRunners task_runners("test",
                           fml::MessageLoop::GetCurrent().GetTaskRunner(),
                           fml::MessageLoop::GetCurrent().GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());

  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();

  std::shared_ptr<NiceMock<MockExternalViewEmbedder>> external_view_embedder =
      std::make_shared<NiceMock<MockExternalViewEmbedder>>();
  rasterizer->SetExternalViewEmbedder(external_view_embedder);

  SurfaceFrame::FramebufferInfo framebuffer_info;
  framebuffer_info.supports_readback = true;

  auto surface_frame = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillOnce(Return(true));
  EXPECT_CALL(*surface, AcquireFrame(DlISize()))
      .WillOnce(Return(ByMove(std::move(surface_frame))));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));
  EXPECT_CALL(*external_view_embedder, SupportsDynamicThreadMerging)
      .WillRepeatedly(Return(true));

  EXPECT_CALL(*external_view_embedder, BeginFrame(/*context=*/nullptr,
                                                  /*raster_thread_merger=*/_))
      .Times(1);
  EXPECT_CALL(*external_view_embedder, PrepareFlutterView(
                                           /*frame_size=*/DlISize(),
                                           /*device_pixel_ratio=*/2.0))
      .Times(1);
  EXPECT_CALL(*external_view_embedder,
              SubmitFlutterView(/*flutter_view_id=*/kImplicitViewId, _, _, _))
      .Times(1);
  EXPECT_CALL(*external_view_embedder, EndFrame(/*should_resubmit_frame=*/false,
                                                /*raster_thread_merger=*/_))
      .Times(1);

  rasterizer->Setup(std::move(surface));

  auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
  auto layer_tree = std::make_unique<LayerTree>(/*root_layer=*/nullptr,
                                                /*frame_size=*/DlISize());
  auto layer_tree_item = std::make_unique<FrameItem>(
      SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                          kDevicePixelRatio),
      CreateFinishedBuildRecorder());
  PipelineProduceResult result =
      pipeline->Produce().Complete(std::move(layer_tree_item));
  EXPECT_TRUE(result.success);
  ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
  rasterizer->Draw(pipeline);
}

TEST(RasterizerTest,
     drawLastLayerTreeWithThreadsMergedExternalViewEmbedderAndEndFrameCalled) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  fml::MessageLoop::EnsureInitializedForCurrentThread();
  TaskRunners task_runners("test",
                           fml::MessageLoop::GetCurrent().GetTaskRunner(),
                           fml::MessageLoop::GetCurrent().GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());

  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();

  std::shared_ptr<NiceMock<MockExternalViewEmbedder>> external_view_embedder =
      std::make_shared<NiceMock<MockExternalViewEmbedder>>();
  rasterizer->SetExternalViewEmbedder(external_view_embedder);

  SurfaceFrame::FramebufferInfo framebuffer_info;
  framebuffer_info.supports_readback = true;

  auto surface_frame1 = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  auto surface_frame2 = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  // Prepare two frames for Draw() and DrawLastLayerTrees().
  EXPECT_CALL(*surface, AcquireFrame(DlISize()))
      .WillOnce(Return(ByMove(std::move(surface_frame1))))
      .WillOnce(Return(ByMove(std::move(surface_frame2))));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));
  EXPECT_CALL(*external_view_embedder, SupportsDynamicThreadMerging)
      .WillRepeatedly(Return(true));

  EXPECT_CALL(*external_view_embedder, BeginFrame(/*context=*/nullptr,
                                                  /*raster_thread_merger=*/_))
      .Times(2);
  EXPECT_CALL(*external_view_embedder, PrepareFlutterView(
                                           /*frame_size=*/DlISize(),
                                           /*device_pixel_ratio=*/2.0))
      .Times(2);
  EXPECT_CALL(*external_view_embedder,
              SubmitFlutterView(/*flutter_view_id=*/kImplicitViewId, _, _, _))
      .Times(2);
  EXPECT_CALL(*external_view_embedder, EndFrame(/*should_resubmit_frame=*/false,
                                                /*raster_thread_merger=*/_))
      .Times(2);

  rasterizer->Setup(std::move(surface));

  auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
  auto layer_tree = std::make_unique<LayerTree>(/*root_layer=*/nullptr,
                                                /*frame_size=*/DlISize());
  auto layer_tree_item = std::make_unique<FrameItem>(
      SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                          kDevicePixelRatio),
      CreateFinishedBuildRecorder());
  PipelineProduceResult result =
      pipeline->Produce().Complete(std::move(layer_tree_item));
  EXPECT_TRUE(result.success);

  // The Draw() will respectively call BeginFrame(), SubmitFlutterView() and
  // EndFrame() one time.
  ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
  rasterizer->Draw(pipeline);

  // The DrawLastLayerTrees() will respectively call BeginFrame(),
  // SubmitFlutterView() and EndFrame() one more time, totally 2 times.
  rasterizer->DrawLastLayerTrees(CreateFinishedBuildRecorder());
}

TEST(RasterizerTest, externalViewEmbedderDoesntEndFrameWhenNoSurfaceIsSet) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);

  std::shared_ptr<NiceMock<MockExternalViewEmbedder>> external_view_embedder =
      std::make_shared<NiceMock<MockExternalViewEmbedder>>();
  rasterizer->SetExternalViewEmbedder(external_view_embedder);

  EXPECT_CALL(
      *external_view_embedder,
      EndFrame(/*should_resubmit_frame=*/false,
               /*raster_thread_merger=*/fml::RefPtr<fml::RasterThreadMerger>(
                   nullptr)))
      .Times(0);

  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
    latch.Signal();
  });
  latch.Wait();
}

TEST(RasterizerTest, externalViewEmbedderDoesntEndFrameWhenNotUsedThisFrame) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  auto is_gpu_disabled_sync_switch =
      std::make_shared<const fml::SyncSwitch>(false);
  ON_CALL(delegate, GetIsGpuDisabledSyncSwitch())
      .WillByDefault(Return(is_gpu_disabled_sync_switch));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  std::shared_ptr<NiceMock<MockExternalViewEmbedder>> external_view_embedder =
      std::make_shared<NiceMock<MockExternalViewEmbedder>>();
  rasterizer->SetExternalViewEmbedder(external_view_embedder);
  rasterizer->Setup(std::move(surface));

  EXPECT_CALL(*external_view_embedder, BeginFrame(/*context=*/nullptr,
                                                  /*raster_thread_merger=*/_))
      .Times(0);
  EXPECT_CALL(*external_view_embedder, PrepareFlutterView(
                                           /*frame_size=*/DlISize(),
                                           /*device_pixel_ratio=*/2.0))
      .Times(0);
  EXPECT_CALL(
      *external_view_embedder,
      EndFrame(/*should_resubmit_frame=*/false,
               /*raster_thread_merger=*/fml::RefPtr<fml::RasterThreadMerger>(
                   nullptr)))
      .Times(0);

  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    // Always discard the layer tree.
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(true));
    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kDone);
    EXPECT_EQ(rasterizer->GetLastDrawStatus(kImplicitViewId),
              DrawSurfaceStatus::kDiscarded);
    latch.Signal();
  });
  latch.Wait();
}

TEST(RasterizerTest, externalViewEmbedderDoesntEndFrameWhenPipelineIsEmpty) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  std::shared_ptr<NiceMock<MockExternalViewEmbedder>> external_view_embedder =
      std::make_shared<NiceMock<MockExternalViewEmbedder>>();
  rasterizer->SetExternalViewEmbedder(external_view_embedder);
  rasterizer->Setup(std::move(surface));

  EXPECT_CALL(
      *external_view_embedder,
      EndFrame(/*should_resubmit_frame=*/false,
               /*raster_thread_merger=*/fml::RefPtr<fml::RasterThreadMerger>(
                   nullptr)))
      .Times(0);

  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kPipelineEmpty);
    latch.Signal();
  });
  latch.Wait();
}

TEST(RasterizerTest, drawMultipleViewsWithExternalViewEmbedder) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  std::shared_ptr<NiceMock<MockExternalViewEmbedder>> external_view_embedder =
      std::make_shared<NiceMock<MockExternalViewEmbedder>>();
  rasterizer->SetExternalViewEmbedder(external_view_embedder);
  EXPECT_CALL(*external_view_embedder, SupportsDynamicThreadMerging)
      .WillRepeatedly(Return(false));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillOnce(Return(true));
  EXPECT_CALL(*surface, AcquireFrame(DlISize())).Times(2);
  ON_CALL(*surface, AcquireFrame).WillByDefault([](const DlISize& size) {
    SurfaceFrame::FramebufferInfo framebuffer_info;
    framebuffer_info.supports_readback = true;
    return std::make_unique<SurfaceFrame>(
        /*surface=*/
        nullptr, framebuffer_info,
        /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
        /*submit_callback=*/[](const SurfaceFrame&) { return true; },
        /*frame_size=*/DlISize(800, 600));
  });
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  EXPECT_CALL(*external_view_embedder, BeginFrame(/*context=*/nullptr,
                                                  /*raster_thread_merger=*/_))
      .Times(1);
  EXPECT_CALL(*external_view_embedder,
              PrepareFlutterView(/*frame_size=*/DlISize(),
                                 /*device_pixel_ratio=*/1.5))
      .Times(1);
  EXPECT_CALL(*external_view_embedder,
              SubmitFlutterView(/*flutter_view_id=*/0, _, _, _))
      .Times(1);
  EXPECT_CALL(*external_view_embedder,
              PrepareFlutterView(/*frame_size=*/DlISize(),
                                 /*device_pixel_ratio=*/2.0))
      .Times(1);
  EXPECT_CALL(*external_view_embedder,
              SubmitFlutterView(/*flutter_view_id=*/1, _, _, _))
      .Times(1);
  EXPECT_CALL(*external_view_embedder, EndFrame(/*should_resubmit_frame=*/false,
                                                /*raster_thread_merger=*/_))
      .Times(1);

  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    std::vector<std::unique_ptr<LayerTreeTask>> tasks;
    tasks.push_back(std::make_unique<LayerTreeTask>(
        0, std::make_unique<LayerTree>(nullptr, DlISize()), 1.5));
    tasks.push_back(std::make_unique<LayerTreeTask>(
        1, std::make_unique<LayerTree>(nullptr, DlISize()), 2.0));
    auto layer_tree_item = std::make_unique<FrameItem>(
        std::move(tasks), CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
    latch.Signal();
  });
  latch.Wait();
}

TEST(RasterizerTest,
     drawWithGpuEnabledAndSurfaceAllowsDrawingWhenGpuDisabledDoesAcquireFrame) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  auto is_gpu_disabled_sync_switch =
      std::make_shared<const fml::SyncSwitch>(false);

  SurfaceFrame::FramebufferInfo framebuffer_info;
  framebuffer_info.supports_readback = true;
  auto surface_frame = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, /*framebuffer_info=*/framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillOnce(Return(true));
  ON_CALL(delegate, GetIsGpuDisabledSyncSwitch())
      .WillByDefault(Return(is_gpu_disabled_sync_switch));
  EXPECT_CALL(delegate, GetIsGpuDisabledSyncSwitch()).Times(0);
  EXPECT_CALL(*surface, AcquireFrame(DlISize()))
      .WillOnce(Return(ByMove(std::move(surface_frame))));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
    latch.Signal();
  });
  latch.Wait();
}

TEST(
    RasterizerTest,
    drawWithGpuDisabledAndSurfaceAllowsDrawingWhenGpuDisabledDoesAcquireFrame) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  auto is_gpu_disabled_sync_switch =
      std::make_shared<const fml::SyncSwitch>(true);

  SurfaceFrame::FramebufferInfo framebuffer_info;
  framebuffer_info.supports_readback = true;

  auto surface_frame = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, /*framebuffer_info=*/framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillOnce(Return(true));
  ON_CALL(delegate, GetIsGpuDisabledSyncSwitch())
      .WillByDefault(Return(is_gpu_disabled_sync_switch));
  EXPECT_CALL(delegate, GetIsGpuDisabledSyncSwitch()).Times(0);
  EXPECT_CALL(*surface, AcquireFrame(DlISize()))
      .WillOnce(Return(ByMove(std::move(surface_frame))));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kDone);
    latch.Signal();
  });
  latch.Wait();
}

TEST(
    RasterizerTest,
    drawWithGpuEnabledAndSurfaceDisallowsDrawingWhenGpuDisabledDoesAcquireFrame) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  auto is_gpu_disabled_sync_switch =
      std::make_shared<const fml::SyncSwitch>(false);

  SurfaceFrame::FramebufferInfo framebuffer_info;
  framebuffer_info.supports_readback = true;

  auto surface_frame = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, /*framebuffer_info=*/framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillOnce(Return(false));
  EXPECT_CALL(delegate, GetIsGpuDisabledSyncSwitch())
      .WillOnce(Return(is_gpu_disabled_sync_switch));
  EXPECT_CALL(*surface, AcquireFrame(DlISize()))
      .WillOnce(Return(ByMove(std::move(surface_frame))));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kDone);
    latch.Signal();
  });
  latch.Wait();
}

TEST(
    RasterizerTest,
    drawWithGpuDisabledAndSurfaceDisallowsDrawingWhenGpuDisabledDoesntAcquireFrame) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_)).Times(0);
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  auto is_gpu_disabled_sync_switch =
      std::make_shared<const fml::SyncSwitch>(true);

  SurfaceFrame::FramebufferInfo framebuffer_info;
  framebuffer_info.supports_readback = true;

  auto surface_frame = std::make_unique<SurfaceFrame>(
      /*surface=*/
      nullptr, /*framebuffer_info=*/framebuffer_info,
      /*encode_callback=*/[](const SurfaceFrame&, DlCanvas*) { return true; },
      /*submit_callback=*/[](const SurfaceFrame&) { return true; },
      /*frame_size=*/DlISize(800, 600));
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillOnce(Return(false));
  EXPECT_CALL(delegate, GetIsGpuDisabledSyncSwitch())
      .WillOnce(Return(is_gpu_disabled_sync_switch));
  EXPECT_CALL(*surface, AcquireFrame(DlISize())).Times(0);
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kGpuUnavailable);
    latch.Signal();
  });
  latch.Wait();
}

TEST(
    RasterizerTest,
    FrameTimingRecorderShouldStartRecordingRasterTimeBeforeSurfaceAcquireFrame) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_))
      .WillOnce([&](const FrameTiming& frame_timing) {
        fml::TimePoint now = fml::TimePoint::Now();
        fml::TimePoint raster_start =
            frame_timing.Get(FrameTiming::kRasterStart);
        EXPECT_TRUE(now - raster_start < fml::TimeDelta::FromSecondsF(1));
      });

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  auto is_gpu_disabled_sync_switch =
      std::make_shared<const fml::SyncSwitch>(false);
  ON_CALL(delegate, GetIsGpuDisabledSyncSwitch())
      .WillByDefault(Return(is_gpu_disabled_sync_switch));
  ON_CALL(*surface, AcquireFrame(DlISize())).WillByDefault([] {
    return nullptr;
  });
  EXPECT_CALL(*surface, AcquireFrame(DlISize()));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));
  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kDone);
    EXPECT_EQ(rasterizer->GetLastDrawStatus(kImplicitViewId),
              DrawSurfaceStatus::kFailed);
    latch.Signal();
  });
  latch.Wait();
}

TEST(RasterizerTest,
     drawLayerTreeWithCorrectFrameTimingWhenPipelineIsMoreAvailable) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  ON_CALL(delegate, GetTaskRunners()).WillByDefault(ReturnRef(task_runners));

  fml::AutoResetWaitableEvent latch;
  std::unique_ptr<Rasterizer> rasterizer;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer = std::make_unique<Rasterizer>(delegate);
    latch.Signal();
  });
  latch.Wait();

  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  ON_CALL(*surface, AcquireFrame(DlISize())).WillByDefault([] {
    SurfaceFrame::FramebufferInfo framebuffer_info;
    framebuffer_info.supports_readback = true;
    return std::make_unique<SurfaceFrame>(
        /*surface=*/
        nullptr, framebuffer_info,
        /*encode_callback=*/
        [](const SurfaceFrame&, DlCanvas*) { return true; },
        /*submit_callback=*/[](const SurfaceFrame& frame) { return true; },
        /*frame_size=*/DlISize(800, 600));
  });
  ON_CALL(*surface, MakeRenderContextCurrent()).WillByDefault([] {
    return std::make_unique<GLContextDefaultResult>(true);
  });

  fml::CountDownLatch count_down_latch(2);
  auto first_timestamp = fml::TimePoint::Now();
  auto second_timestamp = first_timestamp + fml::TimeDelta::FromMilliseconds(8);
  std::vector<fml::TimePoint> timestamps = {first_timestamp, second_timestamp};
  int frame_rasterized_count = 0;
  EXPECT_CALL(delegate, OnFrameRasterized(_))
      .Times(2)
      .WillRepeatedly([&](const FrameTiming& frame_timing) {
        EXPECT_EQ(timestamps[frame_rasterized_count],
                  frame_timing.Get(FrameTiming::kVsyncStart));
        EXPECT_EQ(timestamps[frame_rasterized_count],
                  frame_timing.Get(FrameTiming::kBuildStart));
        EXPECT_EQ(timestamps[frame_rasterized_count],
                  frame_timing.Get(FrameTiming::kBuildFinish));
        frame_rasterized_count++;
        count_down_latch.CountDown();
      });

  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer->Setup(std::move(surface));
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    for (int i = 0; i < 2; i++) {
      auto layer_tree = std::make_unique<LayerTree>(
          /*root_layer=*/nullptr, /*frame_size=*/DlISize());
      auto layer_tree_item = std::make_unique<FrameItem>(
          SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                              kDevicePixelRatio),
          CreateFinishedBuildRecorder(timestamps[i]));
      PipelineProduceResult result =
          pipeline->Produce().Complete(std::move(layer_tree_item));
      EXPECT_TRUE(result.success);
      EXPECT_EQ(result.is_first_item, i == 0);
    }
    // Although we only call 'Rasterizer::Draw' once, it will be called twice
    // finally because there are two items in the pipeline.
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
  });
  count_down_latch.Wait();
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer.reset();
    latch.Signal();
  });
  latch.Wait();
}

TEST(RasterizerTest, TeardownFreesResourceCache) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());

  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  auto context = GrDirectContext::MakeMock(nullptr);
  context->setResourceCacheLimit(0);

  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillRepeatedly([]() -> std::unique_ptr<GLContextResult> {
        return std::make_unique<GLContextDefaultResult>(true);
      });
  EXPECT_CALL(*surface, GetContext()).WillRepeatedly(Return(context.get()));

  rasterizer->Setup(std::move(surface));
  EXPECT_EQ(context->getResourceCacheLimit(), 0ul);

  rasterizer->SetResourceCacheMaxBytes(10000000, false);
  EXPECT_EQ(context->getResourceCacheLimit(), 10000000ul);
  EXPECT_EQ(context->getResourceCachePurgeableBytes(), 0ul);

  int count = 0;
  size_t bytes = 0;
  context->getResourceCacheUsage(&count, &bytes);
  EXPECT_EQ(bytes, 0ul);

  auto image_info =
      SkImageInfo::MakeN32Premul(500, 500, SkColorSpace::MakeSRGB());
  auto sk_surface = SkSurfaces::RenderTarget(context.get(),
                                             skgpu::Budgeted::kYes, image_info);
  EXPECT_TRUE(sk_surface);

  SkPaint paint;
  sk_surface->getCanvas()->drawPaint(paint);
  context->flushAndSubmit(GrSyncCpu::kYes);

  EXPECT_EQ(context->getResourceCachePurgeableBytes(), 0ul);

  sk_surface.reset();

  context->getResourceCacheUsage(&count, &bytes);
  EXPECT_GT(bytes, 0ul);
  EXPECT_GT(context->getResourceCachePurgeableBytes(), 0ul);

  rasterizer->Teardown();
  EXPECT_EQ(context->getResourceCachePurgeableBytes(), 0ul);
}

TEST(RasterizerTest, TeardownClearsRenderContext) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  ON_CALL(delegate, GetTaskRunners()).WillByDefault(ReturnRef(task_runners));
  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  bool render_context_is_current = false;
  EXPECT_CALL(*surface, MakeRenderContextCurrent()).WillRepeatedly([&]() {
    render_context_is_current = true;
    return std::make_unique<GLContextDefaultResult>(true);
  });
  EXPECT_CALL(*surface, ClearRenderContext()).WillRepeatedly([&]() {
    render_context_is_current = false;
    return true;
  });

  rasterizer->Setup(std::move(surface));
  EXPECT_TRUE(render_context_is_current);

  rasterizer->Teardown();
  EXPECT_FALSE(render_context_is_current);
}

TEST(RasterizerTest, TeardownNoSurface) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());

  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);

  EXPECT_TRUE(rasterizer);
  rasterizer->Teardown();
}

TEST(RasterizerTest, presentationTimeSetWhenVsyncTargetInFuture) {
  GTEST_SKIP() << "eglPresentationTime is disabled due to "
                  "https://github.com/flutter/flutter/issues/112503";
#if false
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform | ThreadHost::Type::kRaster |
                             ThreadHost::Type::kIo | ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());

  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  ON_CALL(delegate, GetTaskRunners()).WillByDefault(ReturnRef(task_runners));

  fml::AutoResetWaitableEvent latch;
  std::unique_ptr<Rasterizer> rasterizer;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer = std::make_unique<Rasterizer>(delegate);
    latch.Signal();
  });
  latch.Wait();

  const auto millis_16 = fml::TimeDelta::FromMilliseconds(16);
  const auto first_timestamp = fml::TimePoint::Now() + millis_16;
  auto second_timestamp = first_timestamp + millis_16;
  std::vector<fml::TimePoint> timestamps = {first_timestamp, second_timestamp};

  int frames_submitted = 0;
  fml::CountDownLatch submit_latch(2);
  auto surface = std::make_unique<MockSurface>();
  ON_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillByDefault(Return(true));
  ON_CALL(*surface, AcquireFrame(DlISize()))
      .WillByDefault([&] {
        SurfaceFrame::FramebufferInfo framebuffer_info;
        framebuffer_info.supports_readback = true;
        return std::make_unique<SurfaceFrame>(
            /*surface=*/nullptr, framebuffer_info,
            /*submit_callback=*/
            [&](const SurfaceFrame& frame, DlCanvas*) {
              const auto pres_time = *frame.submit_info().presentation_time;
              const auto diff = pres_time - first_timestamp;
              int num_frames_submitted = frames_submitted++;
              EXPECT_EQ(diff.ToMilliseconds(),
                        num_frames_submitted * millis_16.ToMilliseconds());
              submit_latch.CountDown();
              return true;
            },
            /*frame_size=*/DlISize(800, 600));
      });

  ON_CALL(*surface, MakeRenderContextCurrent())
      .WillByDefault(
          [] { return std::make_unique<GLContextDefaultResult>(true); });

  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer->Setup(std::move(surface));
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    for (int i = 0; i < 2; i++) {
      auto layer_tree = std::make_unique<LayerTree>(
          /*root_layer=*/nullptr, /*frame_size=*/DlISize());
      auto layer_tree_item = std::make_unique<FrameItem>(
          SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                             kDevicePixelRatio),
          CreateFinishedBuildRecorder(timestamps[i]));
      PipelineProduceResult result =
          pipeline->Produce().Complete(std::move(layer_tree_item));
      EXPECT_TRUE(result.success);
      EXPECT_EQ(result.is_first_item, i == 0);
    }
    // Although we only call 'Rasterizer::Draw' once, it will be called twice
    // finally because there are two items in the pipeline.
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
  });

  submit_latch.Wait();
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer.reset();
    latch.Signal();
  });
  latch.Wait();
#endif  // false
}

TEST(RasterizerTest, presentationTimeNotSetWhenVsyncTargetInPast) {
  GTEST_SKIP() << "eglPresentationTime is disabled due to "
                  "https://github.com/flutter/flutter/issues/112503";
#if false
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform | ThreadHost::Type::kRaster |
                             ThreadHost::Type::kIo | ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());

  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  ON_CALL(delegate, GetTaskRunners()).WillByDefault(ReturnRef(task_runners));

  fml::AutoResetWaitableEvent latch;
  std::unique_ptr<Rasterizer> rasterizer;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer = std::make_unique<Rasterizer>(delegate);
    latch.Signal();
  });
  latch.Wait();

  const auto millis_16 = fml::TimeDelta::FromMilliseconds(16);
  const auto first_timestamp = fml::TimePoint::Now() - millis_16;

  fml::CountDownLatch submit_latch(1);
  auto surface = std::make_unique<MockSurface>();
  ON_CALL(*surface, AllowsDrawingWhenGpuDisabled()).WillByDefault(Return(true));
  ON_CALL(*surface, AcquireFrame(DlISize()))
      .WillByDefault([&] {
        SurfaceFrame::FramebufferInfo framebuffer_info;
        framebuffer_info.supports_readback = true;
        return std::make_unique<SurfaceFrame>(
            /*surface=*/nullptr, framebuffer_info,
            /*submit_callback=*/
            [&](const SurfaceFrame& frame, DlCanvas*) {
              const std::optional<fml::TimePoint> pres_time =
                  frame.submit_info().presentation_time;
              EXPECT_EQ(pres_time, std::nullopt);
              submit_latch.CountDown();
              return true;
            },
            /*frame_size=*/DlISize(800, 600));
      });

  ON_CALL(*surface, MakeRenderContextCurrent())
      .WillByDefault(
          [] { return std::make_unique<GLContextDefaultResult>(true); });

  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer->Setup(std::move(surface));
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                           kDevicePixelRatio),
        CreateFinishedBuildRecorder(first_timestamp));
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    EXPECT_EQ(result.is_first_item, true);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));
    rasterizer->Draw(pipeline);
  });

  submit_latch.Wait();
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer.reset();
    latch.Signal();
  });
  latch.Wait();
#endif  // false
}

TEST(RasterizerTest, DrawLastLayerTreesRetainsReusableTaskWhenRedrawFails) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillOnce(Return(ByMove(std::make_unique<GLContextDefaultResult>(true))));

  int acquire_count = 0;
  int submitted_count = 0;
  EXPECT_CALL(*surface, AcquireFrame(DlISize()))
      .Times(3)
      .WillRepeatedly([&](const DlISize&) -> std::unique_ptr<SurfaceFrame> {
        acquire_count++;
        if (acquire_count == 2) {
          return nullptr;
        }
        SurfaceFrame::FramebufferInfo framebuffer_info;
        framebuffer_info.supports_readback = true;
        return std::make_unique<SurfaceFrame>(
            /*surface=*/nullptr, framebuffer_info,
            /*encode_callback=*/
            [](const SurfaceFrame&, DlCanvas*) { return true; },
            /*submit_callback=*/
            [&](const SurfaceFrame&) {
              submitted_count++;
              return true;
            },
            /*frame_size=*/DlISize(800, 600));
      });

  rasterizer->Setup(std::move(surface));
  fml::AutoResetWaitableEvent latch;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/10);
    auto layer_tree = std::make_unique<LayerTree>(
        /*root_layer=*/nullptr, /*frame_size=*/DlISize());
    auto layer_tree_item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(layer_tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    PipelineProduceResult result =
        pipeline->Produce().Complete(std::move(layer_tree_item));
    EXPECT_TRUE(result.success);
    ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));

    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kDone);
    EXPECT_EQ(rasterizer->GetLastDrawStatus(kImplicitViewId),
              DrawSurfaceStatus::kSuccess);
    EXPECT_EQ(submitted_count, 1);
    ASSERT_NE(rasterizer->GetLastLayerTree(kImplicitViewId), nullptr);
    EXPECT_TRUE(
        rasterizer->GetLastLayerTree(kImplicitViewId)->IsPrerollReusable());

    // First DrawLastLayerTrees fails (AcquireFrame returns nullptr), but must
    // preserve the reusable last_layer_tree_tasks_ entry.
    rasterizer->DrawLastLayerTrees(CreateFinishedBuildRecorder());
    EXPECT_EQ(rasterizer->GetLastDrawStatus(kImplicitViewId),
              DrawSurfaceStatus::kFailed);
    EXPECT_EQ(submitted_count, 1);
    EXPECT_NE(rasterizer->GetLastLayerTree(kImplicitViewId), nullptr);

    // Subsequent DrawLastLayerTrees succeeds using the retained layer tree.
    rasterizer->DrawLastLayerTrees(CreateFinishedBuildRecorder());
    EXPECT_EQ(rasterizer->GetLastDrawStatus(kImplicitViewId),
              DrawSurfaceStatus::kSuccess);
    EXPECT_EQ(submitted_count, 2);
    latch.Signal();
  });
  latch.Wait();
}

namespace {

class SynchronizedMockTexture final : public Texture {
 public:
  explicit SynchronizedMockTexture(int64_t id) : Texture(id) {}

  void Paint(PaintContext& context,
             const DlRect& bounds,
             bool freeze,
             const DlImageSampling sampling) override {}
  void OnGrContextCreated() override {}
  void OnGrContextDestroyed() override {}
  void MarkNewFrameAvailable() override {}

  void OnTextureUnregistered() override {
    std::vector<Waiter> waiters;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      waiters.swap(waiters_);
    }
    waiters.clear();
  }

  uint64_t GetCurrentFrameGeneration() const override {
    std::lock_guard<std::mutex> lock(mutex_);
    return generation_;
  }

  SkISize GetCurrentFrameSize() const override {
    std::lock_guard<std::mutex> lock(mutex_);
    return size_;
  }

  bool SatisfiesSyncRequirement(uint64_t min_generation,
                                SkISize expected_size) const override {
    std::lock_guard<std::mutex> lock(mutex_);
    return SatisfiesLocked(min_generation, expected_size);
  }

  uint64_t RegisterSyncReadyCallback(uint64_t min_generation,
                                     SkISize expected_size,
                                     fml::closure on_ready) override {
    if (!on_ready) {
      return 0;
    }
    bool ready_now = false;
    uint64_t token = 0;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (SatisfiesLocked(min_generation, expected_size)) {
        ready_now = true;
      } else {
        token = next_token_++;
        waiters_.push_back(Waiter{
            .token = token,
            .min_generation = min_generation,
            .expected_size = expected_size,
            .on_ready = std::move(on_ready),
        });
      }
    }
    if (ready_now) {
      on_ready();
      return 0;
    }
    return token;
  }

  void CancelSyncReadyCallback(uint64_t token) override {
    if (token == 0) {
      return;
    }
    fml::closure canceled;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      for (auto it = waiters_.begin(); it != waiters_.end(); ++it) {
        if (it->token == token) {
          canceled = std::move(it->on_ready);
          waiters_.erase(it);
          break;
        }
      }
    }
  }

  size_t GetActiveWaiterCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return waiters_.size();
  }

  void PublishFrame(uint64_t generation, SkISize size) {
    std::vector<fml::closure> ready;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      generation_ = generation;
      size_ = size;
      auto it = waiters_.begin();
      while (it != waiters_.end()) {
        if (SatisfiesLocked(it->min_generation, it->expected_size)) {
          if (it->on_ready) {
            ready.push_back(std::move(it->on_ready));
          }
          it = waiters_.erase(it);
        } else {
          ++it;
        }
      }
    }
    for (auto& cb : ready) {
      cb();
    }
  }

 private:
  struct Waiter {
    uint64_t token = 0;
    uint64_t min_generation = 0;
    SkISize expected_size = SkISize::MakeEmpty();
    fml::closure on_ready;
  };

  bool SatisfiesLocked(uint64_t min_generation, SkISize expected_size) const {
    if (min_generation > 0 && generation_ < min_generation) {
      return false;
    }
    if (!expected_size.isEmpty() && size_ != expected_size) {
      return false;
    }
    return true;
  }

  mutable std::mutex mutex_;
  uint64_t next_token_ = 1;
  uint64_t generation_ = 0;
  SkISize size_ = SkISize::MakeEmpty();
  std::vector<Waiter> waiters_;
};

std::unique_ptr<LayerTree> MakeLayerTreeWithTextureSync(
    int64_t texture_id,
    uint64_t min_generation,
    SkISize expected_size,
    fml::TimeDelta max_wait) {
  TextureSyncRequirement req;
  req.texture_id = texture_id;
  req.min_frame_generation = min_generation;
  req.expected_physical_size = expected_size;
  req.max_wait = max_wait;

  auto root = std::make_shared<ContainerLayer>();
  root->Add(std::make_shared<TextureLayer>(
      DlPoint(0.0f, 0.0f), DlSize(400.0f, 300.0f), texture_id, false,
      DlImageSampling::kLinear, req));
  return std::make_unique<LayerTree>(root, DlISize(800, 600));
}

}  // namespace

TEST(RasterizerTest, SyncLatchZeroDelayPassThroughWhenAlreadySatisfied) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillRepeatedly([]() -> std::unique_ptr<GLContextResult> {
        return std::make_unique<GLContextDefaultResult>(true);
      });

  int submitted_count = 0;
  EXPECT_CALL(*surface, AcquireFrame(DlISize(800, 600)))
      .WillOnce([&](const DlISize&) -> std::unique_ptr<SurfaceFrame> {
        SurfaceFrame::FramebufferInfo framebuffer_info;
        framebuffer_info.supports_readback = true;
        return std::make_unique<SurfaceFrame>(
            /*surface=*/nullptr, framebuffer_info,
            [](const SurfaceFrame&, DlCanvas*) { return true; },
            [&](const SurfaceFrame&) {
              submitted_count++;
              return true;
            },
            /*frame_size=*/DlISize(800, 600));
      });

  constexpr int64_t kTextureId = 501;
  auto sync_texture = std::make_shared<SynchronizedMockTexture>(kTextureId);
  sync_texture->PublishFrame(5, SkISize::Make(800, 600));

  rasterizer->Setup(std::move(surface));
  rasterizer->GetTextureRegistry()->RegisterTexture(sync_texture);

  fml::AutoResetWaitableEvent done;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto pipeline = std::make_shared<FramePipeline>(/*depth=*/4);
    auto tree = MakeLayerTreeWithTextureSync(
        kTextureId, /*min_generation=*/5, SkISize::Make(800, 600),
        fml::TimeDelta::FromMilliseconds(50));
    auto item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    ASSERT_TRUE(pipeline->Produce().Complete(std::move(item)).success);

    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kDone);
    EXPECT_EQ(submitted_count, 1);
    EXPECT_FALSE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(rasterizer->GetSyncFramesHeldCount(), 0u);
    EXPECT_EQ(rasterizer->GetSyncFramesLatchedCount(), 0u);
    EXPECT_EQ(rasterizer->GetSyncFramesTimedOutCount(), 0u);
    done.Signal();
  });
  done.Wait();
}

TEST(RasterizerTest,
     SyncLatchHoldsFrameAndLatchesAtomicallyWithZeroTornFrames) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));
  ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));

  constexpr int64_t kTextureId = 502;
  auto sync_texture = std::make_shared<SynchronizedMockTexture>(kTextureId);
  // Initially at frame generation 1 (400x300).
  sync_texture->PublishFrame(1, SkISize::Make(400, 300));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillRepeatedly([]() -> std::unique_ptr<GLContextResult> {
        return std::make_unique<GLContextDefaultResult>(true);
      });

  std::atomic<int> submitted_count{0};
  std::atomic<uint64_t> texture_gen_at_submit{0};
  std::atomic<int> texture_width_at_submit{0};
  fml::AutoResetWaitableEvent frame_latched;

  EXPECT_CALL(*surface, AcquireFrame(DlISize(800, 600)))
      .WillOnce([&](const DlISize&) -> std::unique_ptr<SurfaceFrame> {
        SurfaceFrame::FramebufferInfo framebuffer_info;
        framebuffer_info.supports_readback = true;
        return std::make_unique<SurfaceFrame>(
            /*surface=*/nullptr, framebuffer_info,
            [](const SurfaceFrame&, DlCanvas*) { return true; },
            [&](const SurfaceFrame&) {
              texture_gen_at_submit.store(
                  sync_texture->GetCurrentFrameGeneration());
              texture_width_at_submit.store(
                  sync_texture->GetCurrentFrameSize().width());
              submitted_count.fetch_add(1);
              frame_latched.Signal();
              return true;
            },
            /*frame_size=*/DlISize(800, 600));
      });

  rasterizer->Setup(std::move(surface));
  rasterizer->GetTextureRegistry()->RegisterTexture(sync_texture);

  auto pipeline = std::make_shared<FramePipeline>(/*depth=*/4);
  fml::AutoResetWaitableEvent held_verified;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    auto tree = MakeLayerTreeWithTextureSync(
        kTextureId, /*min_generation=*/2, SkISize::Make(800, 600),
        fml::TimeDelta::FromMilliseconds(250));
    auto item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    ASSERT_TRUE(pipeline->Produce().Complete(std::move(item)).success);

    DrawStatus status = rasterizer->Draw(pipeline);
    EXPECT_EQ(status, DrawStatus::kDone);
    // CRITICAL INVARIANT: 0 intermediate torn frames `(LayerTree_k, PV_{k-1})`!
    EXPECT_EQ(submitted_count.load(), 0);
    EXPECT_TRUE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(rasterizer->GetSyncFramesHeldCount(), 1u);
    held_verified.Signal();
  });
  held_verified.Wait();

  // Simulate PV_k arriving 4 ms later on the platform/producer thread.
  std::this_thread::sleep_for(std::chrono::milliseconds(4));
  sync_texture->PublishFrame(2, SkISize::Make(800, 600));

  EXPECT_FALSE(
      frame_latched.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(500)));
  EXPECT_EQ(submitted_count.load(), 1);
  EXPECT_EQ(texture_gen_at_submit.load(), 2u);
  EXPECT_EQ(texture_width_at_submit.load(), 800);

  fml::AutoResetWaitableEvent counters_verified;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    EXPECT_FALSE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(rasterizer->GetSyncFramesHeldCount(), 1u);
    EXPECT_EQ(rasterizer->GetSyncFramesLatchedCount(), 1u);
    EXPECT_EQ(rasterizer->GetSyncFramesTimedOutCount(), 0u);
    counters_verified.Signal();
  });
  counters_verified.Wait();
}

TEST(RasterizerTest, SyncLatchBoundedTimeoutDrawsCleanlyWhenProducerStalls) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_));
  ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));

  constexpr int64_t kTextureId = 503;
  auto sync_texture = std::make_shared<SynchronizedMockTexture>(kTextureId);
  sync_texture->PublishFrame(1, SkISize::Make(400, 300));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillRepeatedly([]() -> std::unique_ptr<GLContextResult> {
        return std::make_unique<GLContextDefaultResult>(true);
      });

  std::atomic<int> submitted_count{0};
  fml::AutoResetWaitableEvent timeout_submitted;

  EXPECT_CALL(*surface, AcquireFrame(DlISize(800, 600)))
      .WillOnce([&](const DlISize&) -> std::unique_ptr<SurfaceFrame> {
        SurfaceFrame::FramebufferInfo framebuffer_info;
        framebuffer_info.supports_readback = true;
        return std::make_unique<SurfaceFrame>(
            /*surface=*/nullptr, framebuffer_info,
            [](const SurfaceFrame&, DlCanvas*) { return true; },
            [&](const SurfaceFrame&) {
              submitted_count.fetch_add(1);
              timeout_submitted.Signal();
              return true;
            },
            /*frame_size=*/DlISize(800, 600));
      });

  rasterizer->Setup(std::move(surface));
  rasterizer->GetTextureRegistry()->RegisterTexture(sync_texture);

  auto pipeline = std::make_shared<FramePipeline>(/*depth=*/4);
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    // Require generation 99 which never arrives; bounded max_wait = 15ms.
    auto tree = MakeLayerTreeWithTextureSync(
        kTextureId, /*min_generation=*/99, SkISize::MakeEmpty(),
        fml::TimeDelta::FromMilliseconds(15));
    auto item = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(tree),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    ASSERT_TRUE(pipeline->Produce().Complete(std::move(item)).success);
    EXPECT_EQ(rasterizer->Draw(pipeline), DrawStatus::kDone);
    EXPECT_EQ(submitted_count.load(), 0);
    EXPECT_TRUE(rasterizer->HasHeldSyncFrame());
  });

  // Producer never publishes generation 99; fallback deadline MUST fire
  // cleanly!
  EXPECT_FALSE(
      timeout_submitted.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(500)));
  EXPECT_EQ(submitted_count.load(), 1);

  fml::AutoResetWaitableEvent verified;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    EXPECT_FALSE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(rasterizer->GetSyncFramesHeldCount(), 1u);
    EXPECT_EQ(rasterizer->GetSyncFramesTimedOutCount(), 1u);
    EXPECT_EQ(rasterizer->GetSyncFramesLatchedCount(), 0u);
    verified.Signal();
  });
  verified.Wait();
}

TEST(RasterizerTest,
     SyncLatchSupersedesOlderHeldFrameIgnoresStaleTimerAndTeardownsCleanly) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));

  constexpr int64_t kTextureId = 504;
  auto sync_texture = std::make_shared<SynchronizedMockTexture>(kTextureId);
  sync_texture->PublishFrame(1, SkISize::Make(400, 300));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillRepeatedly([]() -> std::unique_ptr<GLContextResult> {
        return std::make_unique<GLContextDefaultResult>(true);
      });

  rasterizer->Setup(std::move(surface));
  rasterizer->GetTextureRegistry()->RegisterTexture(sync_texture);

  auto pipeline = std::make_shared<FramePipeline>(/*depth=*/4);
  fml::AutoResetWaitableEvent superseded_ready;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    // Hold Frame 1 (gen 10, 30ms timeout).
    auto tree1 = MakeLayerTreeWithTextureSync(
        kTextureId, /*min_generation=*/10, SkISize::MakeEmpty(),
        fml::TimeDelta::FromMilliseconds(30));
    auto item1 = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(tree1),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    ASSERT_TRUE(pipeline->Produce().Complete(std::move(item1)).success);
    EXPECT_EQ(rasterizer->Draw(pipeline), DrawStatus::kDone);
    EXPECT_TRUE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(rasterizer->GetSyncFramesHeldCount(), 1u);
    EXPECT_EQ(sync_texture->GetActiveWaiterCount(), 1u);

    // Supersede immediately with Frame 2 (gen 20, 30ms timeout): Frame 1's
    // waiter must be immediately canceled so only 1 waiter is active.
    auto tree2 = MakeLayerTreeWithTextureSync(
        kTextureId, /*min_generation=*/20, SkISize::MakeEmpty(),
        fml::TimeDelta::FromMilliseconds(30));
    auto item2 = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(tree2),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    ASSERT_TRUE(pipeline->Produce().Complete(std::move(item2)).success);
    EXPECT_EQ(rasterizer->Draw(pipeline), DrawStatus::kDone);
    EXPECT_TRUE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(rasterizer->GetSyncFramesHeldCount(), 2u);
    EXPECT_EQ(rasterizer->GetSyncFramesSupersededCount(), 1u);
    EXPECT_EQ(sync_texture->GetActiveWaiterCount(), 1u);
    superseded_ready.Signal();
  });
  superseded_ready.Wait();

  fml::AutoResetWaitableEvent torn_down;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    EXPECT_TRUE(rasterizer->HasHeldSyncFrame());
    rasterizer->Teardown();
    EXPECT_FALSE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(sync_texture->GetActiveWaiterCount(), 0u);
    torn_down.Signal();
  });
  torn_down.Wait();

  // Late producer publication after Teardown must be a safe no-op.
  sync_texture->PublishFrame(25, SkISize::Make(800, 600));
  fml::AutoResetWaitableEvent drained;
  thread_host.raster_thread->GetTaskRunner()->PostTask(
      [&] { drained.Signal(); });
  drained.Wait();
}

TEST(RasterizerTest,
     SyncLatchNeverExtendsDeadlineOrStarvesAcrossContinuous120HzSupersedes) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_)).Times(::testing::AtLeast(1));
  ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));

  constexpr int64_t kTextureId = 505;
  auto sync_texture = std::make_shared<SynchronizedMockTexture>(kTextureId);
  sync_texture->PublishFrame(1, SkISize::Make(400, 300));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillRepeatedly([]() -> std::unique_ptr<GLContextResult> {
        return std::make_unique<GLContextDefaultResult>(true);
      });

  std::atomic<int> submitted_count{0};
  fml::AutoResetWaitableEvent first_submit;
  EXPECT_CALL(*surface, AcquireFrame(DlISize(800, 600)))
      .WillRepeatedly([&](const DlISize&) -> std::unique_ptr<SurfaceFrame> {
        SurfaceFrame::FramebufferInfo framebuffer_info;
        framebuffer_info.supports_readback = true;
        return std::make_unique<SurfaceFrame>(
            /*surface=*/nullptr, framebuffer_info,
            [](const SurfaceFrame&, DlCanvas*) { return true; },
            [&](const SurfaceFrame&) {
              if (submitted_count.fetch_add(1) == 0) {
                first_submit.Signal();
              }
              return true;
            },
            /*frame_size=*/DlISize(800, 600));
      });

  rasterizer->Setup(std::move(surface));
  rasterizer->GetTextureRegistry()->RegisterTexture(sync_texture);

  auto pipeline = std::make_shared<FramePipeline>(/*depth=*/4);

  // Submit 4 consecutive frames spaced 8 ms apart (simulating 120 Hz) whose
  // producer never publishes generation 99. Because supersede inherits and
  // caps `min(old.deadline, new.deadline)`, the inherited 16 ms deadline
  // expires on time and submits a frame without unbounded starvation.
  for (int i = 0; i < 4; ++i) {
    fml::AutoResetWaitableEvent step_done;
    thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
      auto tree = MakeLayerTreeWithTextureSync(
          kTextureId, /*min_generation=*/99, SkISize::Make(800, 600),
          fml::TimeDelta::FromMilliseconds(16));
      auto item = std::make_unique<FrameItem>(
          SingleLayerTreeList(kImplicitViewId, std::move(tree),
                              kDevicePixelRatio),
          CreateFinishedBuildRecorder());
      ASSERT_TRUE(pipeline->Produce().Complete(std::move(item)).success);
      EXPECT_EQ(rasterizer->Draw(pipeline), DrawStatus::kDone);
      step_done.Signal();
    });
    step_done.Wait();
    std::this_thread::sleep_for(std::chrono::milliseconds(8));
  }

  EXPECT_FALSE(
      first_submit.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(250)));
  EXPECT_GE(submitted_count.load(), 1);
  EXPECT_GE(rasterizer->GetSyncFramesTimedOutCount(), 1u);

  fml::AutoResetWaitableEvent cleanup;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    rasterizer->Teardown();
    EXPECT_EQ(sync_texture->GetActiveWaiterCount(), 0u);
    cleanup.Signal();
  });
  cleanup.Wait();
}

TEST(RasterizerTest,
     SyncLatchCarriesRequirementAcrossUnconstrainedFrameAndDrawLastLayerTrees) {
  std::string test_name =
      ::testing::UnitTest::GetInstance()->current_test_info()->name();
  ThreadHost thread_host("io.flutter.test." + test_name + ".",
                         ThreadHost::Type::kPlatform |
                             ThreadHost::Type::kRaster | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi);
  TaskRunners task_runners("test", thread_host.platform_thread->GetTaskRunner(),
                           thread_host.raster_thread->GetTaskRunner(),
                           thread_host.ui_thread->GetTaskRunner(),
                           thread_host.io_thread->GetTaskRunner());
  NiceMock<MockDelegate> delegate;
  Settings settings;
  ON_CALL(delegate, GetSettings()).WillByDefault(ReturnRef(settings));
  EXPECT_CALL(delegate, GetTaskRunners())
      .WillRepeatedly(ReturnRef(task_runners));
  EXPECT_CALL(delegate, OnFrameRasterized(_)).Times(::testing::AtLeast(1));
  ON_CALL(delegate, ShouldDiscardLayerTree).WillByDefault(Return(false));

  constexpr int64_t kTextureId = 506;
  auto sync_texture = std::make_shared<SynchronizedMockTexture>(kTextureId);
  sync_texture->PublishFrame(1, SkISize::Make(400, 300));

  auto rasterizer = std::make_unique<Rasterizer>(delegate);
  auto surface = std::make_unique<NiceMock<MockSurface>>();
  EXPECT_CALL(*surface, AllowsDrawingWhenGpuDisabled())
      .WillRepeatedly(Return(true));
  EXPECT_CALL(*surface, MakeRenderContextCurrent())
      .WillRepeatedly([]() -> std::unique_ptr<GLContextResult> {
        return std::make_unique<GLContextDefaultResult>(true);
      });

  std::atomic<int> submitted_count{0};
  fml::AutoResetWaitableEvent latched_submit;
  EXPECT_CALL(*surface, AcquireFrame(DlISize(800, 600)))
      .WillOnce([&](const DlISize&) -> std::unique_ptr<SurfaceFrame> {
        SurfaceFrame::FramebufferInfo framebuffer_info;
        framebuffer_info.supports_readback = true;
        return std::make_unique<SurfaceFrame>(
            /*surface=*/nullptr, framebuffer_info,
            [](const SurfaceFrame&, DlCanvas*) { return true; },
            [&](const SurfaceFrame&) {
              submitted_count.fetch_add(1);
              latched_submit.Signal();
              return true;
            },
            /*frame_size=*/DlISize(800, 600));
      });

  rasterizer->Setup(std::move(surface));
  rasterizer->GetTextureRegistry()->RegisterTexture(sync_texture);

  auto pipeline = std::make_shared<FramePipeline>(/*depth=*/4);
  fml::AutoResetWaitableEvent phase1_done;
  thread_host.raster_thread->GetTaskRunner()->PostTask([&] {
    // Frame k requires gen >= 5.
    auto tree_k = MakeLayerTreeWithTextureSync(
        kTextureId, /*min_generation=*/5, SkISize::Make(800, 600),
        fml::TimeDelta::FromMilliseconds(30));
    auto item_k = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(tree_k),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    ASSERT_TRUE(pipeline->Produce().Complete(std::move(item_k)).success);
    EXPECT_EQ(rasterizer->Draw(pipeline), DrawStatus::kDone);
    EXPECT_TRUE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(submitted_count.load(), 0);

    // Frame k+1 arrives with NO sync requirement on its TextureLayer: it MUST
    // inherit Frame k's still-unsatisfied requirement and remain held so
    // `(LayerTree_{k+1}, PV_{k-1})` is never presented torn!
    auto tree_k1 = MakeLayerTreeWithTextureSync(
        kTextureId, /*min_generation=*/0, SkISize::MakeEmpty(),
        fml::TimeDelta::Zero());
    auto item_k1 = std::make_unique<FrameItem>(
        SingleLayerTreeList(kImplicitViewId, std::move(tree_k1),
                            kDevicePixelRatio),
        CreateFinishedBuildRecorder());
    ASSERT_TRUE(pipeline->Produce().Complete(std::move(item_k1)).success);
    EXPECT_EQ(rasterizer->Draw(pipeline), DrawStatus::kDone);
    EXPECT_TRUE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(submitted_count.load(), 0);

    // Calling DrawLastLayerTrees while still unsatisfied keeps holding.
    rasterizer->DrawLastLayerTrees(CreateFinishedBuildRecorder());
    EXPECT_TRUE(rasterizer->HasHeldSyncFrame());
    EXPECT_EQ(submitted_count.load(), 0);
    phase1_done.Signal();
  });
  phase1_done.Wait();

  // Now publish gen 5 (800x600): the carried-over requirement is satisfied and
  // Frame k+1 latches immediately with 0 torn frames and 0 leaked waiters!
  sync_texture->PublishFrame(5, SkISize::Make(800, 600));
  EXPECT_FALSE(
      latched_submit.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(250)));
  EXPECT_EQ(submitted_count.load(), 1);
  EXPECT_EQ(sync_texture->GetActiveWaiterCount(), 0u);
}

}  // namespace flutter
