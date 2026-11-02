// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture.h"

#include <android/hardware_buffer_jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <unistd.h>

#include "flutter/fml/platform/android/jni_util.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"

namespace flutter {

ImageExternalTexture::ImageExternalTexture(
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageLifecycle lifecycle)
    : Texture(id),
      image_texture_entry_(image_texture_entry),
      jni_facade_(jni_facade),
      texture_lifecycle_(lifecycle) {}

ImageExternalTexture::~ImageExternalTexture() {
  ClearPendingFrameAndWaiter();
}

void ImageExternalTexture::SetNdkSurfaceProducer(
    std::shared_ptr<NdkSurfaceProducer> ndk_producer) {
  std::lock_guard<std::mutex> lock(waiter_mutex_);
  ndk_producer_ = std::move(ndk_producer);
}

void ImageExternalTexture::SetPendingFrameReadyCallbackForTest(
    std::function<void()> callback) {
  std::lock_guard<std::mutex> lock(waiter_mutex_);
  on_pending_frame_ready_callback_ = std::move(callback);
}

// Implementing flutter::Texture.
void ImageExternalTexture::Paint(PaintContext& context,
                                 const DlRect& bounds,
                                 bool freeze,
                                 const DlImageSampling sampling) {
  if (state_ == AttachmentState::kDetached) {
    return;
  }
  Attach(context);
  const bool should_process_frame = !freeze;
  if (should_process_frame) {
    ProcessFrame(context, ToSkRect(bounds));
  }
  if (dl_image_) {
    context.canvas->DrawImageRect(
        dl_image_,                             // image
        DlRect::Make(dl_image_->GetBounds()),  // source rect
        bounds,                                // destination rect
        sampling,                              // sampling
        context.paint,                         // paint
        flutter::DlSrcRectConstraint::kStrict  // enforce edges
    );
  } else {
    FML_LOG(INFO) << "No DlImage available for ImageExternalTexture to paint.";
  }
}

// Implementing flutter::Texture.
void ImageExternalTexture::MarkNewFrameAvailable() {
  // NOOP.
}

// Implementing flutter::Texture.
void ImageExternalTexture::OnTextureUnregistered() {
  ClearPendingFrameAndWaiter();
}

// Implementing flutter::ContextListener.
void ImageExternalTexture::OnGrContextCreated() {
  state_ = AttachmentState::kUninitialized;
}

// Implementing flutter::ContextListener.
void ImageExternalTexture::OnGrContextDestroyed() {
  ClearPendingFrameAndWaiter();
  if (state_ == AttachmentState::kAttached) {
    switch (texture_lifecycle_) {
      case ImageLifecycle::kReset: {
        dl_image_.reset();
        image_lru_.Clear();
      } break;
      case ImageLifecycle::kKeepAlive:
        // Intentionally do nothing.
        ///
        // If we reset the image, we are not able to re-acquire it, but the
        // producer of the image will not know to reproduce it, resulting in a
        // blank image. See https://github.com/flutter/flutter/issues/163561.
        break;
    }
    Detach();
  }
  state_ = AttachmentState::kDetached;
}

JavaLocalRef ImageExternalTexture::AcquireLatestImage() {
  JNIEnv* env = fml::jni::AttachCurrentThread();
  FML_CHECK(env != nullptr);

  // ImageTextureEntry.acquireLatestImage.
  JavaLocalRef image_java =
      jni_facade_->ImageProducerTextureEntryAcquireLatestImage(
          JavaLocalRef(image_texture_entry_));
  return image_java;
}

PlatformViewAndroidJNI::AcquiredHardwareBuffer
ImageExternalTexture::AcquireLatestHardwareBuffer() {
  return jni_facade_->ImageProducerTextureEntryAcquireLatestHardwareBuffer(
      JavaLocalRef(image_texture_entry_), &cached_consumer_,
      &cached_is_surface_producer_);
}

ImageExternalTexture::AcquiredFrame ImageExternalTexture::AcquireLatestFrame() {
  if (ndk_producer_) {
    auto ndk_frame = ndk_producer_->AcquireLatestFrame();
    if (ndk_frame.ahardware_buffer == nullptr) {
      return {};
    }
    return AcquiredFrame{
        .java_hardware_buffer = JavaLocalRef(),
        .ahardware_buffer = ndk_frame.ahardware_buffer,
        .acquire_fence_fd = std::move(ndk_frame.acquire_fence_fd),
        .frame_generation = ndk_frame.frame_generation,
        .frame_size = ndk_frame.frame_size,
        .owned_by_producer = true,
        .caller_owns_ahb_ref = true,
        .acquired_from_ndk_producer = true,
    };
  }
  auto acquired = AcquireLatestHardwareBuffer();
  if (acquired.hardware_buffer.is_null()) {
    return {};
  }
  AHardwareBuffer* ahb = AHardwareBufferFor(acquired.hardware_buffer);
  if (ahb == nullptr) {
    if (!acquired.owned_by_producer) {
      CloseHardwareBuffer(acquired.hardware_buffer);
    }
    return {};
  }
  SkISize frame_size = SkISize::MakeEmpty();
  const auto& proc_table = impeller::android::GetProcTable();
  if (proc_table.AHardwareBuffer_describe.IsAvailable()) {
    AHardwareBuffer_Desc desc{};
    proc_table.AHardwareBuffer_describe(ahb, &desc);
    if (desc.width > 0 && desc.height > 0) {
      frame_size = SkISize::Make(static_cast<int32_t>(desc.width),
                                 static_cast<int32_t>(desc.height));
    }
  }
  uint64_t fallback_gen = 0;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    fallback_gen = ++fallback_frame_generation_;
  }
  return AcquiredFrame{
      .java_hardware_buffer = std::move(acquired.hardware_buffer),
      .ahardware_buffer = ahb,
      .acquire_fence_fd = fml::UniqueFD(),
      .frame_generation = fallback_gen,
      .frame_size = frame_size,
      .owned_by_producer = acquired.owned_by_producer,
      .caller_owns_ahb_ref = false,
      .acquired_from_ndk_producer = false,
  };
}

