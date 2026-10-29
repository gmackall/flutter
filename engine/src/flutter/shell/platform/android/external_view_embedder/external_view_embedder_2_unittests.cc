// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#define FML_USED_ON_EMBEDDER

#include <memory>

#include "flutter/display_list/effects/dl_color_filter.h"
#include "flutter/display_list/effects/image_filters/dl_blur_image_filter.h"
#include "flutter/display_list/effects/image_filters/dl_color_filter_image_filter.h"
#include "flutter/display_list/image/dl_image_skia.h"
#include "flutter/flow/embedded_views.h"
#include "flutter/flow/surface.h"
#include "flutter/fml/make_copyable.h"
#include "flutter/fml/message_loop.h"
#include "flutter/shell/common/thread_host.h"
#include "flutter/shell/platform/android/external_view_embedder/external_view_embedder_2.h"
#include "flutter/shell/platform/android/jni/jni_mock.h"
#include "flutter/shell/platform/android/surface/android_surface.h"
#include "flutter/shell/platform/android/surface/android_surface_mock.h"
#include "flutter/testing/post_task_sync.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "third_party/skia/include/core/SkBitmap.h"
#include "third_party/skia/include/core/SkSurface.h"

namespace flutter {
namespace testing {

namespace {

constexpr double kDpr = 2.0;
const DlISize kFrameSize = DlISize(1000, 2000);

TaskRunners GetTaskRunnersForFixture() {
  fml::MessageLoop::EnsureInitializedForCurrentThread();
  auto& loop = fml::MessageLoop::GetCurrent();
  return {
      "test",
      loop.GetTaskRunner(),  // platform
      loop.GetTaskRunner(),  // raster
      loop.GetTaskRunner(),  // ui
      loop.GetTaskRunner()   // io
  };
}

class FakeImage final : public DlImage {
 public:
  explicit FakeImage(DlISize size) : size_(size) {}
  Type GetImageType() const override { return Type::kSkia; }
  bool isTextureBacked() const override { return true; }
  DlColorSpace GetColorSpace() const override { return DlColorSpace::kSRGB; }
  bool isOpaque() const override { return true; }
  bool isUIThreadSafe() const override { return true; }
  DlISize GetSize() const override { return size_; }
  size_t GetApproximateByteSize() const override { return 0; }

 private:
  DlISize size_;
};

class FakeSnapshotSource final : public PlatformViewSnapshotSource {
 public:
  explicit FakeSnapshotSource(sk_sp<DlImage> image)
      : image_(std::move(image)) {}
  sk_sp<DlImage> AcquireLatestImage() override {
    acquire_count++;
    return image_;
  }
  int acquire_count = 0;

