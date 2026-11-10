// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_NDK_IMAGE_READER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_NDK_IMAGE_READER_H_

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <vector>

#include "flutter/common/graphics/texture.h"
#include "flutter/display_list/image/dl_image.h"
#include "flutter/fml/macros.h"
#include "flutter/fml/memory/ref_ptr.h"
#include "flutter/fml/memory/weak_ptr.h"
#include "flutter/fml/platform/android/scoped_java_ref.h"
#include "flutter/fml/task_runner.h"
#include "flutter/fml/time/time_delta.h"
#include "flutter/fml/unique_fd.h"

namespace flutter {

class ImageExternalTexture;
class NdkImageTextureBridge;
class PlatformView;

/// RAII wrapper holding an explicit AHardwareBuffer_acquire reference.
class ScopedAHardwareBufferRef {
 public:
  ScopedAHardwareBufferRef(AHardwareBuffer* buffer, bool acquire);
  ~ScopedAHardwareBufferRef();

  AHardwareBuffer* get() const { return buffer_; }

 private:
  AHardwareBuffer* buffer_ = nullptr;
  bool owns_ref_ = false;

  FML_DISALLOW_COPY_AND_ASSIGN(ScopedAHardwareBufferRef);
};

/// Computes the per-buffer cache key for [buffer].
/// On API 31+, uses AHardwareBuffer_getId and sets [*out_used_pointer_key] to
/// false. On API 29-30, falls back to the pointer value and sets
/// [*out_used_pointer_key] to true (callers must retain an explicit
/// AHardwareBuffer_acquire reference for the cache entry lifetime to prevent
/// pointer ABA reuse).
uint64_t ComputeHardwareBufferKey(AHardwareBuffer* buffer,
                                  bool* out_used_pointer_key);

/// Waits on an AImageReader acquire sync_file FD using bounded poll(), closing
/// [fence_fd] before returning. Returns true if the FD was invalid (no wait
/// required) or signaled cleanly.
bool WaitAcquireFenceFd(fml::UniqueFD& fence_fd, int timeout_ms = 3000);

/// Raster-thread incremental retirement queue that releases bulk-evicted GPU
/// texture imports in small batches across frames to avoid synchronous
/// vkFreeMemory / eglDestroyImageKHR stalls during texture disposal, while
/// flushing synchronously on context teardown.
class IncrementalRetireQueue final
    : public ContextListener,
      public std::enable_shared_from_this<IncrementalRetireQueue> {
 public:
  struct RetiredImageEntry {
    sk_sp<DlImage> image;
    std::shared_ptr<ScopedAHardwareBufferRef> ahb_ref;
  };

  static constexpr size_t kDefaultBatchSize = 2;
  static constexpr fml::TimeDelta kDefaultDrainInterval =
      fml::TimeDelta::FromMilliseconds(4);

  explicit IncrementalRetireQueue(
      fml::RefPtr<fml::TaskRunner> raster_task_runner,
      size_t batch_size = kDefaultBatchSize,
      fml::TimeDelta drain_interval = kDefaultDrainInterval);

  ~IncrementalRetireQueue();

  /// Enqueues [entries] for retirement on the raster thread. Single-entry
  /// evictions with an idle queue release immediately; bulk evictions drain in
  /// small posted batches.
  void Retire(std::vector<RetiredImageEntry> entries);

  /// Synchronously releases all queued entries on the raster thread.
  void FlushSynchronously();

  size_t PendingCount() const { return queue_.size(); }

  // |ContextListener|
  void OnGrContextCreated() override;

  // |ContextListener|
  void OnGrContextDestroyed() override;

 private:
  void ScheduleNextBatch();
  void DrainOneBatch(uint64_t scheduled_generation);

  fml::RefPtr<fml::TaskRunner> raster_task_runner_;
  const size_t batch_size_;
  const fml::TimeDelta drain_interval_;
  std::deque<RetiredImageEntry> queue_;
  uint64_t drain_generation_ = 0;
  bool drain_scheduled_ = false;

  FML_DISALLOW_COPY_AND_ASSIGN(IncrementalRetireQueue);
};

/// Thread-safe NDK AImageReader instance associated with a monotonic
/// [reader_generation]. Shared across AImageReader's internal ALooper callback
/// thread, the platform thread, and the raster thread.
class NdkImageReader : public std::enable_shared_from_this<NdkImageReader> {
 public:
  static constexpr int32_t kMaxDequeuedImages = 2;
  static constexpr int32_t kMaxImages = 5 + kMaxDequeuedImages;

  struct AcquiredNativeImage {
    uint64_t reader_generation = 0;
    AHardwareBuffer* hardware_buffer = nullptr;
    std::shared_ptr<ScopedAHardwareBufferRef> caller_ahb_ref;
    fml::UniqueFD acquire_fence_fd;
  };

  /// Returns true when running on Android API 29+ with all required
  /// libmediandk and libandroid procs resolved.
  static bool IsAvailable(bool require_java_surface = true);

  /// Creates a new NDK AImageReader on the platform thread. If [env] is
  /// non-null, also creates and retains the Java android.view.Surface via
  /// ANativeWindow_toSurface.
  static std::shared_ptr<NdkImageReader> Create(
      JNIEnv* env,
      int32_t width,
      int32_t height,
      std::shared_ptr<NdkImageTextureBridge> bridge);

  ~NdkImageReader();

  uint64_t generation() const { return generation_; }

  ANativeWindow* window() const { return window_; }

  fml::jni::ScopedJavaLocalRef<jobject> GetJavaSurface() const;