ImageExternalTexture::AcquiredFrame
ImageExternalTexture::AcquireNextOrPendingFrame() {
  AcquiredFrame fresh = AcquireLatestFrame();
  if (fresh.ahardware_buffer != nullptr) {
    if (pending_frame_.has_value()) {
      ClearPendingFrameAndWaiter();
    }
    if (!fresh.caller_owns_ahb_ref) {
      FML_DCHECK(!fresh.acquired_from_ndk_producer);
      const auto& proc_table = impeller::android::GetProcTable();
      if (proc_table.AHardwareBuffer_acquire.IsAvailable()) {
        proc_table.AHardwareBuffer_acquire(fresh.ahardware_buffer);
      }
      fresh.caller_owns_ahb_ref = true;
    }
    if (!fresh.java_hardware_buffer.is_null()) {
      if (!fresh.owned_by_producer) {
        CloseHardwareBuffer(fresh.java_hardware_buffer);
      }
      fresh.java_hardware_buffer.Reset();
    }
    fresh.is_pending_retry = false;
    return fresh;
  }

  if (pending_frame_.has_value()) {
    bool waiter_still_armed = false;
    {
      std::lock_guard<std::mutex> lock(waiter_mutex_);
      waiter_still_armed = waiter_active_for_pending_;
    }
    if (waiter_still_armed && pending_frame_->acquire_fence_fd.is_valid()) {
      // Do not re-block the raster thread for the full bounded timeout on
      // intermediate repaints (e.g. scrolling animations) while the async
      // waiter is already watching this fence. Probe readiness with a 0 ms
      // non-blocking poll: if still unsignaled, leave `pending_frame_` in place
      // and return immediately so the currently displayed `dl_image_` renders
      // at full frame rate; if already signaled or errored, hand the frame out
      // so it imports/retires on this frame without waiting an extra vsync.
      const auto probe = NdkSurfaceProducer::WaitAcquireFenceFdBounded(
          pending_frame_->acquire_fence_fd, /*timeout_ms=*/0);
      if (probe == NdkSurfaceProducer::FenceWaitResult::kTimedOut) {
        return {};
      }
      if (probe == NdkSurfaceProducer::FenceWaitResult::kError) {
        ClearPendingFrameAndWaiter();
        return {};
      }
    }
    AcquiredFrame pending = std::move(*pending_frame_);
    pending_frame_.reset();
    {
      std::lock_guard<std::mutex> lock(waiter_mutex_);
      pending_frame_generation_ = 0;
      pending_frame_size_ = SkISize::MakeEmpty();
    }
    pending.is_pending_retry = true;
    return pending;
  }

  return {};
}