 private:
  sk_sp<DlImage> image_;
};

class MockCaptureDelegate final : public PlatformViewCaptureDelegate {
 public:
  MOCK_METHOD(std::shared_ptr<PlatformViewSnapshotSource>,
              RequestCapture,
              (int64_t view_id,
               bool substituted,
               const std::shared_ptr<PlatformViewSnapshotSource>& previous),
              (override));
};

std::unique_ptr<AndroidExternalViewEmbedder2> MakeEmbedder(
    std::shared_ptr<PlatformViewCaptureDelegate> delegate =
        std::make_shared<MockCaptureDelegate>()) {
  static AndroidContext android_context(AndroidRenderingAPI::kSoftware);
  auto embedder = std::make_unique<AndroidExternalViewEmbedder2>(
      android_context, std::make_shared<JNIMock>(), nullptr,
      GetTaskRunnersForFixture(), std::move(delegate));
  embedder->PrepareFlutterView(kFrameSize, kDpr);
  return embedder;
}

// Prerolls a platform view covering `rect_points` (logical pixels), as the
// platform view layer would.
void AddPlatformView(AndroidExternalViewEmbedder2& embedder,
                     int64_t view_id,
                     const DlRect& rect_points) {
  DlMatrix matrix =
      DlMatrix::MakeScale({kDpr, kDpr, 1}) *
      DlMatrix::MakeTranslation({rect_points.GetX(), rect_points.GetY(), 0});
  MutatorsStack stack;
  stack.PushTransform(matrix);
  embedder.PrerollCompositeEmbeddedView(
      view_id, std::make_unique<EmbeddedViewParams>(
                   matrix, rect_points.GetSize(), stack));
}

std::shared_ptr<DlImageFilter> Blur(DlScalar sigma) {
  return DlBlurImageFilter::Make(sigma, sigma, DlTileMode::kClamp);
}

std::shared_ptr<DlImageFilter> Grayscale() {
  const float matrix[20] = {
      0.2126f, 0.7152f, 0.0722f, 0, 0,  //
      0.2126f, 0.7152f, 0.0722f, 0, 0,  //
      0.2126f, 0.7152f, 0.0722f, 0, 0,  //
      0,       0,       0,       1, 0,
  };
  return DlColorFilterImageFilter::Make(DlColorFilter::MakeMatrix(matrix));
}

// Device-pixel rect of a filter, as the backdrop filter layer reports it.
DlRect DeviceRect(const DlRect& rect_points) {
  return rect_points.Scale(kDpr);
}

// Paints a backdrop filter clipped to `rect_points` into `canvas`, as a
// ClipRectLayer with a BackdropFilterLayer child would.
void PaintBackdropFilter(DlCanvas* canvas,
                         const std::shared_ptr<DlImageFilter>& filter,
                         const DlRect& rect_points) {
  canvas->Save();
  canvas->Scale(kDpr, kDpr);
  canvas->ClipRect(rect_points);
  canvas->SaveLayer(rect_points, nullptr, filter.get());
  canvas->Restore();
  canvas->Restore();
}

}  // namespace

TEST(AndroidExternalViewEmbedder2Capture, NoCaptureWithoutDelegate) {
  auto embedder = MakeEmbedder(nullptr);
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  embedder->PushFilterToVisitedPlatformViews(
      Blur(5), DeviceRect(DlRect::MakeLTRB(50, 50, 150, 150)));
  EXPECT_TRUE(embedder->GetViewsToCaptureForTesting().empty());
}

TEST(AndroidExternalViewEmbedder2Capture, IgnoresFiltersBelowAllViews) {
  auto embedder = MakeEmbedder();
  // The filter is painted before any platform view, so it is below them.
  embedder->PushFilterToVisitedPlatformViews(
      Blur(5), DeviceRect(DlRect::MakeLTRB(0, 0, 100, 100)));
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  EXPECT_TRUE(embedder->GetViewsToCaptureForTesting().empty());
}

TEST(AndroidExternalViewEmbedder2Capture,
     IgnoresFiltersThatDoNotCoverAPlatformView) {
  auto embedder = MakeEmbedder();
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  embedder->PushFilterToVisitedPlatformViews(
      Grayscale(), DeviceRect(DlRect::MakeLTRB(200, 200, 300, 300)));
  EXPECT_TRUE(embedder->GetViewsToCaptureForTesting().empty());
}

TEST(AndroidExternalViewEmbedder2Capture, CapturesViewsUnderAndNearFilters) {
  auto embedder = MakeEmbedder();
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  AddPlatformView(*embedder, 2, DlRect::MakeLTRB(0, 800, 100, 900));
  // Covers view 1 only; view 2 is too far away to be prefetched.
  embedder->PushFilterToVisitedPlatformViews(
      Grayscale(), DeviceRect(DlRect::MakeLTRB(50, 50, 150, 150)));
  EXPECT_THAT(embedder->GetViewsToCaptureForTesting(),
              ::testing::ElementsAre(1));

  // A blur just above view 2 does not cover it, but view 2 is close enough
  // that it may soon move under it, so it is captured ahead of time.
  embedder->PushFilterToVisitedPlatformViews(
      Blur(10), DeviceRect(DlRect::MakeLTRB(0, 700, 100, 780)));
  EXPECT_THAT(embedder->GetViewsToCaptureForTesting(),
              ::testing::ElementsAre(1, 2));
}

TEST(AndroidExternalViewEmbedder2Capture, DoesNotPrefetchWithoutCoveredViews) {
  auto embedder = MakeEmbedder();
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 300, 100, 400));
  // Near, but not over, the platform view.
  embedder->PushFilterToVisitedPlatformViews(
      Blur(10), DeviceRect(DlRect::MakeLTRB(0, 200, 100, 290)));
  EXPECT_TRUE(embedder->GetViewsToCaptureForTesting().empty());
}

