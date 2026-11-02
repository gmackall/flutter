// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture.h"
#include "flutter/shell/platform/android/ndk_surface_producer.h"

#include <android/native_window.h>
#include <dirent.h>
#include <sys/eventfd.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <future>
#include <thread>
#include <vector>

#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/thread.h"
#include "gtest/gtest.h"

namespace flutter {
namespace testing {

namespace {

int CountOpenFds() {
  DIR* dir = ::opendir("/proc/self/fd");
  if (dir == nullptr) {
    return -1;
  }
  int count = 0;
  while (::readdir(dir) != nullptr) {
    count++;
  }
  ::closedir(dir);
  // Subtract 1 for the directory FD opened by opendir() itself.
  return count - 1;
}

bool PostCpuFrameToWindow(ANativeWindow* window,
                          int32_t width,
                          int32_t height) {
  if (window == nullptr) {
    return false;
  }
  ANativeWindow_setBuffersGeometry(window, width, height,
                                   AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
  ANativeWindow_Buffer buffer{};
  if (ANativeWindow_lock(window, &buffer, nullptr) != 0) {
    return false;
  }
  if (ANativeWindow_unlockAndPost(window) != 0) {
    return false;
  }
  return true;
}

}  // namespace

TEST(NdkSurfaceProducerTest, RealAImageReader100FrameDrainLoopHasZeroFdLeaks) {
  if (!NdkSurfaceProducer::IsAvailable()) {
    GTEST_SKIP() << "NdkSurfaceProducer requires API 29+ libmediandk symbols.";
  }

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/1, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  producer->SetSize(64, 64);
  ANativeWindow* window = producer->GetNativeWindowForTest(false);
  ASSERT_NE(window, nullptr);

  // Warm up a small multi-frame burst so any one-time gralloc/ashmem/binder
  // thread-pool FDs are initialized before we snapshot the baseline FD count.
  for (int warm_i = 0; warm_i < 4; ++warm_i) {
    ASSERT_TRUE(PostCpuFrameToWindow(window, 64, 64));
    for (int attempt = 0;
         attempt < 50 && producer->GetNumQueuedImagesForTest() == 0;
         ++attempt) {
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    auto warm = producer->AcquireLatestFrame();
    if (warm.ahardware_buffer != nullptr) {
      ASSERT_EQ(
          NdkSurfaceProducer::WaitAcquireFenceFdBounded(warm.acquire_fence_fd),
          NdkSurfaceProducer::FenceWaitResult::kSignaled);
      NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
          warm.ahardware_buffer);
    }
  }

  // Pump 100 frames (including multi-frame bursts before each acquire) and
  // verify that after releasing the producer, the open FD count matches the
  // pre-test baseline.
  const int fd_baseline = CountOpenFds();
  ASSERT_GT(fd_baseline, 0);

  int acquired_count = 0;
  for (int i = 0; i < 100; ++i) {
    ASSERT_TRUE(PostCpuFrameToWindow(window, 64, 64));
    if (i % 2 == 1) {
      for (int wait_ms = 0;
           wait_ms < 50 && producer->GetNumQueuedImagesForTest() == 0;
           ++wait_ms) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
      }
      auto frame = producer->AcquireLatestFrame();
      if (frame.ahardware_buffer != nullptr) {
        acquired_count++;
        EXPECT_EQ(NdkSurfaceProducer::WaitAcquireFenceFdBounded(
                      frame.acquire_fence_fd),
                  NdkSurfaceProducer::FenceWaitResult::kSignaled);
        NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
            frame.ahardware_buffer);
      }
    }
  }
  EXPECT_GT(acquired_count, 0);

  producer->Release();
  producer.reset();