void ImageExternalTexture::RetainPendingFrameOnTimeout(AcquiredFrame frame) {
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    pending_frame_generation_ = frame.frame_generation;
    pending_frame_size_ = frame.frame_size;
  }
  pending_frame_ = std::move(frame);
  if (pending_frame_->acquire_fence_fd.is_valid()) {
    StartPendingFrameWaiter(pending_frame_->acquire_fence_fd.get());
  }
}

void ImageExternalTexture::MarkFrameDisplayed(AcquiredFrame& frame) {
  frame.displayed = true;
  std::shared_ptr<NdkSurfaceProducer> producer;
  std::vector<fml::closure> ready_sync_callbacks;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    producer = ndk_producer_;
    const uint64_t next_gen = frame.frame_generation > 0
                                  ? frame.frame_generation
                                  : (displayed_frame_generation_ + 1);
    if (next_gen > displayed_frame_generation_) {
      displayed_frame_generation_ = next_gen;
    }
    SkISize resolved_size = frame.frame_size;
    if (resolved_size.isEmpty() && dl_image_) {
      resolved_size = SkISize::Make(dl_image_->GetBounds().GetWidth(),
                                    dl_image_->GetBounds().GetHeight());
    }
    if (!resolved_size.isEmpty()) {
      displayed_frame_size_ = resolved_size;
    }
    CollectSatisfiedSyncCallbacksLocked(ready_sync_callbacks);
  }
  if (frame.acquired_from_ndk_producer && frame.ahardware_buffer != nullptr &&
      producer) {
    producer->NotifyFrameDisplayed(frame.ahardware_buffer);
  }
  for (auto& cb : ready_sync_callbacks) {
    if (cb) {
      cb();
    }
  }
}

void ImageExternalTexture::NotifyPendingFrameReadyListeners() {
  std::shared_ptr<NdkSurfaceProducer> producer;
  std::function<void()> ready_callback;
  std::vector<fml::closure> ready_sync_callbacks;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    producer = ndk_producer_;
    ready_callback = on_pending_frame_ready_callback_;
    CollectSatisfiedSyncCallbacksLocked(ready_sync_callbacks);
  }
  if (producer) {
    producer->NotifyPendingFenceSignaled();
  }
  if (ready_callback) {
    ready_callback();
  }
  for (auto& cb : ready_sync_callbacks) {
    if (cb) {
      cb();
    }
  }
}