TEST(AndroidExternalViewEmbedder2Capture, IgnoresViewsOutsideTheFrame) {
  auto embedder = MakeEmbedder();
  // Scrolled just above the top of the frame (but still in the layer tree).
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, -200, 100, -10));
  AddPlatformView(*embedder, 2, DlRect::MakeLTRB(0, 0, 100, 100));
  embedder->PushFilterToVisitedPlatformViews(
      Blur(10), DeviceRect(DlRect::MakeLTRB(0, 0, 100, 50)));
  // View 1 is entirely outside the frame, so it is neither required (a
  // missing capture of it must not prevent the filter from applying to view
  // 2) nor captured.
  EXPECT_THAT(embedder->GetViewsToCaptureForTesting(),
              ::testing::ElementsAre(2));
}

TEST(AndroidExternalViewEmbedder2Capture, NeverProvidesARootCanvas) {
  auto embedder = MakeEmbedder();
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  embedder->PushFilterToVisitedPlatformViews(
      Blur(5), DeviceRect(DlRect::MakeLTRB(0, 0, 100, 100)));
  EXPECT_EQ(embedder->GetRootCanvas(), nullptr);
}

TEST(AndroidExternalViewEmbedder2Capture, LeavesViewsAloneWithoutFilters) {
  auto embedder = MakeEmbedder();
  embedder->SetSnapshotSourceForTesting(
      1, std::make_shared<FakeSnapshotSource>(
             sk_make_sp<FakeImage>(DlISize(200, 200))));
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));

  DlCanvas* canvas = embedder->CompositeEmbeddedView(1);
  ASSERT_NE(canvas, nullptr);
  EXPECT_FALSE(embedder->IsSubstitutedForTesting(1));
}

TEST(AndroidExternalViewEmbedder2Capture, SubstitutesCapturedViewUnderFilter) {
  auto embedder = MakeEmbedder();
  auto source = std::make_shared<FakeSnapshotSource>(
      sk_make_sp<FakeImage>(DlISize(200, 200)));
  embedder->SetSnapshotSourceForTesting(1, source);

  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  const DlRect filter_rect = DlRect::MakeLTRB(50, 50, 150, 150);
  auto blur = Blur(5);
  embedder->PushFilterToVisitedPlatformViews(blur, DeviceRect(filter_rect));

  DlCanvas* canvas = embedder->CompositeEmbeddedView(1);
  ASSERT_NE(canvas, nullptr);
  EXPECT_EQ(source->acquire_count, 1);
  EXPECT_TRUE(embedder->IsSubstitutedForTesting(1));
  // The capture is the first thing in the slice, below the filter.
  PaintBackdropFilter(canvas, blur, filter_rect);
}

TEST(AndroidExternalViewEmbedder2Capture, FallsBackUntilCaptureIsAvailable) {
  auto embedder = MakeEmbedder();
  // The capture has been requested, but no image has been produced yet.
  embedder->SetSnapshotSourceForTesting(
      1, std::make_shared<FakeSnapshotSource>(nullptr));

  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  embedder->PushFilterToVisitedPlatformViews(
      Blur(5), DeviceRect(DlRect::MakeLTRB(50, 50, 150, 150)));

  ASSERT_NE(embedder->CompositeEmbeddedView(1), nullptr);
  EXPECT_FALSE(embedder->IsSubstitutedForTesting(1));
}

TEST(AndroidExternalViewEmbedder2Capture, SubstitutesOnlyViewsUnderFilters) {
  auto embedder = MakeEmbedder();
  auto source1 = std::make_shared<FakeSnapshotSource>(
      sk_make_sp<FakeImage>(DlISize(200, 200)));
  auto source2 = std::make_shared<FakeSnapshotSource>(
      sk_make_sp<FakeImage>(DlISize(200, 200)));
  embedder->SetSnapshotSourceForTesting(1, source1);
  embedder->SetSnapshotSourceForTesting(2, source2);
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  // Close enough to the filter to be captured, but not under it.
  AddPlatformView(*embedder, 2, DlRect::MakeLTRB(0, 200, 100, 300));
  embedder->PushFilterToVisitedPlatformViews(
      Blur(10), DeviceRect(DlRect::MakeLTRB(0, 0, 100, 50)));
  EXPECT_THAT(embedder->GetViewsToCaptureForTesting(),
              ::testing::ElementsAre(1, 2));

  ASSERT_NE(embedder->CompositeEmbeddedView(1), nullptr);
  ASSERT_NE(embedder->CompositeEmbeddedView(2), nullptr);
  EXPECT_TRUE(embedder->IsSubstitutedForTesting(1));
  EXPECT_FALSE(embedder->IsSubstitutedForTesting(2));
  EXPECT_EQ(source1->acquire_count, 1);
  EXPECT_EQ(source2->acquire_count, 0);
}