  const int fd_after = CountOpenFds();
  // Allow at most baseline FD count (any one-time warmup prior to baseline is
  // already included; zero leaked sync_file or AImage FDs across 100 frames).
  EXPECT_LE(fd_after, fd_baseline);
}

TEST(NdkSurfaceProducerTest,
     DrainLoopDeletesSupersededImagesEachWithOwnFenceFd) {
  const auto default_procs = NdkSurfaceProducer::GetProcs();
  NdkSurfaceProducer::Procs mock_procs = default_procs;

  static AImageReader_ImageListener s_registered_listener{};
  static std::vector<AImage*> s_mock_images;
  static std::vector<int> s_mock_fences;
  static size_t s_acquire_index = 0;
  static std::vector<std::pair<AImage*, int>> s_deleted_async;
  static int s_ahb_live_refs = 0;

  s_registered_listener = {};
  s_mock_images = {
      reinterpret_cast<AImage*>(0x1001),
      reinterpret_cast<AImage*>(0x1002),
      reinterpret_cast<AImage*>(0x1003),
      reinterpret_cast<AImage*>(0x1004),
  };
  s_mock_fences.clear();
  for (int i = 0; i < 4; ++i) {
    int efd = ::eventfd(1, EFD_CLOEXEC | EFD_NONBLOCK);
    ASSERT_GE(efd, 0);
    s_mock_fences.push_back(efd);
  }
  s_acquire_index = 0;
  s_deleted_async.clear();
  s_ahb_live_refs = 1;

  mock_procs.AImageReader_newWithUsage =
      [](int32_t, int32_t, int32_t, uint64_t, int32_t,
         AImageReader** reader) -> media_status_t {
    *reader = reinterpret_cast<AImageReader*>(0x2001);
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_getWindow =
      [](AImageReader*, ANativeWindow** window) -> media_status_t {
    *window = reinterpret_cast<ANativeWindow*>(0x3001);
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_setImageListener =
      [](AImageReader*,
         AImageReader_ImageListener* listener) -> media_status_t {
    if (listener != nullptr) {
      s_registered_listener = *listener;
    } else {
      s_registered_listener = {};
    }
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_delete = [](AImageReader*) {};
  mock_procs.AImageReader_acquireNextImageAsync =
      [](AImageReader*, AImage** image, int* acquireFenceFd) -> media_status_t {
    if (s_acquire_index < s_mock_images.size()) {
      *image = s_mock_images[s_acquire_index];
      *acquireFenceFd = s_mock_fences[s_acquire_index];
      s_acquire_index++;
      return AMEDIA_OK;
    }
    *image = nullptr;
    *acquireFenceFd = -1;
    return AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
  };
  mock_procs.AImage_getHardwareBuffer =
      [](const AImage* image, AHardwareBuffer** buffer) -> media_status_t {
    *buffer = reinterpret_cast<AHardwareBuffer*>(const_cast<AImage*>(image));
    return AMEDIA_OK;
  };
  mock_procs.AImage_deleteAsync = [](AImage* image, int releaseFenceFd) {
    s_deleted_async.emplace_back(image, releaseFenceFd);
    if (releaseFenceFd >= 0) {
      ::close(releaseFenceFd);
    }
  };
  mock_procs.AImage_delete = [](AImage* image) {
    s_deleted_async.emplace_back(image, -1);
  };
  mock_procs.AHardwareBuffer_acquire = [](AHardwareBuffer*) {
    s_ahb_live_refs++;
  };
  mock_procs.AHardwareBuffer_release = [](AHardwareBuffer*) {
    s_ahb_live_refs--;
  };

  const int fd_before = CountOpenFds();
  // Note: fd_before includes the 4 s_mock_fences created above; after the test
  // closes all 4 fences, CountOpenFds() must equal fd_before - 4.
  NdkSurfaceProducer::SetProcsForTest(mock_procs);

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/2, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);
  ASSERT_NE(s_registered_listener.onImageAvailable, nullptr);

  // Trigger OnImageAvailable: it must drain all 4 queued images, calling
  // AImage_deleteAsync on the first 3 superseded images with their own fence
  // FDs, and enqueueing only the 4th image.
  s_registered_listener.onImageAvailable(
      s_registered_listener.context, reinterpret_cast<AImageReader*>(0x2001));

  ASSERT_EQ(s_deleted_async.size(), 3u);
  EXPECT_EQ(s_deleted_async[0].first, s_mock_images[0]);
  EXPECT_EQ(s_deleted_async[0].second, s_mock_fences[0]);
  EXPECT_EQ(s_deleted_async[1].first, s_mock_images[1]);
  EXPECT_EQ(s_deleted_async[1].second, s_mock_fences[1]);
  EXPECT_EQ(s_deleted_async[2].first, s_mock_images[2]);
  EXPECT_EQ(s_deleted_async[2].second, s_mock_fences[2]);

  auto acquired = producer->AcquireLatestFrame();
  EXPECT_EQ(acquired.ahardware_buffer,
            reinterpret_cast<AHardwareBuffer*>(s_mock_images[3]));
  EXPECT_EQ(acquired.acquire_fence_fd.get(), s_mock_fences[3]);
  // AcquireLatestFrame() acquired a caller-owned AHardwareBuffer ref under
  // mutex_ (s_ahb_live_refs: 1 -> 2).
  EXPECT_EQ(s_ahb_live_refs, 2);

  // Now simulate Release() on the platform thread while the raster thread still
  // holds `acquired.ahardware_buffer` before ProcessFrame finishes.
  producer->Release();
  ASSERT_EQ(s_deleted_async.size(), 4u);
  EXPECT_EQ(s_deleted_async[3].first, s_mock_images[3]);
  // Even though AImage_deleteAsync ran on the 4th image, the caller-owned
  // AHardwareBuffer reference keeps s_ahb_live_refs >= 1.
  EXPECT_EQ(s_ahb_live_refs, 2);

  EXPECT_EQ(
      NdkSurfaceProducer::WaitAcquireFenceFdBounded(acquired.acquire_fence_fd),
      NdkSurfaceProducer::FenceWaitResult::kSignaled);
  NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
      acquired.ahardware_buffer);
  EXPECT_EQ(s_ahb_live_refs, 1);

  producer.reset();
  NdkSurfaceProducer::SetProcsForTest(std::nullopt);

  const int fd_after = CountOpenFds();
  EXPECT_EQ(fd_after, fd_before - 4);
}

TEST(NdkSurfaceProducerTest,
     SetSizeRotatesReaderAndPrunesOldReaderAfterNewFrameDequeued) {
  const auto default_procs = NdkSurfaceProducer::GetProcs();
  NdkSurfaceProducer::Procs mock_procs = default_procs;

  static intptr_t s_next_reader_id = 0x4000;
  static AImageReader_ImageListener s_latest_listener{};
  static AImageReader* s_latest_reader = nullptr;
  static int s_deleted_readers = 0;
  static int s_next_image_id = 0x5000;

  s_next_reader_id = 0x4000;
  s_latest_listener = {};
  s_latest_reader = nullptr;
  s_deleted_readers = 0;
  s_next_image_id = 0x5000;

  mock_procs.AImageReader_newWithUsage =
      [](int32_t, int32_t, int32_t, uint64_t, int32_t,
         AImageReader** reader) -> media_status_t {
    *reader = reinterpret_cast<AImageReader*>(++s_next_reader_id);
    s_latest_reader = *reader;
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
      s_latest_listener = *listener;
    }
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_delete = [](AImageReader*) { s_deleted_readers++; };
  mock_procs.AImageReader_acquireNextImageAsync =
      [](AImageReader*, AImage** image, int* acquireFenceFd) -> media_status_t {
    if (s_next_image_id > 0) {
      *image = reinterpret_cast<AImage*>(s_next_image_id);
      s_next_image_id = -s_next_image_id;
      *acquireFenceFd = -1;
      return AMEDIA_OK;
    }
    s_next_image_id = -s_next_image_id + 1;
    *image = nullptr;
    *acquireFenceFd = -1;
    return AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
  };
  mock_procs.AImage_getHardwareBuffer =
      [](const AImage* image, AHardwareBuffer** buffer) -> media_status_t {
    *buffer = reinterpret_cast<AHardwareBuffer*>(const_cast<AImage*>(image));
    return AMEDIA_OK;
  };
  mock_procs.AImage_deleteAsync = [](AImage*, int fd) {
    if (fd >= 0) {
      ::close(fd);
    }
  };
  mock_procs.AHardwareBuffer_acquire = [](AHardwareBuffer*) {};
  mock_procs.AHardwareBuffer_release = [](AHardwareBuffer*) {};

  NdkSurfaceProducer::SetProcsForTest(mock_procs);

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/3, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  producer->SetSize(100, 100);
  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);
  EXPECT_EQ(producer->GetNumImageReadersForTest(), 1);

  // Rotate size to 200x200 and request new window: reader count becomes 2.
  producer->SetSize(200, 200);
  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);
  EXPECT_EQ(producer->GetNumImageReadersForTest(), 2);