void ImageExternalTexture::StartPendingFrameWaiter(int fence_fd) {
  if (fence_fd < 0) {
    return;
  }
  // At most one waiter thread may be active per texture instance at any time
  // (`waiter_active_for_pending_` deduplication), and only on the slow-fence
  // path where a producer acquire fence exceeded `fence_wait_timeout_ms_`.
  std::thread stale_thread;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    if (waiter_active_for_pending_) {
      return;
    }
    stale_thread = std::move(waiter_thread_);
  }
  if (stale_thread.joinable()) {
    stale_thread.join();
  }

  int raw_dup = ::fcntl(fence_fd, F_DUPFD_CLOEXEC, 0);
  if (raw_dup < 0) {
    raw_dup = ::dup(fence_fd);
  }
  if (raw_dup < 0) {
    // Under severe process FD exhaustion where dup()/eventfd() cannot allocate
    // a waiter descriptor, fall back to scheduling a next-frame retry (bounded
    // to 1 non-blocking 0 ms check per vsync until the fence signals).
    FML_LOG(WARNING) << "Failed to dup() timed-out acquire fence FD (" << errno
                     << "); scheduling fallback retry.";
    NotifyPendingFrameReadyListeners();
    return;
  }
  fml::UniqueFD dup_fence(raw_dup);

  int raw_cancel_eventfd = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  if (raw_cancel_eventfd < 0) {
    FML_LOG(WARNING) << "Failed to create cancellation eventfd (" << errno
                     << "); scheduling fallback retry.";
    NotifyPendingFrameReadyListeners();
    return;
  }
  int raw_cancel_wait_fd = ::fcntl(raw_cancel_eventfd, F_DUPFD_CLOEXEC, 0);
  if (raw_cancel_wait_fd < 0) {
    raw_cancel_wait_fd = ::dup(raw_cancel_eventfd);
  }
  if (raw_cancel_wait_fd < 0) {
    ::close(raw_cancel_eventfd);
    FML_LOG(WARNING) << "Failed to dup() cancellation eventfd (" << errno
                     << "); scheduling fallback retry.";
    NotifyPendingFrameReadyListeners();
    return;
  }
  fml::UniqueFD cancel_wait(raw_cancel_wait_fd);
  fml::UniqueFD cancel_signal(raw_cancel_eventfd);

  std::lock_guard<std::mutex> lock(waiter_mutex_);
  const uint64_t generation = ++waiter_generation_;
  waiter_active_for_pending_ = true;
  waiter_cancel_write_fd_ = std::move(cancel_signal);
  waiter_thread_ = std::thread([this, generation,
                                dup_fence = std::move(dup_fence),
                                cancel_wait =
                                    std::move(cancel_wait)]() mutable {
    struct pollfd pfds[2] = {
        {.fd = dup_fence.get(), .events = POLLIN, .revents = 0},
        {.fd = cancel_wait.get(), .events = POLLIN, .revents = 0},
    };
    int ret = 0;
    do {
      pfds[0].revents = 0;
      pfds[1].revents = 0;
      ret = ::poll(pfds, 2, -1);
    } while (ret == -1 && errno == EINTR);

    if (ret < 0) {
      FML_LOG(WARNING) << "Async pending-fence waiter poll() failed with errno="
                       << errno
                       << "; scheduling retry to resolve pending frame.";
    }

    const bool canceled =
        ret > 0 &&
        (pfds[1].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL)) != 0;
    // Treat POLLERR | POLLHUP on the duplicated fence FD (or unexpected
    // poll failure ret < 0) as ready alongside POLLIN so the consumer wakes
    // up and `WaitAcquireFenceFdBounded` classifies terminal errors as
    // `kError`, retiring the unimported frame cleanly rather than leaving
    // it un-notified.
    const bool fence_ready_or_errored =
        !canceled &&
        (ret < 0 || (pfds[0].revents & (POLLIN | POLLERR | POLLHUP)) != 0);

    // Close waiter-owned FDs immediately upon poll wakeup so open FD count
    // returns to baseline before any observer or test callback executes.
    dup_fence.reset();
    cancel_wait.reset();

    std::shared_ptr<NdkSurfaceProducer> producer;
    std::function<void()> ready_callback;
    std::vector<fml::closure> sync_callbacks;
    bool should_notify = false;
    {
      std::lock_guard<std::mutex> lock(waiter_mutex_);
      if (waiter_generation_ == generation) {
        waiter_active_for_pending_ = false;
        if (fence_ready_or_errored) {
          should_notify = true;
          producer = ndk_producer_;
          ready_callback = on_pending_frame_ready_callback_;
          for (auto it = sync_waiters_.begin(); it != sync_waiters_.end();) {
            const bool gen_ok = it->min_generation == 0 ||
                                pending_frame_generation_ >= it->min_generation;
            const bool size_ok = it->expected_size.isEmpty() ||
                                 pending_frame_size_ == it->expected_size;
            if (pending_frame_generation_ > 0 && gen_ok && size_ok) {
              if (it->on_ready) {
                sync_callbacks.push_back(std::move(it->on_ready));
              }
              it = sync_waiters_.erase(it);
            } else {
              ++it;
            }
          }
        }
      }
    }
    if (should_notify) {
      if (producer) {
        producer->NotifyPendingFenceSignaled();
      }
      if (ready_callback) {
        ready_callback();
      }
      for (auto& cb : sync_callbacks) {
        if (cb) {
          cb();
        }
      }
    }
  });
}