  bool IsClosed() const {
    return close_requested_.load(std::memory_order_acquire);
  }

  bool HasPendingImage() const {
    return has_pending_image_.load(std::memory_order_acquire);
  }

  size_t OpenImageCountForTest() const;

  /// Acquires the latest image and its sync fence on the raster thread with
  /// zero JNI calls, keeping up to kMaxDequeuedImages recent AImages open so
  /// producers cannot overwrite an in-flight GPU frame.
  AcquiredNativeImage AcquireLatestImage();

  /// Marks this reader closed, enqueues its generation for raster-thread cache
  /// eviction, and deletes the underlying AImageReader on the platform thread
  /// (never on an ALooper callback thread).
  void RequestClose();

  /// Executes platform-thread listener clearing and AImageReader_delete.
  void CloseOnPlatformThread();

 private:
  NdkImageReader(uint64_t generation,
                 AImageReader* reader,
                 ANativeWindow* window,
                 std::shared_ptr<NdkImageTextureBridge> bridge);

  static void OnImageAvailableCallback(void* context, AImageReader* reader);
  static void OnBufferRemovedCallback(void* context,
                                      AImageReader* reader,
                                      AHardwareBuffer* buffer);

  static uint64_t NextGeneration();

  const uint64_t generation_;
  const std::shared_ptr<NdkImageTextureBridge> bridge_;

  // Serializes raster-thread AcquireLatestImage against platform-thread
  // CloseOnPlatformThread. Never acquired on ALooper callbacks.
  mutable std::mutex reader_mutex_;
  AImageReader* reader_ = nullptr;
  ANativeWindow* window_ = nullptr;
  fml::jni::ScopedJavaGlobalRef<jobject> java_surface_;
  std::deque<AImage*> open_images_;

  std::atomic<bool> close_requested_{false};
  std::atomic<bool> closed_{false};
  std::atomic<bool> has_pending_image_{false};

  AImageReader_ImageListener image_listener_{};
  AImageReader_BufferRemovedListener buffer_removed_listener_{};

  FML_DISALLOW_COPY_AND_ASSIGN(NdkImageReader);
};

/// Shared coordinator connecting PlatformViewAndroid (platform thread),
/// NdkImageReader ALooper callbacks, and ImageExternalTexture (raster thread)
/// without holding strong references to ImageExternalTexture off the raster
/// thread.
class NdkImageTextureBridge
    : public std::enable_shared_from_this<NdkImageTextureBridge> {
 public:
  struct BufferEviction {
    uint64_t reader_generation = 0;
    uint64_t buffer_key = 0;
  };

  NdkImageTextureBridge(
      int64_t texture_id,
      fml::WeakPtr<PlatformView> platform_view,
      fml::RefPtr<fml::TaskRunner> platform_task_runner,
      fml::RefPtr<fml::TaskRunner> raster_task_runner,
      std::function<void()> schedule_frame_override = nullptr);

  ~NdkImageTextureBridge() = default;

  int64_t texture_id() const { return texture_id_; }

  const fml::RefPtr<fml::TaskRunner>& platform_task_runner() const {
    return platform_task_runner_;
  }

  const fml::RefPtr<fml::TaskRunner>& raster_task_runner() const {
    return raster_task_runner_;
  }

  void BindTexture(const std::shared_ptr<ImageExternalTexture>& texture);

  void AddReader(const std::shared_ptr<NdkImageReader>& reader);

  void RemoveReader(uint64_t generation);

  bool HasReaders() const;

  void CloseAllReaders();

  /// Called on the raster thread to acquire the latest frame across the reader
  /// resize queue and prune superseded readers.
  NdkImageReader::AcquiredNativeImage AcquireLatestFromReaders();

  /// Called from ALooper when a new image is available.
  void NotifyImageAvailable();

  /// Called from ALooper when BufferQueue frees a slot.
  void EnqueueBufferRemoved(uint64_t reader_generation, uint64_t buffer_key);

  /// Called when a reader closes so any cached entries for its generation are
  /// evicted on the raster thread.
  void EnqueueGenerationClosed(uint64_t reader_generation);

  /// Requests purging all cached GPU imports except the currently displayed
  /// DlImage on the raster thread.
  void RequestPurgeExceptCurrent();

  /// Swaps out all pending eviction signals in O(1) on the raster thread.
  void TakePendingEvictions(std::vector<BufferEviction>* out_buffers,
                            std::vector<uint64_t>* out_closed_generations,
                            bool* out_purge_except_current);

 private:
  void PostCoalescedRasterDrainLocked();

  const int64_t texture_id_;
  fml::WeakPtr<PlatformView> platform_view_;
  fml::RefPtr<fml::TaskRunner> platform_task_runner_;
  fml::RefPtr<fml::TaskRunner> raster_task_runner_;
  std::function<void()> schedule_frame_override_;

  // Only dereferenced/locked inside tasks running on raster_task_runner_ so
  // ~ImageExternalTexture() is guaranteed to run on the raster thread.
  std::weak_ptr<ImageExternalTexture> weak_texture_raster_only_;

  mutable std::mutex readers_mutex_;
  std::vector<std::shared_ptr<NdkImageReader>> active_readers_;

  std::atomic<bool> frame_schedule_pending_{false};

  std::mutex eviction_mutex_;
  std::vector<BufferEviction> pending_buffers_;
  std::vector<uint64_t> pending_closed_generations_;
  bool purge_except_current_requested_ = false;
  bool raster_drain_scheduled_ = false;

  FML_DISALLOW_COPY_AND_ASSIGN(NdkImageTextureBridge);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_NDK_IMAGE_READER_H_
