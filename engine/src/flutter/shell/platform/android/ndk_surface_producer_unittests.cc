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

  // Warm up one frame so any one-time gralloc/ashmem/binder FDs are initialized
  // before we snapshot the baseline FD count.
  ASSERT_TRUE(PostCpuFrameToWindow(window, 64, 64));
  for (int attempt = 0;
       attempt < 100 && producer->GetNumQueuedImagesForTest() == 0; ++attempt) {
    std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
  {
    auto warm = producer->AcquireLatestFrame();
    if (warm.ahardware_buffer != nullptr) {
      ASSERT_TRUE(NdkSurfaceProducer::WaitAcquireFenceFdBounded(
          std::move(warm.acquire_fence_fd)));
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
        EXPECT_TRUE(NdkSurfaceProducer::WaitAcquireFenceFdBounded(
            std::move(frame.acquire_fence_fd)));
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

  EXPECT_TRUE(NdkSurfaceProducer::WaitAcquireFenceFdBounded(
      std::move(acquired.acquire_fence_fd)));
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
     WaitAcquireFenceFdBoundedHandlesSignaledAndTimeoutAndClosesFd) {
  const int fd_before = CountOpenFds();

  // 1. Signaled eventfd returns true immediately and closes the FD.
  int signaled_fd = ::eventfd(1, EFD_CLOEXEC | EFD_NONBLOCK);
  ASSERT_GE(signaled_fd, 0);
  EXPECT_TRUE(NdkSurfaceProducer::WaitAcquireFenceFdBounded(
      fml::UniqueFD(signaled_fd), /*timeout_ms=*/5));

  // 2. Unsignaled eventfd times out after 5ms, returns false, and closes FD.
  int unsignaled_fd = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  ASSERT_GE(unsignaled_fd, 0);
  EXPECT_FALSE(NdkSurfaceProducer::WaitAcquireFenceFdBounded(
      fml::UniqueFD(unsignaled_fd), /*timeout_ms=*/5));

  const int fd_after = CountOpenFds();
  EXPECT_EQ(fd_after, fd_before);
}

}  // namespace testing
}  // namespace flutter