  // Enqueue and acquire a frame on the new reader: old reader is now prunable
  // and moved to async_readers_to_shutdown_.
  s_latest_listener.onImageAvailable(s_latest_listener.context,
                                     s_latest_reader);
  auto frame = producer->AcquireLatestFrame();
  EXPECT_NE(frame.ahardware_buffer, nullptr);
  EXPECT_EQ(producer->GetNumImageReadersForTest(), 1);

  // Next OnImageAvailable drains async_readers_to_shutdown_ and deletes the
  // old reader.
  s_latest_listener.onImageAvailable(s_latest_listener.context,
                                     s_latest_reader);
  EXPECT_EQ(s_deleted_readers, 1);

  producer->Release();
  EXPECT_EQ(s_deleted_readers, 2);
  producer.reset();
  NdkSurfaceProducer::SetProcsForTest(std::nullopt);
}

TEST(NdkSurfaceProducerTest,
     TrimAndReleaseDuringInFlightCallbackDefersShutdownAndWakesRendezvous) {
  const auto default_procs = NdkSurfaceProducer::GetProcs();
  NdkSurfaceProducer::Procs mock_procs = default_procs;

  static AImageReader_ImageListener s_listener{};
  static fml::AutoResetWaitableEvent s_entered_acquire;
  static fml::AutoResetWaitableEvent s_allow_finish_acquire;
  static std::atomic<int> s_deleted_readers{0};

  s_listener = {};
  s_deleted_readers.store(0);

  mock_procs.AImageReader_newWithUsage =
      [](int32_t, int32_t, int32_t, uint64_t, int32_t,
         AImageReader** reader) -> media_status_t {
    *reader = reinterpret_cast<AImageReader*>(0x6001);
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
  mock_procs.AImageReader_delete = [](AImageReader*) {
    s_deleted_readers.fetch_add(1);
  };
  mock_procs.AImageReader_acquireNextImageAsync =
      [](AImageReader*, AImage** image, int* acquireFenceFd) -> media_status_t {
    s_entered_acquire.Signal();
    s_allow_finish_acquire.Wait();
    *image = nullptr;
    *acquireFenceFd = -1;
    return AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
  };
  mock_procs.AImage_deleteAsync = [](AImage*, int fd) {
    if (fd >= 0) {
      ::close(fd);
    }
  };
  mock_procs.AHardwareBuffer_acquire = [](AHardwareBuffer*) {};
  mock_procs.AHardwareBuffer_release = [](AHardwareBuffer*) {};

  NdkSurfaceProducer::SetProcsForTest(mock_procs);

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/4, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);

  std::thread callback_thread([&]() {
    s_listener.onImageAvailable(s_listener.context,
                                reinterpret_cast<AImageReader*>(0x6001));
  });
  s_entered_acquire.Wait();

  // Start a raster thread that blocks in AcquireLatestFrame()'s rendezvous cv_.
  fml::AutoResetWaitableEvent raster_started;
  fml::AutoResetWaitableEvent raster_finished;
  std::thread raster_thread([&]() {
    raster_started.Signal();
    auto frame = producer->AcquireLatestFrame();
    EXPECT_EQ(frame.ahardware_buffer, nullptr);
    raster_finished.Signal();
  });
  raster_started.Wait();
  std::this_thread::sleep_for(std::chrono::microseconds(200));

  // Call Release() while the callback is in flight and the raster thread is
  // waiting on cv_. The raster thread must wake immediately, and the reader
  // must NOT be deleted until the in-flight callback finishes.
  producer->Release();
  EXPECT_FALSE(
      raster_finished.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(400)));
  EXPECT_EQ(s_deleted_readers.load(), 0);

  s_allow_finish_acquire.Signal();
  callback_thread.join();
  raster_thread.join();
  EXPECT_EQ(s_deleted_readers.load(), 1);

  producer.reset();
  NdkSurfaceProducer::SetProcsForTest(std::nullopt);
}

TEST(NdkSurfaceProducerTest,
     WaitAcquireFenceFdBoundedPreservesOpenFdOnTimeoutAndClosesOnSignal) {
  const int fd_before = CountOpenFds();

  // 1. Signaled eventfd returns kSignaled immediately and closes the FD.
  int signaled_fd = ::eventfd(1, EFD_CLOEXEC | EFD_NONBLOCK);
  ASSERT_GE(signaled_fd, 0);
  fml::UniqueFD owned_signaled(signaled_fd);
  EXPECT_EQ(NdkSurfaceProducer::WaitAcquireFenceFdBounded(owned_signaled,
                                                          /*timeout_ms=*/5),
            NdkSurfaceProducer::FenceWaitResult::kSignaled);
  EXPECT_FALSE(owned_signaled.is_valid());

  // 2. Unsignaled pipe read-end returns kTimedOut after 5ms and leaves the FD
  // OPEN so the caller can retain it in `pending_frame_` until signaled.
  int pipe_fds[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe_fds), 0);
  fml::UniqueFD owned_read(pipe_fds[0]);
  fml::UniqueFD owned_write(pipe_fds[1]);
  EXPECT_EQ(NdkSurfaceProducer::WaitAcquireFenceFdBounded(owned_read,
                                                          /*timeout_ms=*/5),
            NdkSurfaceProducer::FenceWaitResult::kTimedOut);
  EXPECT_TRUE(owned_read.is_valid());
  EXPECT_EQ(owned_read.get(), pipe_fds[0]);

  // Once the producer signals the write end, retrying the SAME preserved FD
  // returns kSignaled and closes owned_read cleanly.
  const uint8_t byte = 1;
  ASSERT_EQ(::write(owned_write.get(), &byte, 1), 1);
  EXPECT_EQ(NdkSurfaceProducer::WaitAcquireFenceFdBounded(owned_read,
                                                          /*timeout_ms=*/5),
            NdkSurfaceProducer::FenceWaitResult::kSignaled);
  EXPECT_FALSE(owned_read.is_valid());
  owned_write.reset();

  const int fd_after = CountOpenFds();
  EXPECT_EQ(fd_after, fd_before);
}