void ImageExternalTexture::CancelPendingFrameWaiter() {
  std::thread thread_to_join;
  fml::UniqueFD cancel_signal;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    ++waiter_generation_;
    waiter_active_for_pending_ = false;
    cancel_signal = std::move(waiter_cancel_write_fd_);
    thread_to_join = std::move(waiter_thread_);
  }
  if (cancel_signal.is_valid()) {
    // Signal eventfd counter (never raises SIGPIPE even if waiter already
    // closed its duplicated descriptor upon fence completion).
    const uint64_t wake = 1;
    ssize_t written = 0;
    do {
      written = ::write(cancel_signal.get(), &wake, sizeof(wake));
    } while (written == -1 && errno == EINTR);
    cancel_signal.reset();
  }
  if (thread_to_join.joinable()) {
    thread_to_join.join();
  }
}

void ImageExternalTexture::ReleaseAcquiredFrame(AcquiredFrame& frame) {
  if (frame.acquired_from_ndk_producer && !frame.displayed &&
      frame.ahardware_buffer != nullptr) {
    std::shared_ptr<NdkSurfaceProducer> producer;
    {
      std::lock_guard<std::mutex> lock(waiter_mutex_);
      producer = ndk_producer_;
    }
    if (producer) {
      // Retire the never-displayed `kPending` AImage* immediately (passing its
      // unused acquire fence as the release fence to AImage_deleteAsync) so
      // superseded/errored/detached frames never evict the currently displayed
      // `kDisplayed` AImage*.
      producer->RetireUnimportedFrame(frame.ahardware_buffer,
                                      std::move(frame.acquire_fence_fd));
    } else {
      frame.acquire_fence_fd.reset();
    }
  } else {
    frame.acquire_fence_fd.reset();
  }
  if (frame.caller_owns_ahb_ref && frame.ahardware_buffer != nullptr) {
    if (frame.acquired_from_ndk_producer) {
      const auto& ndk_procs = NdkSurfaceProducer::GetProcs();
      if (ndk_procs.AHardwareBuffer_release != nullptr) {
        ndk_procs.AHardwareBuffer_release(frame.ahardware_buffer);
      }
    } else {
      const auto& proc_table = impeller::android::GetProcTable();
      if (proc_table.AHardwareBuffer_release.IsAvailable()) {
        proc_table.AHardwareBuffer_release(frame.ahardware_buffer);
      }
    }
    frame.caller_owns_ahb_ref = false;
  }
  if (!frame.java_hardware_buffer.is_null()) {
    if (!frame.owned_by_producer) {
      CloseHardwareBuffer(frame.java_hardware_buffer);
    }
    frame.java_hardware_buffer.Reset();
  }
  frame.ahardware_buffer = nullptr;
}

void ImageExternalTexture::ClearPendingFrameAndWaiter() {
  CancelPendingFrameWaiter();
  std::vector<TextureSyncWaiter> canceled_waiters;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    canceled_waiters.swap(sync_waiters_);
    pending_frame_generation_ = 0;
    pending_frame_size_ = SkISize::MakeEmpty();
  }
  canceled_waiters.clear();
  if (pending_frame_.has_value()) {
    ReleaseAcquiredFrame(*pending_frame_);
    pending_frame_.reset();
  }
}

uint64_t ImageExternalTexture::GetCurrentFrameGeneration() const {
  std::shared_ptr<NdkSurfaceProducer> producer;
  uint64_t displayed_gen = 0;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    producer = ndk_producer_;
    displayed_gen = displayed_frame_generation_;
  }
  if (producer) {
    return std::max(displayed_gen, producer->GetReadyFrameGeneration());
  }
  return displayed_gen;
}

SkISize ImageExternalTexture::GetCurrentFrameSize() const {
  std::shared_ptr<NdkSurfaceProducer> producer;
  SkISize displayed_size = SkISize::MakeEmpty();
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    producer = ndk_producer_;
    displayed_size = displayed_frame_size_;
  }
  if (producer) {
    const SkISize producer_size = producer->GetLatestReadyFrameSize();
    if (!producer_size.isEmpty()) {
      return producer_size;
    }
  }
  if (!displayed_size.isEmpty()) {
    return displayed_size;
  }
  if (dl_image_) {
    return SkISize::Make(dl_image_->GetBounds().GetWidth(),
                         dl_image_->GetBounds().GetHeight());
  }
  return SkISize::MakeEmpty();
}

