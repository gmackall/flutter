// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_vk_impeller.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <memory>
#include <vector>

#include "flutter/display_list/image/dl_image_skia.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/thread.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"
#include "gtest/gtest.h"
#include "third_party/skia/include/core/SkBitmap.h"
#include "third_party/skia/include/core/SkImage.h"
#include "third_party/skia/include/core/SkImageInfo.h"

namespace flutter {
namespace testing {
namespace {

int CountOpenFdsForVkTest() {
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
}

sk_sp<DlImage> MakeSyntheticDlImage(int width, int height) {
  SkBitmap bitmap;
  bitmap.allocPixels(SkImageInfo::MakeN32Premul(width, height));
  bitmap.setImmutable();
  return DlImageSkia::Make(bitmap.asImage());
}

class TestImageExternalTextureVKImpeller
    : public ImageExternalTextureVKImpeller {
 public:
  TestImageExternalTextureVKImpeller()
      : ImageExternalTextureVKImpeller(nullptr,
                                       /*id=*/77,
                                       {},
                                       nullptr,
                                       ImageLifecycle::kReset) {}

  using ImageExternalTextureVKImpeller::Attach;
  using ImageExternalTextureVKImpeller::Detach;
  using ImageExternalTextureVKImpeller::ProcessFrame;

  const sk_sp<DlImage>& GetImage() const { return dl_image_; }

  void SetInitialImage(sk_sp<DlImage> initial) {
    dl_image_ = std::move(initial);
  }

  int GetImportCalls() const { return import_calls_; }

 protected:
  sk_sp<DlImage> ImportHardwareBuffer(
      AHardwareBuffer* latest_hardware_buffer,
      const AHardwareBuffer_Desc& hb_desc) override {
    import_calls_++;
    return MakeSyntheticDlImage(static_cast<int>(hb_desc.width),
                                static_cast<int>(hb_desc.height));
  }

 private:
  int import_calls_ = 0;
};

TEST(ImageExternalTextureVKImpellerTest,
     RetainsTimedOutFinalFrameAndImportsAfterAsyncFenceSignalWithZeroLeaks) {
  // Allocate two real AHardwareBuffers so HardwareBuffer::Describe and
  // GetSystemUniqueID exercise the exact production code paths inside
  // ImageExternalTextureVKImpeller::ProcessFrame.
  impeller::android::HardwareBuffer initial_buffer(
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {16, 16}));
  impeller::android::HardwareBuffer final_buffer(
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {32, 32}));
  if (!initial_buffer.IsValid() || !final_buffer.IsValid()) {
    GTEST_SKIP() << "AHardwareBuffer allocation requires Android API 29+.";
  }

  const auto default_procs = NdkSurfaceProducer::GetProcs();
  NdkSurfaceProducer::Procs mock_procs = default_procs;

  static AImageReader_ImageListener s_listener{};
  static AImage* s_next_image = nullptr;
  static AHardwareBuffer* s_next_ahb = nullptr;
  static int s_next_fence_fd = -1;
  static std::vector<AImage*> s_deleted_images;
  static std::atomic<int> s_ahb_net_refs{0};

  s_listener = {};
  s_next_image = nullptr;
  s_next_ahb = nullptr;
  s_next_fence_fd = -1;
  s_deleted_images.clear();
  s_ahb_net_refs.store(0);