TEST(NdkSurfaceProducerTest,
     WaitAcquireFenceFdBoundedRejectsPollErrorReventsAndBoundsEintrRetries) {
  const int fd_before = CountOpenFds();

  // 1. poll() returning > 0 with POLLERR or POLLNVAL (without valid POLLIN)
  // must return kError and close the FD immediately.
  for (short err_revent :
       {static_cast<short>(POLLERR), static_cast<short>(POLLNVAL),
        static_cast<short>(POLLERR | POLLIN)}) {
    int efd = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    ASSERT_GE(efd, 0);
    fml::UniqueFD owned_efd(efd);
    NdkSurfaceProducer::SetPollFnForTest(
        [err_revent](struct pollfd* pfd, nfds_t, int) -> int {
          pfd->revents = err_revent;
          return 1;
        });
    EXPECT_EQ(NdkSurfaceProducer::WaitAcquireFenceFdBounded(owned_efd,
                                                            /*timeout_ms=*/10),
              NdkSurfaceProducer::FenceWaitResult::kError);
    EXPECT_FALSE(owned_efd.is_valid());
  }

  // 2. Repeated EINTR retries must recompute remaining timeout against an
  // absolute deadline instead of resetting the full timeout_ms on each retry.
  int eintr_fd = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  ASSERT_GE(eintr_fd, 0);
  fml::UniqueFD owned_eintr(eintr_fd);
  std::vector<int> observed_timeouts;
  NdkSurfaceProducer::SetPollFnForTest(
      [&observed_timeouts](struct pollfd*, nfds_t, int timeout_ms) -> int {
        observed_timeouts.push_back(timeout_ms);
        std::this_thread::sleep_for(std::chrono::milliseconds(6));
        errno = EINTR;
        return -1;
      });
  EXPECT_EQ(NdkSurfaceProducer::WaitAcquireFenceFdBounded(owned_eintr,
                                                          /*timeout_ms=*/15),
            NdkSurfaceProducer::FenceWaitResult::kTimedOut);
  NdkSurfaceProducer::SetPollFnForTest(nullptr);
  EXPECT_TRUE(owned_eintr.is_valid());
  owned_eintr.reset();

  ASSERT_GE(observed_timeouts.size(), 2u);
  EXPECT_LT(observed_timeouts.back(), observed_timeouts.front());

  const int fd_after = CountOpenFds();
  EXPECT_EQ(fd_after, fd_before);
}

TEST(
    NdkSurfaceProducerTest,
    DisplayedImagePinnedAcrossTimedOutSupersedesWhileUnimportedRetiresWithFence) {
  const auto default_procs = NdkSurfaceProducer::GetProcs();
  NdkSurfaceProducer::Procs mock_procs = default_procs;

  static AImageReader_ImageListener s_listener{};
  static AImage* s_next_image = nullptr;
  static int s_next_fence = -1;
  static std::vector<std::pair<AImage*, int>> s_deleted_async;
  static int s_ahb_live_refs = 0;

  s_listener = {};
  s_next_image = nullptr;
  s_next_fence = -1;
  s_deleted_async.clear();
  s_ahb_live_refs = 0;

  mock_procs.AImageReader_newWithUsage =
      [](int32_t, int32_t, int32_t, uint64_t, int32_t,
         AImageReader** reader) -> media_status_t {
    *reader = reinterpret_cast<AImageReader*>(0x9001);
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
      *acquireFenceFd = s_next_fence;
      s_next_image = nullptr;
      s_next_fence = -1;
      return AMEDIA_OK;
    }
    *image = nullptr;
    *acquireFenceFd = -1;
    return AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
  };
  mock_procs.AImage_getHardwareBuffer =
      [](const AImage* image, AHardwareBuffer** buffer) -> media_status_t {
    *buffer = reinterpret_cast<AHardwareBuffer*>(const_cast<AImage*>(image));
    return AMEDIA_OK;
  };
  mock_procs.AImage_deleteAsync = [](AImage* image, int releaseFenceFd) {
    s_deleted_async.emplace_back(image, releaseFenceFd);
    if (releaseFenceFd >= 0) {
      ::close(releaseFenceFd);
    }
  };
  mock_procs.AImage_delete = [](AImage* image) {
    s_deleted_async.emplace_back(image, -1);
  };
  mock_procs.AHardwareBuffer_acquire = [](AHardwareBuffer*) {
    s_ahb_live_refs++;
  };
  mock_procs.AHardwareBuffer_release = [](AHardwareBuffer*) {
    s_ahb_live_refs--;
  };

  NdkSurfaceProducer::SetProcsForTest(mock_procs);

  const int fd_baseline = CountOpenFds();
  ASSERT_GT(fd_baseline, 0);

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/91, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);

  auto enqueue_and_acquire = [&](AImage* img, int fence_fd) {
    s_next_image = img;
    s_next_fence = fence_fd;
    s_listener.onImageAvailable(s_listener.context,
                                reinterpret_cast<AImageReader*>(0x9001));
    return producer->AcquireLatestFrame();
  };

  // 1. Frame X is acquired, its fence signals, and it is displayed
  // (`dl_image_`).
  AImage* const img_x = reinterpret_cast<AImage*>(0x9101);
  auto frame_x = enqueue_and_acquire(img_x, -1);
  ASSERT_EQ(frame_x.ahardware_buffer,
            reinterpret_cast<AHardwareBuffer*>(img_x));
  producer->NotifyFrameDisplayed(frame_x.ahardware_buffer);
  NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
      frame_x.ahardware_buffer);

  // 2. Frame Y arrives with an unsignaled acquire fence and times out.
  AImage* const img_y = reinterpret_cast<AImage*>(0x9102);
  int fence_y = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  ASSERT_GE(fence_y, 0);
  auto frame_y = enqueue_and_acquire(img_y, fence_y);
  ASSERT_EQ(frame_y.ahardware_buffer,
            reinterpret_cast<AHardwareBuffer*>(img_y));
  EXPECT_TRUE(s_deleted_async.empty());

  // 3. Frame Z arrives and supersedes unimported Frame Y, and Frame Z ALSO
  //    times out. Frame Y must be immediately deleted with `fence_y` as its
  //    release fence, while displayed Frame X MUST NOT be deleted!
  AImage* const img_z = reinterpret_cast<AImage*>(0x9103);
  int fence_z = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  ASSERT_GE(fence_z, 0);
  auto frame_z = enqueue_and_acquire(img_z, fence_z);
  ASSERT_EQ(frame_z.ahardware_buffer,
            reinterpret_cast<AHardwareBuffer*>(img_z));
  producer->RetireUnimportedFrame(frame_y.ahardware_buffer,
                                  std::move(frame_y.acquire_fence_fd));
  NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
      frame_y.ahardware_buffer);

  ASSERT_EQ(s_deleted_async.size(), 1u);
  EXPECT_EQ(s_deleted_async[0].first, img_y);
  EXPECT_EQ(s_deleted_async[0].second, fence_y);
  for (const auto& deleted : s_deleted_async) {
    EXPECT_NE(deleted.first, img_x)
        << "Displayed image X must never be evicted on consecutive timeouts!";
  }

  // 4. Even a 4th frame W superseding Z still keeps displayed Frame X pinned
  //    until W is imported and subsequent display cycles complete!
  AImage* const img_w = reinterpret_cast<AImage*>(0x9104);
  auto frame_w = enqueue_and_acquire(img_w, -1);
  producer->RetireUnimportedFrame(frame_z.ahardware_buffer,
                                  std::move(frame_z.acquire_fence_fd));
  NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
      frame_z.ahardware_buffer);

  ASSERT_EQ(s_deleted_async.size(), 2u);
  EXPECT_EQ(s_deleted_async[1].first, img_z);
  EXPECT_EQ(s_deleted_async[1].second, fence_z);
  for (const auto& deleted : s_deleted_async) {
    EXPECT_NE(deleted.first, img_x);
  }

  // 5. Importing W keeps X pinned for one more display cycle
  // (`kPreviousDisplayed`)
  //    to cover in-flight GPU work; X is only deleted on final release/cleanup.
  producer->NotifyFrameDisplayed(frame_w.ahardware_buffer);
  NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
      frame_w.ahardware_buffer);

  producer->Release();
  producer.reset();
  NdkSurfaceProducer::SetProcsForTest(std::nullopt);

  EXPECT_EQ(s_ahb_live_refs, 0);
  ASSERT_EQ(s_deleted_async.size(), 4u);
  EXPECT_EQ(CountOpenFds(), fd_baseline);
}

