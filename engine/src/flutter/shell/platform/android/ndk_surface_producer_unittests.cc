// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

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

}  // namespace testing
}  // namespace flutter