  mock_procs.AImageReader_newWithUsage =
      [](int32_t, int32_t, int32_t, uint64_t, int32_t,
         AImageReader** reader) -> media_status_t {
    *reader = reinterpret_cast<AImageReader*>(0x7001);
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
    } else {
      s_listener = {};
    }
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_delete = [](AImageReader*) {};
  mock_procs.AImageReader_acquireNextImageAsync =
      [](AImageReader*, AImage** image, int* acquireFenceFd) -> media_status_t {
    if (s_next_image != nullptr) {
      *image = s_next_image;
      *acquireFenceFd = s_next_fence_fd;
      s_next_image = nullptr;
      s_next_fence_fd = -1;
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
  mock_procs.AHardwareBuffer_acquire = [](AHardwareBuffer* buffer) {
    s_ahb_net_refs.fetch_add(1);
    if (impeller::android::GetProcTable()
            .AHardwareBuffer_acquire.IsAvailable()) {
      impeller::android::GetProcTable().AHardwareBuffer_acquire(buffer);
    }
  };
  mock_procs.AHardwareBuffer_release = [](AHardwareBuffer* buffer) {
    s_ahb_net_refs.fetch_sub(1);
    if (impeller::android::GetProcTable()
            .AHardwareBuffer_release.IsAvailable()) {
      impeller::android::GetProcTable().AHardwareBuffer_release(buffer);
    }
  };

  NdkSurfaceProducer::SetProcsForTest(mock_procs);

  auto platform_thread =
      std::make_unique<fml::Thread>("vk_pending_fence_test_platform_thread");
  auto platform_runner = platform_thread->GetTaskRunner();
  std::shared_ptr<NdkSurfaceProducer> producer;
  {
    fml::AutoResetWaitableEvent created_on_platform;
    platform_runner->PostTask([&]() {
      producer = std::make_shared<NdkSurfaceProducer>(
          /*texture_id=*/77, fml::WeakPtr<PlatformViewAndroid>(),
          platform_runner, /*is_displaying_flutter_ui=*/true);
      created_on_platform.Signal();
    });
    created_on_platform.Wait();
  }

  const int fd_baseline = CountOpenFdsForVkTest();
  ASSERT_GT(fd_baseline, 0);

  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);
  ASSERT_NE(s_listener.onImageAvailable, nullptr);

  auto texture = std::make_unique<TestImageExternalTextureVKImpeller>();
  auto previous_image = MakeSyntheticDlImage(16, 16);
  texture->SetInitialImage(previous_image);
  texture->SetNdkSurfaceProducer(producer);
  texture->SetFenceWaitTimeoutMsForTest(200);

  fml::AutoResetWaitableEvent ready_event;
  std::atomic<bool> notified_on_platform_runner{false};
  producer->SetFrameScheduledCallbackForTest([&](int64_t texture_id) {
    if (texture_id == 77) {
      notified_on_platform_runner.store(
          platform_runner->RunsTasksOnCurrentThread(),
          std::memory_order_release);
      ready_event.Signal();
    }
  });

  // Create a real Linux pipe as the acquire fence: unread read-end blocks
  // poll(POLLIN) with 0 (kTimedOut); writing 1 byte signals POLLIN.
  int pipe_fds[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe_fds), 0);
  const int fence_read_fd = pipe_fds[0];
  fml::UniqueFD fence_write_fd(pipe_fds[1]);

  // Producer submits its single FINAL image with the unsignaled acquire fence
  // and never produces another image.
  AImage* const final_aimage = reinterpret_cast<AImage*>(0x8801);
  s_next_image = final_aimage;
  s_next_ahb = final_buffer.GetHandle();
  s_next_fence_fd = fence_read_fd;
  s_listener.onImageAvailable(s_listener.context,
                              reinterpret_cast<AImageReader*>(0x7001));

  Texture::PaintContext paint_ctx{};
  const SkRect bounds = SkRect::MakeWH(32, 32);

  // 1. First ProcessFrame hits kTimedOut after the configured bounded wait:
  //    - Keeps previous_image displayed and valid
  //    - Retains pending_frame_ with open fence_read_fd and owned AHB ref (1)
  //    - Arms async readiness waiter
  texture->ProcessFrame(paint_ctx, bounds);
  EXPECT_EQ(texture->GetImage(), previous_image);
  EXPECT_EQ(texture->GetImportCalls(), 0);
  EXPECT_TRUE(texture->HasPendingFrameForTest());
  EXPECT_NE(::fcntl(fence_read_fd, F_GETFD), -1);
  EXPECT_EQ(s_ahb_net_refs.load(), 1);
  // The dequeued AImage remains pinned in producer->dequeued_images_ so the
  // BufferQueue slot cannot be overwritten while waiting.
  EXPECT_EQ(producer->GetPendingDequeuedImagesForTest(), 1);
  EXPECT_TRUE(s_deleted_images.empty());

  // 2. Repeated paint while fence is still unsignaled MUST NOT re-block for
  //    the 200ms bounded timeout: it must return in < 50ms, keep pending_frame_
  //    retained, and continue displaying previous_image.
  const auto repaint_start = std::chrono::steady_clock::now();
  texture->ProcessFrame(paint_ctx, bounds);
  const auto repaint_elapsed_ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now() - repaint_start)
          .count();
  EXPECT_LT(repaint_elapsed_ms, 50)
      << "Second ProcessFrame while pending fence is unsignaled must not "
         "re-block for the 200ms timeout!";
  EXPECT_EQ(texture->GetImage(), previous_image);
  EXPECT_TRUE(texture->HasPendingFrameForTest());
  EXPECT_EQ(s_ahb_net_refs.load(), 1);