TEST(
    NdkSurfaceProducerTest,
    NotifyPendingFenceSignaledPostsToRealPlatformTaskRunnerAndSkipsAfterRelease) {
  fml::Thread platform_thread("ndk_producer_test_platform_thread");
  auto platform_runner = platform_thread.GetTaskRunner();

  std::atomic<int> scheduled_count{0};
  std::atomic<int64_t> scheduled_texture_id{-1};
  std::atomic<bool> ran_on_platform_runner{false};
  fml::AutoResetWaitableEvent scheduled_event;

  std::shared_ptr<NdkSurfaceProducer> producer;
  {
    fml::AutoResetWaitableEvent created_on_platform;
    platform_runner->PostTask([&]() {
      producer = std::make_shared<NdkSurfaceProducer>(
          /*texture_id=*/4242, fml::WeakPtr<PlatformViewAndroid>(),
          platform_runner, /*is_displaying_flutter_ui=*/true);
      created_on_platform.Signal();
    });
    created_on_platform.Wait();
  }
  producer->SetFrameScheduledCallbackForTest([&](int64_t texture_id) {
    ran_on_platform_runner.store(platform_runner->RunsTasksOnCurrentThread(),
                                 std::memory_order_release);
    scheduled_texture_id.store(texture_id, std::memory_order_release);
    scheduled_count.fetch_add(1, std::memory_order_acq_rel);
    scheduled_event.Signal();
  });

  // 1. Trigger NotifyPendingFenceSignaled() from a non-platform thread: the
  //    task must execute on `platform_runner` and schedule texture 4242.
  producer->NotifyPendingFenceSignaled();
  EXPECT_FALSE(
      scheduled_event.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(500)));
  EXPECT_TRUE(ran_on_platform_runner.load(std::memory_order_acquire));
  EXPECT_EQ(scheduled_texture_id.load(std::memory_order_acquire), 4242);
  EXPECT_EQ(scheduled_count.load(std::memory_order_acquire), 1);

  // 2. Hold `platform_runner` behind a barrier, queue
  // NotifyPendingFenceSignaled,
  //    call `producer->Release()`, then release the barrier: the queued task
  //    must observe `released_ == true` and skip scheduling another frame.
  fml::AutoResetWaitableEvent runner_blocked;
  fml::AutoResetWaitableEvent allow_runner_continue;
  platform_runner->PostTask([&]() {
    runner_blocked.Signal();
    allow_runner_continue.Wait();
  });
  EXPECT_FALSE(
      runner_blocked.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(500)));

  producer->NotifyPendingFenceSignaled();
  producer->Release();
  allow_runner_continue.Signal();

  fml::AutoResetWaitableEvent drained;
  platform_runner->PostTask([&]() { drained.Signal(); });
  EXPECT_FALSE(drained.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(500)));
  EXPECT_EQ(scheduled_count.load(std::memory_order_acquire), 1);

  producer->SetFrameScheduledCallbackForTest({});
  producer.reset();
}