TEST(AndroidExternalViewEmbedder2Capture, DecidesPerView) {
  auto embedder = MakeEmbedder();
  embedder->SetSnapshotSourceForTesting(
      1, std::make_shared<FakeSnapshotSource>(
             sk_make_sp<FakeImage>(DlISize(200, 200))));
  // View 2 has no capture yet (it just scrolled under the second filter).

  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  AddPlatformView(*embedder, 2, DlRect::MakeLTRB(0, 600, 100, 700));
  embedder->PushFilterToVisitedPlatformViews(
      Blur(10), DeviceRect(DlRect::MakeLTRB(0, 0, 100, 50)));
  embedder->PushFilterToVisitedPlatformViews(
      Blur(10), DeviceRect(DlRect::MakeLTRB(0, 650, 100, 700)));

  ASSERT_NE(embedder->CompositeEmbeddedView(1), nullptr);
  ASSERT_NE(embedder->CompositeEmbeddedView(2), nullptr);
  EXPECT_TRUE(embedder->IsSubstitutedForTesting(1));
  EXPECT_FALSE(embedder->IsSubstitutedForTesting(2));
}

TEST(AndroidExternalViewEmbedder2Capture, ResetClearsFrameState) {
  auto embedder = MakeEmbedder();
  AddPlatformView(*embedder, 1, DlRect::MakeLTRB(0, 0, 100, 100));
  embedder->PushFilterToVisitedPlatformViews(
      Blur(5), DeviceRect(DlRect::MakeLTRB(0, 0, 100, 100)));
  ASSERT_FALSE(embedder->GetViewsToCaptureForTesting().empty());

  embedder->SetSnapshotSourceForTesting(
      1, std::make_shared<FakeSnapshotSource>(
             sk_make_sp<FakeImage>(DlISize(200, 200))));
  ASSERT_NE(embedder->CompositeEmbeddedView(1), nullptr);
  ASSERT_TRUE(embedder->IsSubstitutedForTesting(1));

  embedder->CancelFrame();
  EXPECT_TRUE(embedder->GetViewsToCaptureForTesting().empty());
  EXPECT_FALSE(embedder->IsSubstitutedForTesting(1));
}

namespace {

class TestAndroidSurfaceFactory : public AndroidSurfaceFactory {
 public:
  using TestSurfaceProducer =
      std::function<std::unique_ptr<AndroidSurface>(void)>;
  explicit TestAndroidSurfaceFactory(TestSurfaceProducer&& surface_producer)
      : surface_producer_(std::move(surface_producer)) {}

  std::unique_ptr<AndroidSurface> CreateSurface() override {
    return surface_producer_();
  }

 private:
  TestSurfaceProducer surface_producer_;
};

class SurfaceMock : public Surface {
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
};

}  // namespace