  // 3. Now signal the fence write-end WITHOUT producing any new image.
  const uint8_t wake = 1;
  ASSERT_EQ(::write(fence_write_fd.get(), &wake, 1), 1);

  // 4. Async readiness waiter wakes immediately and posts onto the real
  //    `platform_task_runner_` via `producer->NotifyPendingFenceSignaled()`.
  EXPECT_FALSE(
      ready_event.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(500)));
  EXPECT_TRUE(notified_on_platform_runner.load(std::memory_order_acquire));

  // 5. Next ProcessFrame retries pending_frame_, sees kSignaled, closes
  //    fence_read_fd once, imports the final buffer, selects the new DlImage,
  //    and releases the caller-owned AHB ref once!
  texture->ProcessFrame(paint_ctx, bounds);
  EXPECT_FALSE(texture->HasPendingFrameForTest());
  EXPECT_EQ(texture->GetImportCalls(), 1);
  ASSERT_NE(texture->GetImage(), nullptr);
  EXPECT_NE(texture->GetImage(), previous_image);
  EXPECT_EQ(s_ahb_net_refs.load(), 0);
  errno = 0;
  EXPECT_EQ(::fcntl(fence_read_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);

  // 6. Release producer and texture: verify the single AImage is deleted
  //    exactly once and total open FDs equal the exact pre-test baseline.
  fence_write_fd.reset();
  producer->SetFrameScheduledCallbackForTest({});
  texture.reset();
  producer->Release();
  producer.reset();
  const int fd_after_with_thread = CountOpenFdsForVkTest();
  EXPECT_EQ(fd_after_with_thread, fd_baseline);
  platform_runner = nullptr;
  platform_thread.reset();
  NdkSurfaceProducer::SetProcsForTest(std::nullopt);

  ASSERT_EQ(s_deleted_images.size(), 1u);
  EXPECT_EQ(s_deleted_images[0], final_aimage);
  EXPECT_EQ(s_ahb_net_refs.load(), 0);
}