TEST(NdkSurfaceProducerTest,
     SynchronizedFrameLatchAndResizeSyncOnRealHardwareBuffersWithZeroFdLeaks) {
  if (!NdkSurfaceProducer::IsAvailable()) {
    GTEST_SKIP() << "NdkSurfaceProducer requires API 29+ libmediandk symbols.";
  }

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/9001, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  producer->SetSize(400, 300);
  ANativeWindow* window = producer->GetNativeWindowForTest(false);
  ASSERT_NE(window, nullptr);

  // Warm up allocator/binder FDs before recording baseline.
  for (int i = 0; i < 3; ++i) {
    ASSERT_TRUE(PostCpuFrameToWindow(window, 400, 300));
    for (int w = 0; w < 50 && producer->GetNumQueuedImagesForTest() == 0; ++w) {
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    auto warm = producer->AcquireLatestFrame();
    if (warm.ahardware_buffer != nullptr) {
      EXPECT_GT(warm.frame_generation, 0u);
      EXPECT_EQ(warm.frame_size, SkISize::Make(400, 300));
      NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
          warm.ahardware_buffer);
    }
  }

  const int fd_baseline = CountOpenFds();
  ASSERT_GT(fd_baseline, 0);

  // 1. Simulated 120 Hz PlatformView jank across 2ms, 6ms, and 12ms delays:
  //    verify zero torn frames and immediate wakeup via
  //    RegisterSyncReadyCallback.
  const std::vector<int> delays_ms = {2, 6, 12, 2, 6, 12};
  int torn_frames_observed = 0;

  for (size_t idx = 0; idx < delays_ms.size(); ++idx) {
    const uint64_t target_gen = producer->GetProducedFrameGeneration() + 1;
    EXPECT_FALSE(producer->SatisfiesSyncRequirement(target_gen,
                                                    SkISize::Make(400, 300)));

    fml::AutoResetWaitableEvent sync_ready_event;
    std::atomic<uint64_t> observed_gen_on_ready{0};
    producer->RegisterSyncReadyCallback(
        target_gen, SkISize::Make(400, 300), [&]() {
          observed_gen_on_ready.store(producer->GetProducedFrameGeneration(),
                                      std::memory_order_release);
          sync_ready_event.Signal();
        });

    std::thread delayed_producer([window, delay_ms = delays_ms[idx]]() {
      std::this_thread::sleep_for(std::chrono::milliseconds(delay_ms));
      EXPECT_TRUE(PostCpuFrameToWindow(window, 400, 300));
    });

    EXPECT_FALSE(sync_ready_event.WaitWithTimeout(
        fml::TimeDelta::FromMilliseconds(500)));
    delayed_producer.join();

    const uint64_t actual_gen =
        observed_gen_on_ready.load(std::memory_order_acquire);
    if (actual_gen < target_gen) {
      torn_frames_observed++;
    }
    EXPECT_GE(actual_gen, target_gen);
    EXPECT_TRUE(producer->SatisfiesSyncRequirement(target_gen,
                                                   SkISize::Make(400, 300)));

    auto latched = producer->AcquireLatestFrame();
    if (latched.ahardware_buffer != nullptr) {
      EXPECT_GE(latched.frame_generation, target_gen);
      EXPECT_EQ(latched.frame_size, SkISize::Make(400, 300));
      NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
          latched.ahardware_buffer);
    }
  }
  EXPECT_EQ(torn_frames_observed, 0);

  // 2. Dynamic resize synchronization from 400x300 -> 800x600:
  //    verify 400x300 buffer does NOT satisfy 800x600 sync requirement, and
  //    callback fires immediately once the 800x600 hardware buffer arrives.
  producer->SetSize(800, 600);
  ANativeWindow* resized_window = producer->GetNativeWindowForTest(false);
  ASSERT_NE(resized_window, nullptr);
  EXPECT_FALSE(producer->SatisfiesSyncRequirement(0, SkISize::Make(800, 600)));

  fml::AutoResetWaitableEvent resize_ready_event;
  std::atomic<int> resized_width{0};
  std::atomic<int> resized_height{0};
  producer->RegisterSyncReadyCallback(
      /*min_generation=*/0, SkISize::Make(800, 600), [&]() {
        const SkISize sz = producer->GetLatestReadyFrameSize();
        resized_width.store(sz.width(), std::memory_order_release);
        resized_height.store(sz.height(), std::memory_order_release);
        resize_ready_event.Signal();
      });

  ASSERT_TRUE(PostCpuFrameToWindow(resized_window, 800, 600));
  EXPECT_FALSE(resize_ready_event.WaitWithTimeout(
      fml::TimeDelta::FromMilliseconds(500)));
  EXPECT_EQ(resized_width.load(std::memory_order_acquire), 800);
  EXPECT_EQ(resized_height.load(std::memory_order_acquire), 600);
  EXPECT_TRUE(producer->SatisfiesSyncRequirement(0, SkISize::Make(800, 600)));

  auto resized_frame = producer->AcquireLatestFrame();
  ASSERT_NE(resized_frame.ahardware_buffer, nullptr);
  EXPECT_EQ(resized_frame.frame_size, SkISize::Make(800, 600));
  producer->NotifyFrameDisplayed(resized_frame.ahardware_buffer);
  NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
      resized_frame.ahardware_buffer);

  // 3. A -> B -> A resize cycle & explicit waiter cancellation:
  //    resizing back from 800x600 -> 400x300 must NOT falsely match any older
  //    `kPreviousDisplayed` 400x300 frame, and CancelSyncReadyCallback must
  //    drop `GetSyncWaiterCountForTest()` to 0 immediately.
  producer->SetSize(400, 300);
  ANativeWindow* shrink_window = producer->GetNativeWindowForTest(false);
  ASSERT_NE(shrink_window, nullptr);
  EXPECT_FALSE(producer->SatisfiesSyncRequirement(0, SkISize::Make(400, 300)));

  const uint64_t cancel_token = producer->RegisterSyncReadyCallback(
      /*min_generation=*/0, SkISize::Make(400, 300), []() {});
  EXPECT_NE(cancel_token, 0u);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 1u);
  producer->CancelSyncReadyCallback(cancel_token);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 0u);

  ASSERT_TRUE(PostCpuFrameToWindow(shrink_window, 400, 300));
  for (int w = 0; w < 50 && producer->GetNumQueuedImagesForTest() == 0; ++w) {
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  EXPECT_TRUE(producer->SatisfiesSyncRequirement(0, SkISize::Make(400, 300)));
  auto shrink_frame = producer->AcquireLatestFrame();
  if (shrink_frame.ahardware_buffer != nullptr) {
    EXPECT_EQ(shrink_frame.frame_size, SkISize::Make(400, 300));
    NdkSurfaceProducer::GetProcs().AHardwareBuffer_release(
        shrink_frame.ahardware_buffer);
  }

  producer->Release();
  EXPECT_EQ(producer->GetNumQueuedImagesForTest(), 0);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 0u);
  producer.reset();

  // Verify zero open FD leaks (sync fences, eventfd, AImageReader,
  // AHardwareBuffer).
  EXPECT_LE(CountOpenFds(), fd_baseline);
}