bool ImageExternalTexture::DisplayedStateSatisfiesLocked(
    uint64_t min_generation,
    SkISize expected_size) const {
  auto matches = [min_generation, expected_size](uint64_t gen,
                                                 const SkISize& sz) {
    if (gen == 0) {
      return false;
    }
    if (min_generation > 0 && gen < min_generation) {
      return false;
    }
    if (!expected_size.isEmpty() && sz != expected_size) {
      return false;
    }
    return true;
  };

  // A retained pending frame whose acquire fence has already signaled
  // (`!waiter_active_for_pending_`) will import on the immediate next `Paint()`
  // with zero wait, so treat it as ready to prevent any false hold stall.
  if (!waiter_active_for_pending_ &&
      matches(pending_frame_generation_, pending_frame_size_)) {
    return true;
  }

  return matches(displayed_frame_generation_, displayed_frame_size_);
}

void ImageExternalTexture::CollectSatisfiedSyncCallbacksLocked(
    std::vector<fml::closure>& out_callbacks) {
  for (auto it = sync_waiters_.begin(); it != sync_waiters_.end();) {
    if (DisplayedStateSatisfiesLocked(it->min_generation, it->expected_size)) {
      if (it->on_ready) {
        out_callbacks.push_back(std::move(it->on_ready));
      }
      it = sync_waiters_.erase(it);
    } else {
      ++it;
    }
  }
}

bool ImageExternalTexture::SatisfiesSyncRequirement(
    uint64_t min_generation,
    SkISize expected_size) const {
  if (min_generation == 0 && expected_size.isEmpty()) {
    return true;
  }

  std::shared_ptr<NdkSurfaceProducer> producer;
  bool displayed_ok = false;
  bool ready_pending_ok = false;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    producer = ndk_producer_;
    displayed_ok = DisplayedStateSatisfiesLocked(min_generation, expected_size);
    if (!waiter_active_for_pending_ && pending_frame_generation_ > 0 &&
        (min_generation == 0 || pending_frame_generation_ >= min_generation) &&
        (expected_size.isEmpty() || pending_frame_size_ == expected_size)) {
      ready_pending_ok = true;
    }
  }

  // Non-NDK fallback textures (e.g. API < 29 or Java ImageReaderSurfaceProducer
  // without an NdkSurfaceProducer handle) do not have a producer-side async
  // readiness hook; satisfy immediately so fallback devices never stall waiting
  // for `max_wait`.
  if (!producer) {
    return true;
  }

  // Unified sync predicate (#613):
  // 1. If the producer's newest queued frame has a signaled fence, Paint will
  //    acquire and present that frame, so it alone decides satisfaction.
  // 2. Otherwise (the queue is empty or the newest queued frame's fence is
  //    still unsignaled), satisfy if `ready_pending_ok`, `displayed_ok`, or the
  //    producer's displayed state matches.
  if (producer->HasSignaledNewestQueuedFrame()) {
    return producer->SatisfiesSyncRequirement(min_generation, expected_size);
  }

  if (ready_pending_ok || displayed_ok ||
      producer->SatisfiesSyncRequirement(min_generation, expected_size)) {
    return true;
  }

  return false;
}