TEST(AndroidExternalViewEmbedder2Capture,
     SubstitutedViewDoesNotClipEarlierViewOverlayLayer) {
  using ::testing::_;
  using ::testing::ByMove;
  using ::testing::Return;

  auto jni_mock = std::make_shared<JNIMock>();
  auto android_context =
      std::make_shared<AndroidContext>(AndroidRenderingAPI::kSoftware);
  ThreadHost thread_host("io.flutter.test.SubstitutedOverlayClip.",
                         ThreadHost::Type::kPlatform | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi | ThreadHost::Type::kRaster);
  TaskRunners task_runners(
      "test",
      thread_host.platform_thread->GetTaskRunner(),  // platform
      thread_host.raster_thread->GetTaskRunner(),    // raster
      thread_host.ui_thread->GetTaskRunner(),        // ui
      thread_host.io_thread->GetTaskRunner()         // io
  );

  const DlISize frame_size(100, 100);
  SurfaceFrame::FramebufferInfo framebuffer_info;
  sk_sp<SkSurface> overlay_sk_surface =
      SkSurfaces::Raster(SkImageInfo::MakeN32Premul(100, 100));

  auto surface_mock = std::make_unique<SurfaceMock>();
  EXPECT_CALL(*surface_mock, AcquireFrame(frame_size))
      .WillOnce(Return(ByMove(std::make_unique<SurfaceFrame>(
          overlay_sk_surface, framebuffer_info,
          [](const SurfaceFrame&, DlCanvas*) { return true; },
          [](const SurfaceFrame&) { return true; }, frame_size))));

  auto surface_factory = std::make_shared<TestAndroidSurfaceFactory>(
      fml::MakeCopyable([surface_mock = std::move(surface_mock)]() mutable {
        auto android_surface = std::make_unique<AndroidSurfaceMock>();
        EXPECT_CALL(*android_surface, IsValid()).WillRepeatedly(Return(true));
        EXPECT_CALL(*android_surface, SetNativeWindow(_, _))
            .WillRepeatedly(Return(true));
        EXPECT_CALL(*android_surface, CreateGPUSurface(_))
            .WillOnce(Return(ByMove(std::move(surface_mock))));
        return android_surface;
      }));

  fml::RefPtr<AndroidNativeWindow> window =
      fml::MakeRefCounted<AndroidNativeWindow>(nullptr);
  EXPECT_CALL(*jni_mock, MaybeResizeSurfaceView(100, 100));
  EXPECT_CALL(*jni_mock, createOverlaySurface2())
      .WillOnce(Return(
          ByMove(std::make_unique<PlatformViewAndroidJNI::OverlayMetadata>(
              0, window))));
  EXPECT_CALL(*jni_mock, showOverlaySurface2());
  EXPECT_CALL(*jni_mock, sealFrameTransactions());
  EXPECT_CALL(*jni_mock,
              onDisplayPlatformView2(1, 0, 0, 100, 100, 100, 100, _));
  EXPECT_CALL(*jni_mock, onDisplayPlatformView2(2, 0, 0, 50, 50, 50, 50, _));
  EXPECT_CALL(*jni_mock, swapTransaction());
  EXPECT_CALL(*jni_mock, onEndFrame2());

  auto delegate = std::make_shared<MockCaptureDelegate>();
  EXPECT_CALL(*delegate, RequestCapture(1, false, _)).WillOnce(Return(nullptr));
  EXPECT_CALL(*delegate, RequestCapture(2, true, _)).WillOnce(Return(nullptr));

  auto embedder = std::make_unique<AndroidExternalViewEmbedder2>(
      *android_context, jni_mock, surface_factory, task_runners, delegate);
  sk_sp<SkSurface> snapshot_surface =
      SkSurfaces::Raster(SkImageInfo::MakeN32Premul(50, 50));
  embedder->SetSnapshotSourceForTesting(
      2, std::make_shared<FakeSnapshotSource>(
             DlImageSkia::Make(snapshot_surface->makeImageSnapshot())));

  PostTaskSync(task_runners.GetRasterTaskRunner(), [&]() {
    embedder->PrepareFlutterView(frame_size, 1.0);

    // View 1 at (0, 0, 100, 100) is not under a filter (e.g. a SurfaceView).
    MutatorsStack mutators1;
    DlMatrix matrix1 = DlMatrix::MakeTranslation({0, 0});
    mutators1.PushTransform(matrix1);
    embedder->PrerollCompositeEmbeddedView(
        1, std::make_unique<EmbeddedViewParams>(matrix1, DlSize(100, 100),
                                                mutators1));

    // View 2 at (0, 0, 50, 50) is substituted under a backdrop filter at
    // (25, 25, 50, 50), and has a transparent capture at (10, 10).
    MutatorsStack mutators2;
    DlMatrix matrix2 = DlMatrix::MakeTranslation({0, 0});
    mutators2.PushTransform(matrix2);
    embedder->PrerollCompositeEmbeddedView(
        2, std::make_unique<EmbeddedViewParams>(matrix2, DlSize(50, 50),
                                                mutators2));
    embedder->PushFilterToVisitedPlatformViews(
        Blur(5), DlRect::MakeLTRB(25, 25, 50, 50));

    // A red Flutter widget painted between View 1 and View 2 covers (0, 0, 50,
    // 50) in View 1's overlay layer.
    DlCanvas* slice1 = embedder->CompositeEmbeddedView(1);
    DlPaint red_paint;
    red_paint.setColor(DlColor::kRed());
    red_paint.setDrawStyle(DlDrawStyle::kFill);
    slice1->DrawRect(DlRect::MakeXYWH(0, 0, 50, 50), red_paint);

    embedder->CompositeEmbeddedView(2);
    EXPECT_FALSE(embedder->IsSubstitutedForTesting(1));
    EXPECT_TRUE(embedder->IsSubstitutedForTesting(2));

    auto main_frame = std::make_unique<SurfaceFrame>(
        SkSurfaces::Null(100, 100), framebuffer_info,
        [](const SurfaceFrame&, DlCanvas*) { return true; },
        [](const SurfaceFrame&) { return true; }, frame_size);
    embedder->SubmitFlutterView(0, nullptr, nullptr, std::move(main_frame));
  });
  PostTaskSync(task_runners.GetPlatformTaskRunner(), []() {});

  // Because view 2 is substituted (not a live native view at z = 0), it must
  // not punch a DlClipOp::kDifference hole out of view 1's overlay layer at
  // (10, 10).
  SkBitmap bitmap;
  bitmap.allocN32Pixels(100, 100);
  ASSERT_TRUE(overlay_sk_surface->readPixels(bitmap, 0, 0));
  EXPECT_EQ(bitmap.getColor(10, 10), SK_ColorRED);

  EXPECT_CALL(*jni_mock, destroyOverlaySurface2());
  embedder->Teardown();
  embedder.reset();
}