TEST(NdkSurfaceProducerTest,
     SyncFenceWatcherRearmsAcrossQueuedUnsignaledFencesAndWakesPromptly) {
  static AImageReader_ImageListener s_listener{};
  static AImage* s_next_image = nullptr;
  static int s_next_fence = -1;

  NdkSurfaceProducer::Procs mock_procs = NdkSurfaceProducer::GetProcs();
  mock_procs.AImageReader_newWithUsage =
      [](int32_t, int32_t, int32_t, uint64_t, int32_t,
         AImageReader** reader) -> media_status_t {
    *reader = reinterpret_cast<AImageReader*>(0xA001);
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_getWindow =
      [](AImageReader*, ANativeWindow** window) -> media_status_t {
    *window = reinterpret_cast<ANativeWindow*>(0xA002);
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_setImageListener =
      [](AImageReader*,
         AImageReader_ImageListener* listener) -> media_status_t {
    s_listener = listener != nullptr ? *listener : AImageReader_ImageListener{};
    return AMEDIA_OK;
  };
  mock_procs.AImageReader_delete = [](AImageReader*) {};
  mock_procs.AImageReader_acquireNextImageAsync =
      [](AImageReader*, AImage** image, int* acquireFenceFd) -> media_status_t {
    if (s_next_image != nullptr) {
      *image = s_next_image;
      *acquireFenceFd = s_next_fence;
      s_next_image = nullptr;
      s_next_fence = -1;
      return AMEDIA_OK;
    }
    *image = nullptr;
    *acquireFenceFd = -1;
    return AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
  };
  mock_procs.AImage_getHardwareBuffer =
      [](const AImage* image, AHardwareBuffer** buffer) -> media_status_t {
    *buffer = reinterpret_cast<AHardwareBuffer*>(const_cast<AImage*>(image));
    return AMEDIA_OK;
  };
  mock_procs.AImage_deleteAsync = [](AImage*, int releaseFenceFd) {
    if (releaseFenceFd >= 0) {
      ::close(releaseFenceFd);
    }
  };
  mock_procs.AImage_delete = [](AImage*) {};
  mock_procs.AHardwareBuffer_acquire = [](AHardwareBuffer*) {};
  mock_procs.AHardwareBuffer_release = [](AHardwareBuffer*) {};

  NdkSurfaceProducer::SetProcsForTest(mock_procs);

  const int fd_baseline = CountOpenFds();
  ASSERT_GT(fd_baseline, 0);

  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/99, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  ASSERT_NE(producer->GetNativeWindowForTest(false), nullptr);

  // Queue 2 frames backed by real kernel unsignaled eventfd acquire fences.
  const int fence_1 = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  const int fence_2 = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  ASSERT_GE(fence_1, 0);
  ASSERT_GE(fence_2, 0);

  s_next_image = reinterpret_cast<AImage*>(0xA101);
  s_next_fence = ::dup(fence_1);
  s_listener.onImageAvailable(s_listener.context,
                              reinterpret_cast<AImageReader*>(0xA001));

  s_next_image = reinterpret_cast<AImage*>(0xA102);
  s_next_fence = ::dup(fence_2);
  s_listener.onImageAvailable(s_listener.context,
                              reinterpret_cast<AImageReader*>(0xA001));

  EXPECT_FALSE(producer->SatisfiesSyncRequirement(1, SkISize::MakeEmpty()));
  EXPECT_FALSE(producer->SatisfiesSyncRequirement(2, SkISize::MakeEmpty()));

  fml::AutoResetWaitableEvent cb1_fired;
  fml::AutoResetWaitableEvent cb2_fired;
  const uint64_t tok1 = producer->RegisterSyncReadyCallback(
      /*min_generation=*/1, SkISize::MakeEmpty(),
      [&]() { cb1_fired.Signal(); });
  const uint64_t tok2 = producer->RegisterSyncReadyCallback(
      /*min_generation=*/2, SkISize::MakeEmpty(),
      [&]() { cb2_fired.Signal(); });
  EXPECT_NE(tok1, 0u);
  EXPECT_NE(tok2, 0u);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 2u);

  // Step 1: Signal ONLY fence_1 while fence_2 remains unsignaled. Verify the
  // watcher thread wakes on fence_1 (`GetSyncWatcherPollIterationsForTest >=
  // 1`), sees the newest queued frame (`fence_2`) is still unsignaled so
  // neither callback fires prematurely (`GetSyncWaiterCountForTest == 2`), and
  // re-arms its internal `while (dup_fence.is_valid())` loop onto `fence_2`
  // without self-joining.
  const uint64_t one = 1;
  ASSERT_EQ(::write(fence_1, &one, sizeof(one)),
            static_cast<ssize_t>(sizeof(one)));
  ::close(fence_1);
  for (int w = 0;
       w < 100 && producer->GetSyncWatcherPollIterationsForTest() < 1u; ++w) {
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  EXPECT_GE(producer->GetSyncWatcherPollIterationsForTest(), 1u);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 2u);

  // Step 2: Now signal `fence_2` and verify the re-armed watcher wakes for a
  // second iteration (`>= 2`), fires both callbacks cleanly, and leaves 0
  // active waiters.
  ASSERT_EQ(::write(fence_2, &one, sizeof(one)),
            static_cast<ssize_t>(sizeof(one)));
  ::close(fence_2);

  EXPECT_FALSE(
      cb1_fired.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(250)));
  EXPECT_FALSE(
      cb2_fired.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(250)));
  EXPECT_GE(producer->GetSyncWatcherPollIterationsForTest(), 2u);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 0u);

  // Step 3: Run 50 sequential register -> signal -> latch cycles with real
  // unsignaled eventfd fences and assert retired watcher handles are
  // continuously reaped off-raster (`GetRetiredWatcherThreadCountForTest() <=
  // 1`) so long-lived sessions never accumulate zombie threads or stacks.
  for (int i = 0; i < 50; ++i) {
    const int cycle_fence = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    ASSERT_GE(cycle_fence, 0);
    s_next_image =
        reinterpret_cast<AImage*>(static_cast<uintptr_t>(0xB000 + i));
    s_next_fence = ::dup(cycle_fence);
    s_listener.onImageAvailable(s_listener.context,
                                reinterpret_cast<AImageReader*>(0xA001));

    const uint64_t target_gen = producer->GetProducedFrameGeneration();
    fml::AutoResetWaitableEvent latched;
    const uint64_t tok = producer->RegisterSyncReadyCallback(
        target_gen, SkISize::MakeEmpty(), [&]() { latched.Signal(); });
    EXPECT_NE(tok, 0u);
    ASSERT_EQ(::write(cycle_fence, &one, sizeof(one)),
              static_cast<ssize_t>(sizeof(one)));
    ::close(cycle_fence);
    EXPECT_FALSE(
        latched.WaitWithTimeout(fml::TimeDelta::FromMilliseconds(250)));
    EXPECT_EQ(producer->GetRetiredWatcherThreadCountForTest(), 0u);
  }
  EXPECT_EQ(producer->GetRetiredWatcherThreadCountForTest(), 0u);

  // Now queue a final unsignaled fence, verify non-blocking
  // CancelSyncReadyCallback returns immediately, and verify Release() while
  // parked in poll() wakes in < 50 ms with zero leaked file descriptors.
  const int fence_3 = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  ASSERT_GE(fence_3, 0);
  s_next_image = reinterpret_cast<AImage*>(0xA103);
  s_next_fence = fence_3;
  s_listener.onImageAvailable(s_listener.context,
                              reinterpret_cast<AImageReader*>(0xA001));

  const uint64_t final_gen = producer->GetProducedFrameGeneration();
  const uint64_t tok3 = producer->RegisterSyncReadyCallback(
      /*min_generation=*/final_gen, SkISize::MakeEmpty(), []() {});
  EXPECT_NE(tok3, 0u);
  producer->CancelSyncReadyCallback(tok3);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 0u);

  const uint64_t tok4 = producer->RegisterSyncReadyCallback(
      /*min_generation=*/final_gen, SkISize::MakeEmpty(), []() {});
  EXPECT_NE(tok4, 0u);
  const auto release_start = std::chrono::steady_clock::now();
  producer->Release();
  const auto release_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                              std::chrono::steady_clock::now() - release_start)
                              .count();
  EXPECT_LT(release_ms, 50);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 0u);

  producer.reset();
  NdkSurfaceProducer::SetProcsForTest(std::nullopt);
  EXPECT_EQ(CountOpenFds(), fd_baseline);
}

