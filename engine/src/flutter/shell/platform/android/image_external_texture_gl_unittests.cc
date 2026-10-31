// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_gl_skia.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "flutter/display_list/image/dl_image_skia.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/impeller/toolkit/egl/context.h"
#include "flutter/impeller/toolkit/egl/display.h"
#include "flutter/impeller/toolkit/egl/surface.h"
#include "flutter/shell/platform/android/jni/mock_jni_env.h"
#include "third_party/skia/include/gpu/ganesh/GrDirectContext.h"
#include "third_party/skia/include/gpu/ganesh/SkImageGanesh.h"
#include "third_party/skia/include/gpu/ganesh/gl/GrGLBackendSurface.h"
#include "third_party/skia/include/gpu/ganesh/gl/GrGLDirectContext.h"
#include "third_party/skia/include/gpu/ganesh/gl/GrGLInterface.h"

namespace flutter {
namespace testing {
namespace {

class TestImageExternalTextureGL : public ImageExternalTextureGLSkia {
 public:
  TestImageExternalTextureGL()
      : ImageExternalTextureGLSkia(nullptr,
                                   1,
                                   {},
                                   nullptr,
                                   ImageLifecycle::kReset) {
    ON_CALL(mock_env_, GetObjectRefType(::testing::_))
        .WillByDefault(::testing::Return(JNILocalRefType));
    ON_CALL(mock_env_, NewLocalRef(::testing::_))
        .WillByDefault(::testing::ReturnArg<0>());
    ON_CALL(mock_env_, DeleteLocalRef(::testing::_))
        .WillByDefault(::testing::Return());
    MockJavaVM::GetInstance().SetJNIEnv(&mock_env_);
  }

  ~TestImageExternalTextureGL() override {
    MockJavaVM::GetInstance().SetJNIEnv(nullptr);
  }

  using ImageExternalTextureGL::AcquiredFrame;
  using ImageExternalTextureGL::CreateEGLImage;
  using ImageExternalTextureGL::Detach;
  using ImageExternalTextureGL::EglSyncProcs;
  using ImageExternalTextureGL::ProcessFrame;
  using ImageExternalTextureGL::SetEglSyncProcsForTest;
  using ImageExternalTextureGL::UpdateImage;
  using ImageExternalTextureGL::WaitAcquireFenceOnGL;

  const sk_sp<DlImage>& GetImage() const { return dl_image_; }

  void SetImportFailure(bool fail) { fail_import_ = fail; }

  void SetNextProcessFrameBuffer(AHardwareBuffer* ahb,
                                 bool owned_by_producer,
                                 std::function<void()> on_before_import = {}) {
    has_next_acquired_ = true;
    next_ahb_ = ahb;
    next_owned_by_producer_ = owned_by_producer;
    on_before_import_ = std::move(on_before_import);
  }

  void SetNextAcquiredFrame(AcquiredFrame frame,
                            std::function<void()> on_before_import = {}) {
    next_custom_frame_ = std::move(frame);
    on_before_import_ = std::move(on_before_import);
  }

  int GetCloseHardwareBufferCalls() const { return close_hb_calls_; }

  GLuint GetLastTexture() const { return last_texture_; }

  impeller::EGLImageKHRWithDisplay GetLastEGLImage() const {
    return last_egl_image_;
  }

 protected:
  AcquiredFrame AcquireLatestFrame() override {
    if (next_custom_frame_.has_value()) {
      AcquiredFrame frame = std::move(*next_custom_frame_);
      next_custom_frame_.reset();
      return frame;
    }
    return ImageExternalTextureGLSkia::AcquireLatestFrame();
  }

  PlatformViewAndroidJNI::AcquiredHardwareBuffer AcquireLatestHardwareBuffer()
      override {
    if (!has_next_acquired_) {
      return {};
    }
    has_next_acquired_ = false;
    return {fml::jni::ScopedJavaLocalRef<jobject>(
                &mock_env_, reinterpret_cast<jobject>(0x1)),
            next_owned_by_producer_};
  }

  AHardwareBuffer* AHardwareBufferFor(
      const fml::jni::JavaRef<jobject>& hardware_buffer) override {
    return next_ahb_;
  }

  void CloseHardwareBuffer(
      const fml::jni::JavaRef<jobject>& hardware_buffer) override {
    close_hb_calls_++;
  }

  void BindImageToTexture(const impeller::UniqueEGLImageKHR& image,
                          GLuint tex) override {
    ImageExternalTextureGLSkia::BindImageToTexture(image, tex);
    last_texture_ = tex;
    last_egl_image_ = image.get();
  }

