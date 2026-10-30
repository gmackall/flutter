// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_NDK_SURFACE_PRODUCER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_NDK_SURFACE_PRODUCER_H_

#include <android/hardware_buffer.h>
#include <jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <vector>

#include "flutter/fml/macros.h"
#include "flutter/fml/memory/ref_ptr.h"
#include "flutter/fml/memory/weak_ptr.h"
#include "flutter/fml/platform/android/scoped_java_ref.h"
#include "flutter/fml/task_runner.h"
#include "flutter/fml/unique_fd.h"

namespace flutter {

class PlatformViewAndroid;

//------------------------------------------------------------------------------
/// @brief      Native NDK AImageReader backing for FlutterRenderer's
///             ImageReaderSurfaceProducer on Android API 29+.
///
///             Replaces Java ImageReader.acquireLatestImage() (which passes
///             waitForFence=true and blocks CPU on Fence::waitForever) with
///             AImageReader_acquireNextImageAsync(), returning the raw
///             AHardwareBuffer* and un-waited sync fence FD directly to the
///             raster thread with zero per-frame JNI calls so Impeller/GLES
///             can import the fence on the GPU.
///
class NdkSurfaceProducer
    : public std::enable_shared_from_this<NdkSurfaceProducer> {
 public:
  struct AcquiredFrame {
    AHardwareBuffer* ahardware_buffer = nullptr;
    fml::UniqueFD acquire_fence_fd;
  };

  static bool IsAvailable();

  static int GetLastReaderThreadTid();

  NdkSurfaceProducer(int64_t texture_id,
                     fml::WeakPtr<PlatformViewAndroid> platform_view,
                     fml::RefPtr<fml::TaskRunner> platform_task_runner,
                     bool is_displaying_flutter_ui);

  ~NdkSurfaceProducer();

  void SetSize(int32_t width, int32_t height);

  fml::jni::ScopedJavaLocalRef<jobject> GetSurface(JNIEnv* env,
                                                   bool force_new_reader);

  void ScheduleFrameFromPlatform(bool is_displaying_flutter_ui);

  void SetDisplayingFlutterUi(bool is_displaying_flutter_ui);

  void OnTrimMemoryCleanup();

  void Release();

  AcquiredFrame AcquireLatestFrame();

  int32_t GetNumImageReadersForTest();
  int32_t GetNumQueuedImagesForTest();
  int32_t GetPendingDequeuedImagesForTest();

 private:
  static constexpr int32_t kMaxImages = 8;
  // Retain 3 dequeued images (out of kMaxImages = 8) so at most 2 in-flight
  // swapchain frames have guaranteed host-fence completion before an AImage is
  // returned asynchronously to BufferQueue via AImage_deleteAsync(img, -1).
  static constexpr size_t kMaxDequeuedImages = 3;

  struct QueuedImage {
    AImage* image = nullptr;
    AHardwareBuffer* ahb = nullptr;
    fml::UniqueFD acquire_fence_fd;
  };

  struct PerReader;

  struct ListenerContext {
    std::weak_ptr<NdkSurfaceProducer> producer;
    std::weak_ptr<PerReader> reader;
  };

  struct PerReader : public std::enable_shared_from_this<PerReader> {
    AImageReader* reader = nullptr;
    ANativeWindow* window = nullptr;
    fml::jni::ScopedJavaGlobalRef<jobject> java_surface;
    std::unique_ptr<ListenerContext> listener_ctx;
    AImageReader_ImageListener listener{};

    std::deque<QueuedImage> image_queue;
    std::atomic<int> reader_tid{0};
    int32_t in_flight_acquire = 0;
    bool closed = false;
    bool close_when_idle = false;
    bool shutdown_done = false;

    void ShutdownReader();
    ~PerReader();
  };

  static void OnImageAvailableStatic(void* context, AImageReader* raw_reader);

  void OnImageAvailable(const std::shared_ptr<PerReader>& per_reader,
                        AImageReader* raw_reader);

  std::shared_ptr<PerReader> CreatePerReaderLocked(JNIEnv* env,
                                                   int32_t width,
                                                   int32_t height);

  bool HasQueuedImagesLocked() const;
  bool HasInFlightAcquireLocked() const;
  void CollectPrunableReadersLocked(
      std::vector<std::shared_ptr<PerReader>>& out);

  void PostScheduleFromReader();
  void PostArmNextVsync();
  void PostIdleAsyncCleanup();
  void TriggerEngineTextureFrameOnPlatformThread();

  const int64_t texture_id_;
  fml::WeakPtr<PlatformViewAndroid> platform_view_;
  fml::RefPtr<fml::TaskRunner> platform_task_runner_;

  std::atomic<bool> is_displaying_ui_{false};
  std::atomic<bool> has_been_acquired_with_image_{false};
  std::atomic<bool> latest_frame_only_mode_{false};

  std::mutex mutex_;
  std::condition_variable cv_;
  int32_t requested_width_ = 1;
  int32_t requested_height_ = 1;
  bool create_new_reader_ = true;
  bool released_ = false;
  int32_t waiting_dequeue_count_ = 0;
  bool schedule_from_reader_posted_ = false;
  bool arm_next_vsync_posted_ = false;
  bool async_cleanup_posted_ = false;

  std::deque<std::shared_ptr<PerReader>> readers_;
  PerReader* last_reader_dequeued_from_ = nullptr;
  std::deque<AImage*> dequeued_images_;
  std::deque<QueuedImage> async_images_to_delete_;
  std::deque<std::shared_ptr<PerReader>> async_readers_to_shutdown_;

  FML_DISALLOW_COPY_AND_ASSIGN(NdkSurfaceProducer);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_NDK_SURFACE_PRODUCER_H_
