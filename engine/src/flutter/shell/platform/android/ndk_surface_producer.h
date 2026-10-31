// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_NDK_SURFACE_PRODUCER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_NDK_SURFACE_PRODUCER_H_

#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <optional>
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
///             AHardwareBuffer* (with a caller-owned AHardwareBuffer_acquire
///             reference taken under mutex_) and un-waited sync fence FD
///             directly to the raster thread with zero per-frame JNI calls.
///
class NdkSurfaceProducer
    : public std::enable_shared_from_this<NdkSurfaceProducer> {
 public:
  struct AcquiredFrame {
    // Caller-owned reference acquired via AHardwareBuffer_acquire under mutex_.
    // The caller must release it via AHardwareBuffer_release when done.
    AHardwareBuffer* ahardware_buffer = nullptr;
    fml::UniqueFD acquire_fence_fd;
  };

  struct Procs {
    decltype(&AImageReader_newWithUsage) AImageReader_newWithUsage = nullptr;
    decltype(&AImageReader_delete) AImageReader_delete = nullptr;
    decltype(&AImageReader_getWindow) AImageReader_getWindow = nullptr;
    decltype(&AImageReader_setImageListener) AImageReader_setImageListener =
        nullptr;
    decltype(&AImageReader_acquireNextImageAsync)
        AImageReader_acquireNextImageAsync = nullptr;
    decltype(&AImage_delete) AImage_delete = nullptr;
    decltype(&AImage_deleteAsync) AImage_deleteAsync = nullptr;
    decltype(&AImage_getHardwareBuffer) AImage_getHardwareBuffer = nullptr;
    decltype(&ANativeWindow_toSurface) ANativeWindow_toSurface = nullptr;
    decltype(&AHardwareBuffer_acquire) AHardwareBuffer_acquire = nullptr;
    decltype(&AHardwareBuffer_release) AHardwareBuffer_release = nullptr;

    bool IsValid() const {
      return AImageReader_newWithUsage != nullptr &&
             AImageReader_delete != nullptr &&
             AImageReader_getWindow != nullptr &&
             AImageReader_setImageListener != nullptr &&
             AImageReader_acquireNextImageAsync != nullptr &&
             AImage_delete != nullptr && AImage_deleteAsync != nullptr &&
             AImage_getHardwareBuffer != nullptr &&
             ANativeWindow_toSurface != nullptr &&
             AHardwareBuffer_acquire != nullptr &&
             AHardwareBuffer_release != nullptr;
    }
  };

  static constexpr size_t kMaxDequeuedImages = 2;
  static constexpr int32_t kMaxImages =
      5 + static_cast<int32_t>(kMaxDequeuedImages);
  static constexpr int kDefaultFenceWaitTimeoutMs = 32;

  static const Procs& GetProcs();
  static void SetProcsForTest(std::optional<Procs> overrides);

  static bool IsAvailable();

  /// Waits up to `timeout_ms` on `fence_fd` via `poll()` and closes the FD.
  /// Returns true if the fence signaled (or was invalid), false on timeout or
  /// error.
  static bool WaitAcquireFenceFdBounded(
      fml::UniqueFD fence_fd,
      int timeout_ms = kDefaultFenceWaitTimeoutMs);

  NdkSurfaceProducer(int64_t texture_id,
                     fml::WeakPtr<PlatformViewAndroid> platform_view,
                     fml::RefPtr<fml::TaskRunner> platform_task_runner,
                     bool is_displaying_flutter_ui);

  ~NdkSurfaceProducer();

  void SetSize(int32_t width, int32_t height);

  fml::jni::ScopedJavaLocalRef<jobject> GetSurface(JNIEnv* env,
                                                   bool force_new_reader);

  /// Test-only helper that creates or returns the active reader's
  /// ANativeWindow* without requiring a real JNIEnv/Surface object.
  ANativeWindow* GetNativeWindowForTest(bool force_new_reader);

  void ScheduleFrameFromPlatform(bool is_displaying_flutter_ui);

  void SetDisplayingFlutterUi(bool is_displaying_flutter_ui);

  void OnTrimMemoryCleanup();

  void Release();

  AcquiredFrame AcquireLatestFrame();

  int32_t GetNumImageReadersForTest();
  int32_t GetNumQueuedImagesForTest();
  int32_t GetPendingDequeuedImagesForTest();

 private:
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

    // Guarded by NdkSurfaceProducer::mutex_ while attached to producer; moved
    // out under mutex_ before ShutdownReader() is invoked, and additionally
    // guarded by shutdown_mutex_ inside ShutdownReader().
    std::deque<QueuedImage> image_queue;
    int32_t in_flight_acquire = 0;
    bool closed = false;
    bool close_when_idle = false;
    std::atomic<bool> shutdown_done{false};
    std::mutex shutdown_mutex;

    void ShutdownReader();
    ~PerReader();
  };

  static void OnImageAvailableStatic(void* context, AImageReader* raw_reader);

  void OnImageAvailable(const std::shared_ptr<PerReader>& per_reader,
                        AImageReader* raw_reader);

  std::shared_ptr<PerReader> CreatePerReaderLocked(JNIEnv* env,
                                                   int32_t width,
                                                   int32_t height,
                                                   bool create_java_surface);

  bool HasQueuedImagesLocked() const;
  bool HasInFlightAcquireLocked() const;
  void CollectPrunableReadersLocked(
      std::vector<std::shared_ptr<PerReader>>& out,
      std::vector<QueuedImage>& images_out);

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