  EGLImageKHR CreateEGLImageKHR(EGLDisplay display,
                                EGLClientBuffer buffer) override {
    if (on_before_import_) {
      auto cb = std::move(on_before_import_);
      on_before_import_ = {};
      cb();
    }
    if (fail_import_) {
      return EGL_NO_IMAGE_KHR;
    }
    return ImageExternalTextureGL::CreateEGLImageKHR(display, buffer);
  }

 private:
  ::testing::NiceMock<MockJNIEnv> mock_env_;
  bool fail_import_ = false;
  bool has_next_acquired_ = false;
  AHardwareBuffer* next_ahb_ = nullptr;
  bool next_owned_by_producer_ = true;
  std::optional<AcquiredFrame> next_custom_frame_;
  int close_hb_calls_ = 0;
  std::function<void()> on_before_import_;
  GLuint last_texture_ = 0;
  impeller::EGLImageKHRWithDisplay last_egl_image_ = {};
};

class ImageExternalTextureGLTest : public ::testing::Test {
 protected:
  void SetUp() override {
    MockJavaVM::GetInstance();
    ASSERT_TRUE(display_.IsValid());
    auto config = display_.ChooseConfig({});
    ASSERT_NE(config, nullptr);
    context_ = display_.CreateContext(*config, nullptr);
    ASSERT_NE(context_, nullptr);
    surface_ = display_.CreatePixelBufferSurface(*config, 1, 1);
    ASSERT_NE(surface_, nullptr);
    ASSERT_TRUE(context_->MakeCurrent(*surface_));
    gr_context_ = GrDirectContexts::MakeGL(GrGLMakeNativeInterface());
    ASSERT_NE(gr_context_, nullptr);

    auto original = CreateBuffer({16, 16});
    ASSERT_TRUE(original.IsValid());
    texture_ = std::make_unique<TestImageExternalTextureGL>();
    paint_context_.gr_context = gr_context_.get();
    texture_->UpdateImage(original.GetHandle(), bounds_, paint_context_);
    original_image_ = texture_->GetImage();
    ASSERT_NE(original_image_, nullptr);
    original_texture_ = GetTextureName(original_image_);
    ASSERT_TRUE(glIsTexture(original_texture_));
  }

  void TearDown() override {
    original_image_.reset();
    texture_.reset();
    gr_context_.reset();
    if (context_) {
      context_->ClearCurrent();
    }
  }

  static impeller::android::HardwareBuffer CreateBuffer(impeller::ISize size) {
    return impeller::android::HardwareBuffer(
        impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
            size));
  }

  GLuint GetTextureName(const sk_sp<DlImage>& image) {
    GrBackendTexture backend_texture;
    EXPECT_TRUE(SkImages::GetBackendTextureFromImage(
        image->asSkiaImage()->skia_image(), &backend_texture, false));
    GrGLTextureInfo info = {};
    EXPECT_TRUE(GrBackendTextures::GetGLTextureInfo(backend_texture, &info));
    return info.fID;
  }