TEST(NdkSurfaceProducerTest,
     ImageExternalTextureSyncWaiterTokenCancelAndFallbackPassThrough) {
  class TestImageExternalTexture final : public ImageExternalTexture {
   public:
    explicit TestImageExternalTexture(int64_t id)
        : ImageExternalTexture(id,
                               fml::jni::ScopedJavaGlobalRef<jobject>(),
                               /*jni_facade=*/nullptr,
                               ImageLifecycle::kReset) {}
    void ProcessFrame(PaintContext&, const SkRect&) override {}
    void Attach(PaintContext&) override {}
    void Detach() override {}
  };

  if (!NdkSurfaceProducer::IsAvailable()) {
    GTEST_SKIP() << "NdkSurfaceProducer requires API 29+ libmediandk symbols.";
  }

  const int fd_baseline = CountOpenFds();
  ASSERT_GT(fd_baseline, 0);

  auto texture = std::make_shared<TestImageExternalTexture>(/*id=*/777);

  // 1. Without an attached NdkSurfaceProducer (Java fallback path), sync
  //    requirements pass through immediately so fallback devices never stall.
  EXPECT_TRUE(texture->SatisfiesSyncRequirement(5, SkISize::Make(400, 300)));
  bool fallback_fired = false;
  EXPECT_EQ(texture->RegisterSyncReadyCallback(
                5, SkISize::Make(400, 300),
                [&fallback_fired]() { fallback_fired = true; }),
            0u);
  EXPECT_TRUE(fallback_fired);
  EXPECT_EQ(texture->GetSyncWaiterCountForTest(), 0u);

  // 2. With an attached NdkSurfaceProducer, RegisterSyncReadyCallback returns
  //    a non-zero token and CancelSyncReadyCallback(token) removes the waiter
  //    from BOTH the texture AND the producer immediately.
  auto producer = std::make_shared<NdkSurfaceProducer>(
      /*texture_id=*/777, fml::WeakPtr<PlatformViewAndroid>(),
      /*platform_task_runner=*/nullptr, /*is_displaying_flutter_ui=*/true);
  texture->SetNdkSurfaceProducer(producer);

  EXPECT_FALSE(texture->SatisfiesSyncRequirement(5, SkISize::Make(400, 300)));
  const uint64_t token =
      texture->RegisterSyncReadyCallback(5, SkISize::Make(400, 300), []() {});
  EXPECT_NE(token, 0u);
  EXPECT_EQ(texture->GetSyncWaiterCountForTest(), 1u);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 1u);

  texture->CancelSyncReadyCallback(token);
  EXPECT_EQ(texture->GetSyncWaiterCountForTest(), 0u);
  EXPECT_EQ(producer->GetSyncWaiterCountForTest(), 0u);

  texture->SetNdkSurfaceProducer(nullptr);
  producer->Release();
  producer.reset();
  texture.reset();

  EXPECT_EQ(CountOpenFds(), fd_baseline);
}

}  // namespace testing
}  // namespace flutter