TEST(ImageExternalTextureVKImpellerTest,
     TimedOutFrameSupersededByNewerFrameAndCanceledOnDetachWithZeroLeaks) {
  impeller::android::HardwareBuffer buffer1(
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {32, 32}));
  impeller::android::HardwareBuffer buffer2(
      impeller::android::HardwareBufferDescriptor::MakeForSwapchainImage(
          {64, 64}));
  if (!buffer1.IsValid() || !buffer2.IsValid()) {
    GTEST_SKIP() << "AHardwareBuffer allocation requires Android API 29+.";
  }

  const auto default_procs = NdkSurfaceProducer::GetProcs();
  NdkSurfaceProducer::Procs mock_procs = default_procs;

  static AImageReader_ImageListener s_listener{};
  static AImage* s_next_image = nullptr;
  static AHardwareBuffer* s_next_ahb = nullptr;
  static int s_next_fence_fd = -1;
  static std::atomic<int> s_ahb_net_refs{0};
  static std::vector<AImage*> s_deleted_images;

  s_listener = {};
  s_next_image = nullptr;
  s_next_ahb = nullptr;
  s_next_fence_fd = -1;
  s_ahb_net_refs.store(0);
  s_deleted_images.clear();

  mock_procs.AImageReader_newWithUsage =
      [](int32_t, int32_t, int32_t, uint64_t, int32_t,
         AImageReader** reader) -> media_status_t {
    *reader = reinterpret_cast<AImageReader*>(0x7002);
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
    } else {
      s_listener = {};
    }
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_delete = [](AImageReader*) {};
  mock_procs.AImageReader_acquireNextImageAsync =
      [](AImageReader*, AImage** image, int* acquireFenceFd) -> media_status_t {
    if (s_next_image != nullptr) {
      *image = s_next_image;
      *acquireFenceFd = s_next_fence_fd;
      s_next_image = nullptr;
      s_next_fence_fd = -1;
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
  mock_procs.AHardwareBuffer_acquire = [](AHardwareBuffer* buffer) {
    s_ahb_net_refs.fetch_add(1);
    if (impeller::android::GetProcTable()
            .AHardwareBuffer_acquire.IsAvailable()) {
      impeller::android::GetProcTable().AHardwareBuffer_acquire(buffer);
    }
  };
  mock_procs.AHardwareBuffer_release = [](AHardwareBuffer* buffer) {
    s_ahb_net_refs.fetch_sub(1);
    if (impeller::android::GetProcTable()
            .AHardwareBuffer_release.IsAvailable()) {
      impeller::android::GetProcTable().AHardwareBuffer_release(buffer);
    }
  };

  NdkSurfaceProducer::SetProcsForTest(mock_procs);

  const int fd_baseline = CountOpenFdsForVkTest();
  ASSERT_GT(fd_baseline, 0);

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/78, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);

  auto texture = std::make_unique<TestImageExternalTextureVKImpeller>();
  texture->SetInitialImage(MakeSyntheticDlImage(16, 16));
  texture->SetNdkSurfaceProducer(producer);
  texture->SetFenceWaitTimeoutMsForTest(5);

  Texture::PaintContext paint_ctx{};
  const SkRect bounds = SkRect::MakeWH(64, 64);

  // 1. Submit unsignaled Frame 1 -> times out, retained in pending_frame_.
  int pipe1[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe1), 0);
  const int fence1_fd = pipe1[0];
  fml::UniqueFD write1(pipe1[1]);

  s_next_image = reinterpret_cast<AImage*>(0x8901);
  s_next_ahb = buffer1.GetHandle();
  s_next_fence_fd = fence1_fd;
  s_listener.onImageAvailable(s_listener.context,
                              reinterpret_cast<AImageReader*>(0x7002));
  texture->ProcessFrame(paint_ctx, bounds);
  EXPECT_TRUE(texture->HasPendingFrameForTest());
  EXPECT_EQ(s_ahb_net_refs.load(), 1);

  // 2. Submit signaled Frame 2 -> supersedes Frame 1 immediately!
  int pipe2[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe2), 0);
  const uint8_t b = 1;
  ASSERT_EQ(::write(pipe2[1], &b, 1), 1);
  ::close(pipe2[1]);
  const int fence2_fd = pipe2[0];

  s_next_image = reinterpret_cast<AImage*>(0x8902);
  s_next_ahb = buffer2.GetHandle();
  s_next_fence_fd = fence2_fd;
  s_listener.onImageAvailable(s_listener.context,
                              reinterpret_cast<AImageReader*>(0x7002));
  texture->ProcessFrame(paint_ctx, bounds);

  EXPECT_FALSE(texture->HasPendingFrameForTest());
  EXPECT_EQ(s_ahb_net_refs.load(), 0);
  errno = 0;
  EXPECT_EQ(::fcntl(fence1_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  errno = 0;
  EXPECT_EQ(::fcntl(fence2_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);
  write1.reset();
  EXPECT_EQ(CountOpenFdsForVkTest(), fd_baseline);

  // 3. Submit unsignaled Frame 3 and verify Detach() cancels waiter immediately
  //    without blocking and releases all FDs & AHB refs.
  int pipe3[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe3), 0);
  const int fence3_fd = pipe3[0];
  fml::UniqueFD write3(pipe3[1]);

  s_next_image = reinterpret_cast<AImage*>(0x8903);
  s_next_ahb = buffer1.GetHandle();
  s_next_fence_fd = fence3_fd;
  s_listener.onImageAvailable(s_listener.context,
                              reinterpret_cast<AImageReader*>(0x7002));
  texture->ProcessFrame(paint_ctx, bounds);
  EXPECT_TRUE(texture->HasPendingFrameForTest());
  EXPECT_EQ(s_ahb_net_refs.load(), 1);

  const auto start = std::chrono::steady_clock::now();
  texture->Detach();
  const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                              std::chrono::steady_clock::now() - start)
                              .count();
  EXPECT_LT(elapsed_ms, 200);
  EXPECT_FALSE(texture->HasPendingFrameForTest());
  EXPECT_EQ(s_ahb_net_refs.load(), 0);
  errno = 0;
  EXPECT_EQ(::fcntl(fence3_fd, F_GETFD), -1);
  EXPECT_EQ(errno, EBADF);

  write3.reset();
  texture.reset();
  producer->Release();
  producer.reset();
  NdkSurfaceProducer::SetProcsForTest(std::nullopt);

  EXPECT_EQ(s_deleted_images.size(), 3u);
  EXPECT_EQ(s_ahb_net_refs.load(), 0);
  EXPECT_EQ(CountOpenFdsForVkTest(), fd_baseline);
}

}  // namespace
}  // namespace testing
}  // namespace flutter