  impeller::egl::Display display_;
  std::unique_ptr<impeller::egl::Context> context_;
  std::unique_ptr<impeller::egl::Surface> surface_;
  sk_sp<GrDirectContext> gr_context_;
  std::unique_ptr<TestImageExternalTextureGL> texture_;
  Texture::PaintContext paint_context_;
  const SkRect bounds_ = SkRect::MakeWH(32, 32);
  sk_sp<DlImage> original_image_;
  GLuint original_texture_ = 0;
};

TEST_F(ImageExternalTextureGLTest, RejectsFailedEGLImport) {
  auto buffer = CreateBuffer({16, 16});
  ASSERT_TRUE(buffer.IsValid());
  ASSERT_NE(eglGetCurrentDisplay(), EGL_NO_DISPLAY);

  texture_->SetImportFailure(true);
  EXPECT_FALSE(texture_->CreateEGLImage(buffer.GetHandle()).is_valid());
}

TEST_F(ImageExternalTextureGLTest, RetainsDisplayedTextureAfterFailedResize) {
  auto resized = CreateBuffer({32, 32});
  ASSERT_TRUE(resized.IsValid());

  texture_->SetImportFailure(true);
  for (size_t attempt = 0; attempt < 3; attempt++) {
    texture_->UpdateImage(resized.GetHandle(), bounds_, paint_context_);
    EXPECT_EQ(texture_->GetImage(), original_image_);
    ASSERT_TRUE(glIsTexture(original_texture_));
  }

  texture_->SetImportFailure(false);
  texture_->UpdateImage(resized.GetHandle(), bounds_, paint_context_);
  ASSERT_NE(texture_->GetImage(), nullptr);
  EXPECT_NE(texture_->GetImage(), original_image_);
  EXPECT_TRUE(glIsTexture(GetTextureName(texture_->GetImage())));
  // Other users of the previous image must also keep its texture alive.
  EXPECT_TRUE(glIsTexture(original_texture_));
  original_image_.reset();
  gr_context_->flushAndSubmit(GrSyncCpu::kYes);
  EXPECT_FALSE(glIsTexture(original_texture_));
}

TEST_F(ImageExternalTextureGLTest,
       RetainsDisplayedTextureAfterFailedSkiaImport) {
  auto resized = CreateBuffer({32, 32});
  ASSERT_TRUE(resized.IsValid());

  // EGL import succeeds, but Skia cannot wrap the texture without a context.
  paint_context_.gr_context = nullptr;
  for (size_t attempt = 0; attempt < 3; attempt++) {
    SCOPED_TRACE(attempt);
    texture_->UpdateImage(resized.GetHandle(), bounds_, paint_context_);
    EXPECT_EQ(texture_->GetImage(), original_image_);
    EXPECT_TRUE(glIsTexture(original_texture_));
    ASSERT_NE(texture_->GetLastTexture(), 0u);
    ASSERT_NE(texture_->GetLastTexture(), original_texture_);
    EXPECT_FALSE(glIsTexture(texture_->GetLastTexture()));
    const auto failed_image = texture_->GetLastEGLImage();
    ASSERT_NE(failed_image.image, EGL_NO_IMAGE_KHR);
    // Destroying an already released EGL image must fail.
    EXPECT_FALSE(eglDestroyImageKHR(failed_image.display, failed_image.image));
    EXPECT_EQ(eglGetError(), EGL_BAD_PARAMETER);
  }

  paint_context_.gr_context = gr_context_.get();
  texture_->UpdateImage(resized.GetHandle(), bounds_, paint_context_);
  ASSERT_NE(texture_->GetImage(), nullptr);
  EXPECT_NE(texture_->GetImage(), original_image_);
  original_image_.reset();
  gr_context_->flushAndSubmit(GrSyncCpu::kYes);
  EXPECT_FALSE(glIsTexture(original_texture_));
}

TEST_F(ImageExternalTextureGLTest,
       RetainsDisplayedTextureAfterExpirationAndFailedImport) {
  auto replacement = CreateBuffer({16, 16});
  ASSERT_TRUE(replacement.IsValid());
  if (!replacement.GetSystemUniqueID().has_value()) {
    GTEST_SKIP() << "Hardware buffer caching requires Android API 31.";
  }
  const auto* original_image = original_image_.get();
  original_image_.reset();
  // Only the cache and the displayed image now own the original image.
  EXPECT_FALSE(texture_->GetImage()->unique());

  texture_->SetImportFailure(true);
  for (size_t frame = 1; frame < 2 * kImageReaderSwapchainSize; frame++) {
    texture_->UpdateImage(replacement.GetHandle(), bounds_, paint_context_);
    ASSERT_EQ(texture_->GetImage().get(), original_image);
    EXPECT_FALSE(texture_->GetImage()->unique());
    ASSERT_TRUE(glIsTexture(original_texture_));
  }

  texture_->UpdateImage(replacement.GetHandle(), bounds_, paint_context_);
  ASSERT_EQ(texture_->GetImage().get(), original_image);
  // Expiration drops the cache's reference, but the displayed image stays
  // valid.
  EXPECT_TRUE(texture_->GetImage()->unique());
  EXPECT_TRUE(glIsTexture(original_texture_));

  texture_->SetImportFailure(false);
  texture_->UpdateImage(replacement.GetHandle(), bounds_, paint_context_);
  ASSERT_NE(texture_->GetImage(), nullptr);
  EXPECT_TRUE(glIsTexture(GetTextureName(texture_->GetImage())));
  gr_context_->flushAndSubmit(GrSyncCpu::kYes);
  EXPECT_FALSE(glIsTexture(original_texture_));
}

TEST_F(ImageExternalTextureGLTest,
       IgnoresNullHardwareBufferAndRetainsDisplayedTexture) {
  texture_->UpdateImage(nullptr, bounds_, paint_context_);
  EXPECT_EQ(texture_->GetImage(), original_image_);
  EXPECT_TRUE(glIsTexture(original_texture_));

  // Simulate producer closing the Java HardwareBuffer before AHardwareBufferFor
  // resolves it (returning nullptr), both for owned_by_producer=true and false.
  texture_->SetNextProcessFrameBuffer(nullptr, /*owned_by_producer=*/true);
  texture_->ProcessFrame(paint_context_, bounds_);
  EXPECT_EQ(texture_->GetImage(), original_image_);
  EXPECT_EQ(texture_->GetCloseHardwareBufferCalls(), 0);

  texture_->SetNextProcessFrameBuffer(nullptr, /*owned_by_producer=*/false);
  texture_->ProcessFrame(paint_context_, bounds_);
  EXPECT_EQ(texture_->GetImage(), original_image_);
  EXPECT_EQ(texture_->GetCloseHardwareBufferCalls(), 1);
}

TEST_F(ImageExternalTextureGLTest,
       ProcessFrameRetainsNativeBufferWhenProducerReleasesBeforeImport) {
  std::optional<impeller::android::HardwareBuffer> producer_buffer(
      std::in_place,
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {32, 32}));
  ASSERT_TRUE(producer_buffer->IsValid());
  AHardwareBuffer* raw_handle = producer_buffer->GetHandle();