TEST(AndroidExternalViewEmbedder2Capture,
     SubstitutesEarlierViewOverlappingFilteredLaterViewWithoutOverlayLayer) {
  using ::testing::_;
  using ::testing::ByMove;
  using ::testing::ElementsAre;
  using ::testing::Return;

  auto jni_mock = std::make_shared<JNIMock>();
  auto android_context =
      std::make_shared<AndroidContext>(AndroidRenderingAPI::kSoftware);
  ThreadHost thread_host("io.flutter.test.OverlappingFilteredLaterView.",
                         ThreadHost::Type::kPlatform | ThreadHost::Type::kIo |
                             ThreadHost::Type::kUi | ThreadHost::Type::kRaster);
  TaskRunners task_runners(
      "test",
      thread_host.platform_thread->GetTaskRunner(),  // platform
      thread_host.raster_thread->GetTaskRunner(),    // raster
      thread_host.ui_thread->GetTaskRunner(),        // ui
      thread_host.io_thread->GetTaskRunner()         // io
  );

  const DlISize frame_size(400, 400);
  SurfaceFrame::FramebufferInfo framebuffer_info;
  auto surface_mock = std::make_unique<SurfaceMock>();
  EXPECT_CALL(*surface_mock, AcquireFrame(_)).Times(0);

  auto surface_factory = std::make_shared<TestAndroidSurfaceFactory>(
      fml::MakeCopyable([surface_mock = std::move(surface_mock)]() mutable {
        auto android_surface = std::make_unique<AndroidSurfaceMock>();
        EXPECT_CALL(*android_surface, IsValid()).WillRepeatedly(Return(true));
        EXPECT_CALL(*android_surface, SetNativeWindow(_, _))
            .WillRepeatedly(Return(true));
        EXPECT_CALL(*android_surface, CreateGPUSurface(_))
            .WillOnce(Return(ByMove(std::move(surface_mock))));
        return android_surface;
      }));

  fml::RefPtr<AndroidNativeWindow> window =
      fml::MakeRefCounted<AndroidNativeWindow>(nullptr);
  EXPECT_CALL(*jni_mock, MaybeResizeSurfaceView(400, 400));
  EXPECT_CALL(*jni_mock, createOverlaySurface2())
      .WillOnce(Return(
          ByMove(std::make_unique<PlatformViewAndroidJNI::OverlayMetadata>(
              0, window))));
  EXPECT_CALL(*jni_mock, showOverlaySurface2()).Times(0);
  EXPECT_CALL(*jni_mock, sealFrameTransactions());
  EXPECT_CALL(*jni_mock,
              onDisplayPlatformView2(1, 20, 150, 180, 220, 180, 220, _));
  EXPECT_CALL(*jni_mock,
              onDisplayPlatformView2(2, 100, 150, 200, 220, 200, 220, _));
  EXPECT_CALL(*jni_mock, swapTransaction());
  EXPECT_CALL(*jni_mock, onEndFrame2());

  auto delegate = std::make_shared<MockCaptureDelegate>();
  EXPECT_CALL(*delegate, RequestCapture(1, true, _)).WillOnce(Return(nullptr));
  EXPECT_CALL(*delegate, RequestCapture(2, true, _)).WillOnce(Return(nullptr));

  auto embedder = std::make_unique<AndroidExternalViewEmbedder2>(
      *android_context, jni_mock, surface_factory, task_runners, delegate);
  sk_sp<SkSurface> snapshot_surface1 =
      SkSurfaces::Raster(SkImageInfo::MakeN32Premul(180, 220));
  sk_sp<SkSurface> snapshot_surface2 =
      SkSurfaces::Raster(SkImageInfo::MakeN32Premul(200, 220));
  embedder->SetSnapshotSourceForTesting(
      1, std::make_shared<FakeSnapshotSource>(
             DlImageSkia::Make(snapshot_surface1->makeImageSnapshot())));
  embedder->SetSnapshotSourceForTesting(
      2, std::make_shared<FakeSnapshotSource>(
             DlImageSkia::Make(snapshot_surface2->makeImageSnapshot())));

  PostTaskSync(task_runners.GetRasterTaskRunner(), [&]() {
    embedder->PrepareFlutterView(frame_size, 1.0);

    // View 1 at (20, 150, 200, 370) is behind View 2 at (100, 150, 300, 370),
    // overlapping at x = 100..200.
    MutatorsStack mutators1;
    DlMatrix matrix1 = DlMatrix::MakeTranslation({20, 150});
    mutators1.PushTransform(matrix1);
    embedder->PrerollCompositeEmbeddedView(
        1, std::make_unique<EmbeddedViewParams>(matrix1, DlSize(180, 220),
                                                mutators1));

    MutatorsStack mutators2;
    DlMatrix matrix2 = DlMatrix::MakeTranslation({100, 150});
    mutators2.PushTransform(matrix2);
    embedder->PrerollCompositeEmbeddedView(
        2, std::make_unique<EmbeddedViewParams>(matrix2, DlSize(200, 220),
                                                mutators2));

    // The backdrop filter at (210, 120, 360, 400) intersects View 2 only (not
    // View 1), but because View 2 overlaps earlier View 1, View 1 must also be
    // substituted so View 2's slice and filter are not hoisted to an overlay.
    auto blur = Blur(14);
    const DlRect filter_rect = DlRect::MakeLTRB(210, 120, 360, 400);
    embedder->PushFilterToVisitedPlatformViews(blur, filter_rect);
    EXPECT_THAT(embedder->GetViewsToCaptureForTesting(), ElementsAre(1, 2));

    embedder->CompositeEmbeddedView(1);
    DlCanvas* slice2 = embedder->CompositeEmbeddedView(2);
    PaintBackdropFilter(slice2, blur, filter_rect);
    EXPECT_TRUE(embedder->IsSubstitutedForTesting(1));
    EXPECT_TRUE(embedder->IsSubstitutedForTesting(2));

    auto main_frame = std::make_unique<SurfaceFrame>(
        SkSurfaces::Null(400, 400), framebuffer_info,
        [](const SurfaceFrame&, DlCanvas*) { return true; },
        [](const SurfaceFrame&) { return true; }, frame_size);
    embedder->SubmitFlutterView(0, nullptr, nullptr, std::move(main_frame));
  });
  PostTaskSync(task_runners.GetPlatformTaskRunner(), []() {});

  EXPECT_CALL(*jni_mock, destroyOverlaySurface2());
  embedder->Teardown();
  embedder.reset();
}

}  // namespace testing
}  // namespace flutter
