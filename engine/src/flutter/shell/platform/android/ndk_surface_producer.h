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
#include <poll.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
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
  enum class FenceWaitResult {
    /// The fence signaled (or was invalid / already retired), and `fence_fd`
    /// has been closed.
    kSignaled,
    /// The bounded `poll()` wait timed out before the fence signaled.
    /// `fence_fd` is intentionally left OPEN and owned by the caller so the
    /// frame can be retained and retried once the fence signals.
    kTimedOut,
    /// `poll()` failed with a terminal error or `POLLERR` / `POLLNVAL`
    /// revents; `fence_fd` has been closed.
    kError,
  };

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

  static constexpr size_t kMaxQueuedImages = 2;
  static constexpr size_t kMaxDequeuedImages = 2;
  // Explicit per-reader consumer retention budget + headroom slack so
  // `AImageReader_acquireNextImageAsync` never hits
  // `AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED` when a producer submits a final
  // frame under heavy GPU contention:
  //   `kMaxQueuedImages` (2 queued in `PerReader::image_queue`)
  // + 1 (`ConsumerImageState::kDisplayed` currently on screen)
  // + `kMaxDequeuedImages` (2 `ConsumerImageState::kPreviousDisplayed`
  // in-flight)
  // + `kMaxDequeuedImages` (2 `ConsumerImageState::kPending` unimported)
  // + 2 (extra producer/reader slack slots) = 9.
  static constexpr int32_t kMaxImages = static_cast<int32_t>(
      kMaxQueuedImages + 1 + kMaxDequeuedImages + kMaxDequeuedImages + 2);
  static constexpr int kDefaultFenceWaitTimeoutMs = 32;

  static const Procs& GetProcs();
  static void SetProcsForTest(std::optional<Procs> overrides);

  static bool IsAvailable();

  /// Waits up to `timeout_ms` on `fence_fd` via `poll()` without closing
  /// `fence_fd` on `kTimedOut`.
  ///
  /// Ownership contract:
  /// - `kSignaled`: closes `fence_fd` (or accepts an already-invalid FD) and
  ///   returns `FenceWaitResult::kSignaled`.
  /// - `kTimedOut`: leaves `fence_fd` open and valid so the caller retains
  ///   ownership of the fence FD for retry or asynchronous readiness waiting.
  /// - `kError`: closes `fence_fd` on terminal poll failure (`POLLERR`,
  ///   `POLLNVAL`, or unrecoverable `poll()` error) and returns
  ///   `FenceWaitResult::kError`.
  static FenceWaitResult WaitAcquireFenceFdBounded(
      fml::UniqueFD& fence_fd,
      int timeout_ms = kDefaultFenceWaitTimeoutMs);

  static FenceWaitResult WaitAcquireFenceFdBounded(
      fml::UniqueFD&& fence_fd,
      int timeout_ms = kDefaultFenceWaitTimeoutMs) {
    return WaitAcquireFenceFdBounded(fence_fd, timeout_ms);
  }

  using PollFn = std::function<int(struct pollfd*, nfds_t, int)>;
  static void SetPollFnForTest(PollFn poll_fn);

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

  /// Schedules a new engine texture frame on `platform_task_runner_` when a
  /// previously timed-out acquire fence retained in `ImageExternalTexture`
  /// signals readiness, without mutating `is_displaying_ui_` or
  /// `latest_frame_only_mode_`.
  void NotifyPendingFenceSignaled();

  /// Marks the dequeued frame identified by `ahb` as actively imported and
  /// displayed by the GPU consumer (`dl_image_`). The corresponding `AImage*`
  /// is pinned in `dequeued_images_` in state `kDisplayed` and will NEVER be
  /// retired by subsequent dequeues, fence timeouts, supersedes, resizes, or
  /// repeated paints until a newer frame has also been imported and displayed.
  void NotifyFrameDisplayed(AHardwareBuffer* ahb);

  /// Immediately retires a dequeued `kPending` frame identified by `ahb` that
  /// was discarded without being displayed (for example because a newer frame
  /// superseded a timed-out `pending_frame_`, because fence polling returned
  /// `kError`, or because the texture detached), passing `unused_acquire_fence`
  /// as the `releaseFenceFd` argument to `AImage_deleteAsync` so the producer
  /// cannot overwrite the buffer before its own in-flight write completes.
  void RetireUnimportedFrame(
      AHardwareBuffer* ahb,
      fml::UniqueFD unused_acquire_fence = fml::UniqueFD());

  void SetFrameScheduledCallbackForTest(std::function<void(int64_t)> callback);

  void SetDisplayingFlutterUi(bool is_displaying_flutter_ui);

  void OnTrimMemoryCleanup();

  void Release();

  AcquiredFrame AcquireLatestFrame();

  int32_t GetNumImageReadersForTest();
  int32_t GetNumQueuedImagesForTest();
  int32_t GetPendingDequeuedImagesForTest();
  int32_t GetDisplayedImagesCountForTest();

 private:
  struct QueuedImage {
    AImage* image = nullptr;
    AHardwareBuffer* ahb = nullptr;
    fml::UniqueFD acquire_fence_fd;
  };

  struct PerReader;

  enum class ConsumerImageState {
    // Handed to the consumer via AcquireLatestFrame(), awaiting import
    // (NotifyFrameDisplayed) or discard/supersede/error
    // (RetireUnimportedFrame).
    kPending,
    // Imported into `dl_image_` and actively sampled by the GPU consumer.
    // Never retired until a newer frame has also been imported and displayed.
    kDisplayed,
    // Previously displayed image whose replacement has already been imported;
    // kept pinned across Impeller/Skia's in-flight GPU frame pipeline before
    // moving to `async_images_to_delete_`.
    kPreviousDisplayed,
  };

  struct DequeuedImage {
    AImage* image = nullptr;
    AHardwareBuffer* ahb = nullptr;
    PerReader* reader = nullptr;
    ConsumerImageState state = ConsumerImageState::kPending;
  };

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
  bool IsReaderReferencedByDequeuedImagesLocked(const PerReader* reader) const;
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
  std::atomic<bool> has_frame_scheduled_callback_for_test_{false};
  std::function<void(int64_t)> frame_scheduled_callback_for_test_;

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
  // GPU-Consumer Buffer Retirement & Ownership Invariant:
  //
  // 1. Consumer-State Slot Exclusion (`dequeued_images_`):
  //    An `AHardwareBuffer_acquire` reference alone protects kernel buffer
  //    allocation lifetime (preventing UAF on the memory object) but does NOT
  //    prevent Android's BufferQueue producer from re-dequeueing and writing
  //    into a released `AImage` slot. Producer overwrite exclusion is enforced
  //    by tracking explicit `ConsumerImageState` transitions in
  //    `dequeued_images_`:
  //      - `kPending`: handed to the consumer by `AcquireLatestFrame()`, held
  //        open while awaiting fence readiness / import. If superseded by a
  //        newer frame, rejected on `kError`, or dropped on detach,
  //        `RetireUnimportedFrame(ahb, unused_acquire_fence)` removes ONLY that
  //        unimported `kPending` entry and passes its still-open
  //        `acquire_fence_fd` as the `releaseFenceFd` argument to
  //        `AImage_deleteAsync`, delaying producer slot reuse until the
  //        producer's own in-flight write completes.
  //      - `kDisplayed`: imported into the consumer's active `dl_image_` via
  //        `NotifyFrameDisplayed(ahb)`. A `kDisplayed` image is NEVER evicted
  //        by subsequent dequeues, consecutive fence timeouts, or supersedes,
  //        guaranteeing the currently sampled buffer slot stays `ACQUIRED` and
  //        immune to producer overwrite across arbitrarily many repeated
  //        `Paint()` calls and multiple Flutter views.
  //      - `kPreviousDisplayed`: previously displayed images (up to
  //        `kMaxDequeuedImages = 2` entries) kept pinned after a newer frame is
  //        imported so in-flight GPU command buffers (`kMaxFramesInFlight = 2`)
  //        and multi-paint frames finish sampling before the slot moves to
  //        `async_images_to_delete_`.
  //
  // 2. Resize (`SetSize`) & Reader Rotation:
  //    `SetSize()` marks `create_new_reader_ = true` without retiring
  //    `dequeued_images_`. `CollectPrunableReadersLocked()` never closes or
  //    deletes any reader referenced by `last_reader_dequeued_from_` or by any
  //    live entry in `dequeued_images_`
  //    (`IsReaderReferencedByDequeuedImagesLocked`), so the reader backing the
  //    currently displayed `AImage*` remains alive until a replacement frame
  //    from the new reader is imported and the old image retires.
  //
  // 3. Cleanup / Shutdown (`OnTrimMemoryCleanup` / `Release`) & Exactly-Once
  //    Resource Release:
  //    Every `AImage*` transitions through disjoint owner containers under
  //    `mutex_` (`PerReader::image_queue` -> `dequeued_images_` ->
  //    `async_images_to_delete_` or immediate local retirement) via
  //    move-and-erase, guaranteeing each `AImage*` is passed to
  //    `DeleteQueuedImageAsync` at most/exactly once. Every `acquire_fence_fd`
  //    is owned by a single `fml::UniqueFD` at all times. Every caller
  //    `AHardwareBuffer*` ref acquired in `AcquireLatestFrame()` is paired
  //    1-for-1 with `AHardwareBuffer_release` when `ProcessFrame` finishes
  //    importing or discards `pending_frame_`.
  std::deque<DequeuedImage> dequeued_images_;
  std::deque<QueuedImage> async_images_to_delete_;
  std::deque<std::shared_ptr<PerReader>> async_readers_to_shutdown_;

  FML_DISALLOW_COPY_AND_ASSIGN(NdkSurfaceProducer);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_NDK_SURFACE_PRODUCER_H_