  bool released_producer_ref = false;
  texture_->SetNextProcessFrameBuffer(raw_handle, /*owned_by_producer=*/true,
                                      [&]() {
                                        // Simulate producer.release() /
                                        // PerImage.close() dropping the
                                        // producer's HardwareBuffer reference
                                        // on the main thread after ProcessFrame
                                        // acquired its native AHardwareBuffer
                                        // ref but before EGL import runs.
                                        producer_buffer.reset();
                                        released_producer_ref = true;
                                      });
  texture_->ProcessFrame(paint_context_, bounds_);
  EXPECT_TRUE(released_producer_ref);
  ASSERT_NE(texture_->GetImage(), nullptr);
  EXPECT_NE(texture_->GetImage(), original_image_);
  EXPECT_TRUE(glIsTexture(GetTextureName(texture_->GetImage())));
}

TEST_F(ImageExternalTextureGLTest,
       WaitAcquireFenceOnGLReclaimsAndClosesFdOnSyncFailureOrNoDisplay) {
  // 1. With current EGL display: a pipe FD is not a kernel sync_file, so
  // eglCreateSyncKHR (if supported) fails with EGL_NO_SYNC_KHR, reclaims raw_fd
  // into UniqueFD, falls back to WaitAcquireFenceFdBounded, and closes the FD.
  int pipe_fds[2] = {-1, -1};
  ASSERT_EQ(pipe(pipe_fds), 0);
  const char byte = 1;
  ASSERT_EQ(write(pipe_fds[1], &byte, 1), 1);
  close(pipe_fds[1]);
  const int read_fd = pipe_fds[0];
  texture_->WaitAcquireFenceOnGL(fml::UniqueFD(read_fd));
  errno = 0;
  EXPECT_EQ(fcntl(read_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);

  // 2. Without a current EGL display: directly takes the bounded poll fallback.
  ASSERT_TRUE(context_->ClearCurrent());
  int pipe_fds_no_display[2] = {-1, -1};
  ASSERT_EQ(pipe(pipe_fds_no_display), 0);
  ASSERT_EQ(write(pipe_fds_no_display[1], &byte, 1), 1);
  close(pipe_fds_no_display[1]);
  const int read_fd_no_display = pipe_fds_no_display[0];
  texture_->WaitAcquireFenceOnGL(fml::UniqueFD(read_fd_no_display));
  errno = 0;
  EXPECT_EQ(fcntl(read_fd_no_display, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  ASSERT_TRUE(context_->MakeCurrent(*surface_));
}

TEST_F(ImageExternalTextureGLTest,
       ProcessFrameConsumesCallerOwnedAhbRefAndAcquireFenceFromNdkProducer) {
  std::optional<impeller::android::HardwareBuffer> producer_buffer(
      std::in_place,
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {32, 32}));
  ASSERT_TRUE(producer_buffer->IsValid());
  AHardwareBuffer* raw_handle = producer_buffer->GetHandle();

  // Simulate NdkSurfaceProducer::AcquireLatestFrame() acquiring the
  // caller-owned reference under its mutex before returning AcquiredFrame.
  const auto& proc_table = impeller::android::GetProcTable();
  ASSERT_TRUE(proc_table.AHardwareBuffer_acquire.IsAvailable());
  proc_table.AHardwareBuffer_acquire(raw_handle);

  int pipe_fds[2] = {-1, -1};
  ASSERT_EQ(pipe(pipe_fds), 0);
  const char byte = 1;
  ASSERT_EQ(write(pipe_fds[1], &byte, 1), 1);
  close(pipe_fds[1]);
  const int fence_fd = pipe_fds[0];

  TestImageExternalTextureGL::AcquiredFrame frame;
  frame.ahardware_buffer = raw_handle;
  frame.acquire_fence_fd = fml::UniqueFD(fence_fd);
  frame.owned_by_producer = true;
  frame.caller_owns_ahb_ref = true;

  bool released_producer_ref = false;
  texture_->SetNextAcquiredFrame(std::move(frame), [&]() {
    // Drop the producer's own reference before EGL import runs; the caller
    // reference acquired by NdkSurfaceProducer must keep raw_handle alive.
    producer_buffer.reset();
    released_producer_ref = true;
  });

  texture_->ProcessFrame(paint_context_, bounds_);
  EXPECT_TRUE(released_producer_ref);
  errno = 0;
  EXPECT_EQ(fcntl(fence_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  ASSERT_NE(texture_->GetImage(), nullptr);
  EXPECT_NE(texture_->GetImage(), original_image_);
  EXPECT_TRUE(glIsTexture(GetTextureName(texture_->GetImage())));
}

TEST_F(ImageExternalTextureGLTest,
       ProcessFrameSkipsUpdateAndRetainsPreviousImageWhenEglWaitSyncFails) {
  impeller::android::HardwareBuffer new_buffer(
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {32, 32}));
  ASSERT_TRUE(new_buffer.IsValid());
  const auto& proc_table = impeller::android::GetProcTable();
  ASSERT_TRUE(proc_table.AHardwareBuffer_acquire.IsAvailable());
  proc_table.AHardwareBuffer_acquire(new_buffer.GetHandle());

  int pipe_fds[2] = {-1, -1};
  ASSERT_EQ(pipe(pipe_fds), 0);
  close(pipe_fds[1]);
  const int fence_fd = pipe_fds[0];

  static bool s_destroy_called = false;
  s_destroy_called = false;

  TestImageExternalTextureGL::EglSyncProcs sync_procs;
  sync_procs.force_native_fence_supported = true;
  sync_procs.create_sync = [](EGLDisplay, EGLenum,
                              const EGLint* attrib_list) -> EGLSyncKHR {
    // Per EGL_ANDROID_native_fence_sync spec, eglCreateSyncKHR takes ownership
    // of the fence FD on success.
    if (attrib_list != nullptr &&
        attrib_list[0] == EGL_SYNC_NATIVE_FENCE_FD_ANDROID &&
        attrib_list[1] >= 0) {
      ::close(attrib_list[1]);
    }
    return reinterpret_cast<EGLSyncKHR>(0x1);
  };
  sync_procs.wait_sync = [](EGLDisplay, EGLSyncKHR, EGLint) -> EGLint {
    return EGL_FALSE;
  };
  sync_procs.destroy_sync = [](EGLDisplay, EGLSyncKHR) -> EGLBoolean {
    s_destroy_called = true;
    return EGL_TRUE;
  };
  texture_->SetEglSyncProcsForTest(sync_procs);

  TestImageExternalTextureGL::AcquiredFrame frame;
  frame.ahardware_buffer = new_buffer.GetHandle();
  frame.acquire_fence_fd = fml::UniqueFD(fence_fd);
  frame.owned_by_producer = true;
  frame.caller_owns_ahb_ref = true;
  texture_->SetNextAcquiredFrame(std::move(frame));

  texture_->ProcessFrame(paint_context_, bounds_);
  texture_->SetEglSyncProcsForTest(std::nullopt);

  EXPECT_TRUE(s_destroy_called);
  errno = 0;
  EXPECT_EQ(fcntl(fence_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  // Because eglWaitSyncKHR returned EGL_FALSE, ProcessFrame must skip sampling
  // the new buffer and retain original_image_.
  EXPECT_EQ(texture_->GetImage(), original_image_);
  EXPECT_TRUE(glIsTexture(original_texture_));
}

TEST_F(ImageExternalTextureGLTest,
       CpuPollTimeoutRetainsPendingFrameAndDeliversFinalUpdateWhenSignaled) {
  auto count_open_fds = []() -> int {
    DIR* dir = ::opendir("/proc/self/fd");
    if (dir == nullptr) {
      return -1;
    }
    int count = 0;
    while (::readdir(dir) != nullptr) {
      count++;
    }
    ::closedir(dir);
    return count - 1;
  };

  impeller::android::HardwareBuffer final_buffer(
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {32, 32}));
  ASSERT_TRUE(final_buffer.IsValid());
  const auto& proc_table = impeller::android::GetProcTable();
  ASSERT_TRUE(proc_table.AHardwareBuffer_acquire.IsAvailable());

  const int fd_baseline = count_open_fds();
  ASSERT_GT(fd_baseline, 0);

  proc_table.AHardwareBuffer_acquire(final_buffer.GetHandle());

  int pipe_fds[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe_fds), 0);
  const int read_fence_fd = pipe_fds[0];
  fml::UniqueFD write_end(pipe_fds[1]);

  fml::AutoResetWaitableEvent ready_event;
  texture_->SetFenceWaitTimeoutMsForTest(200);
  texture_->SetPendingFrameReadyCallbackForTest(
      [&ready_event]() { ready_event.Signal(); });

  TestImageExternalTextureGL::AcquiredFrame frame;
  frame.ahardware_buffer = final_buffer.GetHandle();
  frame.acquire_fence_fd = fml::UniqueFD(read_fence_fd);
  frame.owned_by_producer = true;
  frame.caller_owns_ahb_ref = true;
  texture_->SetNextAcquiredFrame(std::move(frame));

  // First ProcessFrame hits bounded poll timeout: keeps original_image_ active,
  // leaves read_fence_fd open inside pending_frame_, and starts async waiter.
  texture_->ProcessFrame(paint_context_, bounds_);
  EXPECT_EQ(texture_->GetImage(), original_image_);
  EXPECT_TRUE(glIsTexture(original_texture_));
  EXPECT_TRUE(texture_->HasPendingFrameForTest());
  EXPECT_NE(::fcntl(read_fence_fd, F_GETFD), -1);

  // Second ProcessFrame while read_fence_fd is STILL unsignaled must return
  // immediately (< 50ms, well under the 200ms timeout) without re-blocking the
  // raster thread, while keeping pending_frame_ retained and original_image_
  // displayed.
  const auto repaint_start = std::chrono::steady_clock::now();
  texture_->ProcessFrame(paint_context_, bounds_);
  const auto repaint_elapsed_ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now() - repaint_start)
          .count();
  EXPECT_LT(repaint_elapsed_ms, 50)
      << "Second ProcessFrame on GL while pending fence is unsignaled must not "
         "re-block for the 200ms timeout!";
  EXPECT_EQ(texture_->GetImage(), original_image_);
  EXPECT_TRUE(texture_->HasPendingFrameForTest());

  // Signal the write end WITHOUT producing any second image.
  const uint8_t byte = 1;
  ASSERT_EQ(::write(write_end.get(), &byte, 1), 1);

  // Async readiness waiter must wake and fire the notification callback.
  EXPECT_FALSE(
      ready_event.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(500)));

  // Subsequent ProcessFrame retries pending_frame_ with no new producer frame,
  // observes kSignaled, closes read_fence_fd, and imports the final image.
  texture_->ProcessFrame(paint_context_, bounds_);
  EXPECT_FALSE(texture_->HasPendingFrameForTest());
  errno = 0;
  EXPECT_EQ(::fcntl(read_fence_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  ASSERT_NE(texture_->GetImage(), nullptr);
  EXPECT_NE(texture_->GetImage(), original_image_);
  EXPECT_TRUE(glIsTexture(GetTextureName(texture_->GetImage())));

  write_end.reset();
  texture_->SetPendingFrameReadyCallbackForTest({});
  EXPECT_EQ(count_open_fds(), fd_baseline);
}

TEST_F(ImageExternalTextureGLTest,
       CpuPollTimeoutSupersededByNewerFrameAndCanceledOnDetachWithZeroLeaks) {
  auto count_open_fds = []() -> int {
    DIR* dir = ::opendir("/proc/self/fd");
    if (dir == nullptr) {
      return -1;
    }
    int count = 0;
    while (::readdir(dir) != nullptr) {
      count++;
    }
    ::closedir(dir);
    return count - 1;
  };

  impeller::android::HardwareBuffer buffer1(
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {32, 32}));
  impeller::android::HardwareBuffer buffer2(
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {32, 32}));
  ASSERT_TRUE(buffer1.IsValid());
  ASSERT_TRUE(buffer2.IsValid());
  const auto& proc_table = impeller::android::GetProcTable();
  ASSERT_TRUE(proc_table.AHardwareBuffer_acquire.IsAvailable());

  const int fd_baseline = count_open_fds();
  ASSERT_GT(fd_baseline, 0);

  // 1. Frame 1 times out and is held in pending_frame_.
  proc_table.AHardwareBuffer_acquire(buffer1.GetHandle());
  int pipe1[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe1), 0);
  const int fence1_fd = pipe1[0];
  fml::UniqueFD write1(pipe1[1]);

  texture_->SetFenceWaitTimeoutMsForTest(5);
  TestImageExternalTextureGL::AcquiredFrame frame1;
  frame1.ahardware_buffer = buffer1.GetHandle();
  frame1.acquire_fence_fd = fml::UniqueFD(fence1_fd);
  frame1.owned_by_producer = true;
  frame1.caller_owns_ahb_ref = true;
  texture_->SetNextAcquiredFrame(std::move(frame1));
  texture_->ProcessFrame(paint_context_, bounds_);
  EXPECT_TRUE(texture_->HasPendingFrameForTest());
  EXPECT_NE(::fcntl(fence1_fd, F_GETFD), -1);

  // 2. Newer signaled Frame 2 supersedes Frame 1 before Frame 1 ever signals.
  proc_table.AHardwareBuffer_acquire(buffer2.GetHandle());
  int pipe2[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe2), 0);
  const uint8_t byte = 1;
  ASSERT_EQ(::write(pipe2[1], &byte, 1), 1);
  ::close(pipe2[1]);
  const int fence2_fd = pipe2[0];