uint64_t ImageExternalTexture::RegisterSyncReadyCallback(
    uint64_t min_generation,
    SkISize expected_size,
    fml::closure on_ready) {
  if (!on_ready) {
    return 0;
  }

  std::shared_ptr<NdkSurfaceProducer> producer;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    if (state_ == AttachmentState::kDetached) {
      return 0;
    }
    producer = ndk_producer_;
  }

  // Non-NDK fallback textures or already-satisfied textures invoke immediately.
  if (!producer || SatisfiesSyncRequirement(min_generation, expected_size)) {
    on_ready();
    return 0;
  }

  auto fired = std::make_shared<std::atomic<bool>>(false);
  auto shared_cb = std::make_shared<fml::closure>(std::move(on_ready));
  fml::closure once_cb = [fired, shared_cb]() {
    if (!fired->exchange(true, std::memory_order_acq_rel)) {
      if (*shared_cb) {
        (*shared_cb)();
      }
      *shared_cb = nullptr;
    }
  };

  uint64_t assigned_token = 0;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    if (state_ == AttachmentState::kDetached) {
      return 0;
    }
    // Prune any previously fired one-shot entries so `sync_waiters_` stays
    // bounded even when producer callbacks fire without an intervening cancel.
    sync_waiters_.erase(
        std::remove_if(sync_waiters_.begin(), sync_waiters_.end(),
                       [](const TextureSyncWaiter& w) { return !w.on_ready; }),
        sync_waiters_.end());
    assigned_token = next_sync_waiter_token_++;
    sync_waiters_.push_back(TextureSyncWaiter{
        .token = assigned_token,
        .producer_token = 0,
        .min_generation = min_generation,
        .expected_size = expected_size,
        .on_ready = once_cb,
    });
  }

  const uint64_t producer_token = producer->RegisterSyncReadyCallback(
      min_generation, expected_size, std::move(once_cb));

  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    if (fired->load(std::memory_order_acquire) || producer_token == 0) {
      sync_waiters_.erase(
          std::remove_if(sync_waiters_.begin(), sync_waiters_.end(),
                         [assigned_token](const TextureSyncWaiter& w) {
                           return w.token == assigned_token;
                         }),
          sync_waiters_.end());
      return 0;
    }
    for (auto& w : sync_waiters_) {
      if (w.token == assigned_token) {
        w.producer_token = producer_token;
        break;
      }
    }
  }

  return assigned_token;
}

void ImageExternalTexture::CancelSyncReadyCallback(uint64_t token) {
  if (token == 0) {
    return;
  }
  std::shared_ptr<NdkSurfaceProducer> producer;
  uint64_t producer_token = 0;
  fml::closure extracted_cb;
  {
    std::lock_guard<std::mutex> lock(waiter_mutex_);
    producer = ndk_producer_;
    for (auto it = sync_waiters_.begin(); it != sync_waiters_.end(); ++it) {
      if (it->token == token) {
        producer_token = it->producer_token;
        extracted_cb = std::move(it->on_ready);
        sync_waiters_.erase(it);
        break;
      }
    }
  }
  if (producer && producer_token != 0) {
    producer->CancelSyncReadyCallback(producer_token);
  }
}

size_t ImageExternalTexture::GetSyncWaiterCountForTest() const {
  std::lock_guard<std::mutex> lock(waiter_mutex_);
  return sync_waiters_.size();
}

void ImageExternalTexture::CloseImage(const fml::jni::JavaRef<jobject>& image) {
  if (image.obj() == nullptr) {
    return;
  }
  jni_facade_->ImageClose(JavaLocalRef(image));
}

void ImageExternalTexture::CloseHardwareBuffer(
    const fml::jni::JavaRef<jobject>& hardware_buffer) {
  if (hardware_buffer.obj() == nullptr) {
    return;
  }
  jni_facade_->HardwareBufferClose(JavaLocalRef(hardware_buffer));
}

JavaLocalRef ImageExternalTexture::HardwareBufferFor(
    const fml::jni::JavaRef<jobject>& image) {
  if (image.obj() == nullptr) {
    return JavaLocalRef();
  }
  // Image.getHardwareBuffer.
  return jni_facade_->ImageGetHardwareBuffer(JavaLocalRef(image));
}

AHardwareBuffer* ImageExternalTexture::AHardwareBufferFor(
    const fml::jni::JavaRef<jobject>& hardware_buffer) {
  if (hardware_buffer.is_null()) {
    return nullptr;
  }
  JNIEnv* env = fml::jni::AttachCurrentThread();
  FML_CHECK(env != nullptr);
  const auto& proc =
      impeller::android::GetProcTable().AHardwareBuffer_fromHardwareBuffer;
  return proc ? proc(env, hardware_buffer.obj()) : nullptr;
}

}  // namespace flutter