  TestImageExternalTextureGL::AcquiredFrame frame2;
  frame2.ahardware_buffer = buffer2.GetHandle();
  frame2.acquire_fence_fd = fml::UniqueFD(fence2_fd);
  frame2.owned_by_producer = true;
  frame2.caller_owns_ahb_ref = true;
  texture_->SetNextAcquiredFrame(std::move(frame2));
  texture_->ProcessFrame(paint_context_, bounds_);

  // Both fence1_fd (superseded) and fence2_fd (imported) must be closed!
  EXPECT_FALSE(texture_->HasPendingFrameForTest());
  errno = 0;
  EXPECT_EQ(::fcntl(fence1_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  errno = 0;
  EXPECT_EQ(::fcntl(fence2_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  EXPECT_NE(texture_->GetImage(), original_image_);
  write1.reset();
  EXPECT_EQ(count_open_fds(), fd_baseline);

  // 3. Verify Detach() immediately cancels an active unsignaled waiter and
  // closes the retained fence FD without waiting for the producer to write.
  proc_table.AHardwareBuffer_acquire(buffer1.GetHandle());
  int pipe3[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe3), 0);
  const int fence3_fd = pipe3[0];
  fml::UniqueFD write3(pipe3[1]);

  TestImageExternalTextureGL::AcquiredFrame frame3;
  frame3.ahardware_buffer = buffer1.GetHandle();
  frame3.acquire_fence_fd = fml::UniqueFD(fence3_fd);
  frame3.owned_by_producer = true;
  frame3.caller_owns_ahb_ref = true;
  texture_->SetNextAcquiredFrame(std::move(frame3));
  texture_->ProcessFrame(paint_context_, bounds_);
  EXPECT_TRUE(texture_->HasPendingFrameForTest());

  texture_->Detach();
  EXPECT_FALSE(texture_->HasPendingFrameForTest());
  errno = 0;
  EXPECT_EQ(::fcntl(fence3_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  write3.reset();
  EXPECT_EQ(count_open_fds(), fd_baseline);
}

TEST_F(ImageExternalTextureGLTest,
       ProcessFrameRetiresFailedImportFramesWithoutEvictingDisplayedImage) {
  auto buf_x = CreateBuffer({16, 16});
  auto buf_a = CreateBuffer({16, 16});
  auto buf_b = CreateBuffer({16, 16});
  auto buf_c = CreateBuffer({16, 16});
  ASSERT_TRUE(buf_x.IsValid() && buf_a.IsValid() && buf_b.IsValid() &&
              buf_c.IsValid());

  const auto default_procs = NdkSurfaceProducer::GetProcs();
  NdkSurfaceProducer::Procs mock_procs = default_procs;

  static AImageReader_ImageListener s_listener{};
  static AImage* s_next_image = nullptr;
  static AHardwareBuffer* s_next_ahb = nullptr;
  static std::vector<AImage*> s_deleted_images;

  s_listener = {};
  s_next_image = nullptr;
  s_next_ahb = nullptr;
  s_deleted_images.clear();

  mock_procs.AImageReader_newWithUsage =
      [](int32_t, int32_t, int32_t, uint64_t, int32_t,
         AImageReader** reader) -> media_status_t {
    *reader = reinterpret_cast<AImageReader*>(0x9501);
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_getWindow =
      [](AImageReader* r, ANativeWindow** window) -> media_status_t {
    *window = reinterpret_cast<ANativeWindow*>(r);
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_setImageListener =
      [](AImageReader*,
         AImageReader_ImageListener* listener) -> media_status_t {
    if (listener != nullptr) {
      s_listener = *listener;
    }
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_delete = [](AImageReader*) {};
  mock_procs.AImageReader_acquireNextImageAsync =
      [](AImageReader*, AImage** image, int* acquireFenceFd) -> media_status_t {
    if (s_next_image != nullptr) {
      *image = s_next_image;
      *acquireFenceFd = -1;
      s_next_image = nullptr;
      return AMEDIA_OK;
    }
    *image = nullptr;
    *acquireFenceFd = -1;
    return AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
  };
  mock_procs.AImage_getHardwareBuffer =
      [](const AImage*, AHardwareBuffer** buffer) -> media_status_t {
    *buffer = s_next_ahb;
    return AMEDIA_OK;
  };
  mock_procs.AImage_deleteAsync = [](AImage* image, int releaseFenceFd) {
    s_deleted_images.push_back(image);
    if (releaseFenceFd >= 0) {
      ::close(releaseFenceFd);
    }
  };
  mock_procs.AImage_delete = [](AImage* image) {
    s_deleted_images.push_back(image);
  };

  NdkSurfaceProducer::SetProcsForTest(mock_procs);

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/95, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);
  texture_->SetNdkSurfaceProducer(producer);

  auto submit_and_process = [&](AImage* img, AHardwareBuffer* ahb) {
    s_next_image = img;
    s_next_ahb = ahb;
    s_listener.onImageAvailable(s_listener.context,
                                reinterpret_cast<AImageReader*>(0x9501));
    texture_->ProcessFrame(paint_context_, bounds_);
  };

  // 1. Frame X imports cleanly and becomes the active displayed image.
  AImage* const img_x = reinterpret_cast<AImage*>(0x9601);
  submit_and_process(img_x, buf_x.GetHandle());
  const auto displayed_x = texture_->GetImage();
  ASSERT_NE(displayed_x, nullptr);
  EXPECT_EQ(producer->GetDisplayedImagesCountForTest(), 1);
  EXPECT_TRUE(s_deleted_images.empty());

  // 2. Three consecutive frames A, B, C fail EGL import. Each failed frame
  //    must be retired immediately as unimported while Frame X stays pinned in
  //    `kDisplayed` and is NEVER evicted!
  texture_->SetImportFailure(true);
  AImage* const img_a = reinterpret_cast<AImage*>(0x9602);
  AImage* const img_b = reinterpret_cast<AImage*>(0x9603);
  AImage* const img_c = reinterpret_cast<AImage*>(0x9604);
  submit_and_process(img_a, buf_a.GetHandle());
  submit_and_process(img_b, buf_b.GetHandle());
  submit_and_process(img_c, buf_c.GetHandle());

  EXPECT_EQ(texture_->GetImage(), displayed_x);
  EXPECT_EQ(producer->GetDisplayedImagesCountForTest(), 1);
  ASSERT_EQ(s_deleted_images.size(), 3u);
  EXPECT_EQ(s_deleted_images[0], img_a);
  EXPECT_EQ(s_deleted_images[1], img_b);
  EXPECT_EQ(s_deleted_images[2], img_c);
  for (AImage* deleted : s_deleted_images) {
    EXPECT_NE(deleted, img_x);
  }

  texture_->SetImportFailure(false);
  texture_->SetNdkSurfaceProducer(nullptr);
  producer->Release();
  producer.reset();
  NdkSurfaceProducer::SetProcsForTest(std::nullopt);
  ASSERT_EQ(s_deleted_images.size(), 4u);
  EXPECT_EQ(s_deleted_images[3], img_x);
}

}  // namespace
}  // namespace testing
}  // namespace flutter
