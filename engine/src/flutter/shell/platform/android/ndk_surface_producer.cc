// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/ndk_surface_producer.h"

#include <android/api-level.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <utility>

#include "flutter/fml/logging.h"
#include "flutter/fml/native_library.h"
#include "flutter/fml/trace_event.h"
#include "flutter/shell/platform/android/platform_view_android.h"

namespace flutter {

namespace {

constexpr auto kDequeueRendezvousTimeout = std::chrono::microseconds(1500);
constexpr auto kAsyncCleanupIdleDelay = fml::TimeDelta::FromMilliseconds(32);

std::mutex g_procs_override_mutex;
std::optional<NdkSurfaceProducer::Procs> g_procs_override;
std::mutex g_poll_override_mutex;
NdkSurfaceProducer::PollFn g_poll_override;

template <typename T>
void ResolveSymbol(const fml::RefPtr<fml::NativeLibrary>& lib,
                   const char* name,
                   T& out_fn) {
  if (!lib) {
    return;
  }
  auto sym = lib->ResolveFunction<T>(name);
  if (sym.has_value()) {
    out_fn = sym.value();
  }
}

NdkSurfaceProducer::Procs LoadDefaultProcs() {
  NdkSurfaceProducer::Procs procs;
  static const auto mediandk = fml::NativeLibrary::Create("libmediandk.so");
  static const auto android = fml::NativeLibrary::Create("libandroid.so");

  ResolveSymbol(mediandk, "AImageReader_newWithUsage",
                procs.AImageReader_newWithUsage);
  ResolveSymbol(mediandk, "AImageReader_delete", procs.AImageReader_delete);
  ResolveSymbol(mediandk, "AImageReader_getWindow",
                procs.AImageReader_getWindow);
  ResolveSymbol(mediandk, "AImageReader_setImageListener",
                procs.AImageReader_setImageListener);
  ResolveSymbol(mediandk, "AImageReader_acquireNextImageAsync",
                procs.AImageReader_acquireNextImageAsync);
  ResolveSymbol(mediandk, "AImage_delete", procs.AImage_delete);
  ResolveSymbol(mediandk, "AImage_deleteAsync", procs.AImage_deleteAsync);
  ResolveSymbol(mediandk, "AImage_getHardwareBuffer",
                procs.AImage_getHardwareBuffer);

  ResolveSymbol(android, "ANativeWindow_toSurface",
                procs.ANativeWindow_toSurface);
  ResolveSymbol(android, "AHardwareBuffer_acquire",
                procs.AHardwareBuffer_acquire);
  ResolveSymbol(android, "AHardwareBuffer_release",
                procs.AHardwareBuffer_release);
  ResolveSymbol(android, "AHardwareBuffer_describe",
                procs.AHardwareBuffer_describe);
  return procs;
}

bool IsFenceSignaledNonBlocking(int fence_fd) {
  if (fence_fd < 0) {
    return true;
  }
  NdkSurfaceProducer::PollFn poll_fn;
  {
    std::lock_guard<std::mutex> lock(g_poll_override_mutex);
    poll_fn = g_poll_override;
  }
  struct pollfd pfd = {
      .fd = fence_fd,
      .events = POLLIN,
      .revents = 0,
  };
  int ret = 0;
  do {
    pfd.revents = 0;
    ret = poll_fn ? poll_fn(&pfd, 1, 0) : ::poll(&pfd, 1, 0);
  } while (ret == -1 && errno == EINTR);
  return ret > 0 && (pfd.revents & POLLIN) != 0 &&
         (pfd.revents & (POLLERR | POLLNVAL)) == 0;
}

SkISize QueryHardwareBufferSize(AHardwareBuffer* ahb,
                                const NdkSurfaceProducer::Procs& procs,
                                int32_t fallback_width,
                                int32_t fallback_height) {
  bool using_test_procs_override = false;
  {
    std::lock_guard<std::mutex> lock(g_procs_override_mutex);
    using_test_procs_override = g_procs_override.has_value();
  }
  if (!using_test_procs_override && ahb != nullptr &&
      procs.AHardwareBuffer_describe != nullptr) {
    AHardwareBuffer_Desc desc{};
    procs.AHardwareBuffer_describe(ahb, &desc);
    if (desc.width > 0 && desc.height > 0) {
      return SkISize::Make(static_cast<int32_t>(desc.width),
                           static_cast<int32_t>(desc.height));
    }
  }
  return SkISize::Make(fallback_width, fallback_height);
}

// Deletes an AImage and transfers ownership of `fence_fd` to libmediandk via
// AImage_deleteAsync(image, releaseFenceFd).
//
// Note on superseded images: when draining multiple queued frames or evicting
// an unconsumed frame from `image_queue`, `fence_fd` is the producer's
// `acquire_fence_fd` for that superseded AImage. Passing the acquire fence as
// the `releaseFenceFd` argument to `AImage_deleteAsync` is safe and
// intentional: BufferQueue treats the release fence as the signal required
// before the buffer slot may be re-dequeued by the producer, so passing the
// producer's own acquire fence delays buffer slot reuse until the producer's
// in-flight write to that buffer has completed while transferring FD ownership
// to libmediandk to close without blocking the CPU.
void DeleteQueuedImageAsync(AImage* image, fml::UniqueFD fence_fd) {
  if (image == nullptr) {
    return;
  }
  const auto& procs = NdkSurfaceProducer::GetProcs();
  if (procs.AImage_deleteAsync != nullptr) {
    procs.AImage_deleteAsync(image, fence_fd.release());
  } else if (procs.AImage_delete != nullptr) {
    fence_fd.reset();
    procs.AImage_delete(image);
  }
}

}  // namespace

const NdkSurfaceProducer::Procs& NdkSurfaceProducer::GetProcs() {
  static const Procs kDefaultProcs = LoadDefaultProcs();
  std::lock_guard<std::mutex> lock(g_procs_override_mutex);
  if (g_procs_override.has_value()) {
    return *g_procs_override;
  }
  return kDefaultProcs;
}

void NdkSurfaceProducer::SetProcsForTest(std::optional<Procs> overrides) {
  std::lock_guard<std::mutex> lock(g_procs_override_mutex);
  g_procs_override = std::move(overrides);
}

bool NdkSurfaceProducer::IsAvailable() {
  if (android_get_device_api_level() < 29) {
    return false;
  }
  return GetProcs().IsValid();
}

void NdkSurfaceProducer::SetPollFnForTest(PollFn poll_fn) {
  std::lock_guard<std::mutex> lock(g_poll_override_mutex);
  g_poll_override = std::move(poll_fn);
}

NdkSurfaceProducer::FenceWaitResult
NdkSurfaceProducer::WaitAcquireFenceFdBounded(fml::UniqueFD& fence_fd,
                                              int timeout_ms) {
  if (!fence_fd.is_valid()) {
    return FenceWaitResult::kSignaled;
  }
  PollFn poll_fn;
  {
    std::lock_guard<std::mutex> lock(g_poll_override_mutex);
    poll_fn = g_poll_override;
  }
  const auto deadline =
      fml::TimePoint::Now() + fml::TimeDelta::FromMilliseconds(timeout_ms);
  struct pollfd pfd = {
      .fd = fence_fd.get(),
      .events = POLLIN,
      .revents = 0,
  };
  int ret = 0;
  for (;;) {
    int remaining_ms = 0;
    if (timeout_ms < 0) {
      remaining_ms = -1;
    } else if (timeout_ms > 0) {
      const auto now = fml::TimePoint::Now();
      if (now < deadline) {
        remaining_ms = std::max<int>(
            1,
            static_cast<int>(((deadline - now).ToMicroseconds() + 999) / 1000));
      }
    }
    pfd.revents = 0;
    ret = poll_fn ? poll_fn(&pfd, 1, remaining_ms)
                  : ::poll(&pfd, 1, remaining_ms);
    if (ret == -1 && errno == EINTR) {
      if (timeout_ms >= 0 && fml::TimePoint::Now() >= deadline) {
        ret = 0;
        break;
      }
      continue;
    }
    break;
  }
  if (ret == 0) {
    // Recoverable timeout: leave `fence_fd` open so the caller retains
    // ownership in `pending_frame_` and can retry once the fence signals.
    if (timeout_ms > 0) {
      FML_LOG(WARNING) << "Timed out waiting " << timeout_ms
                       << "ms for AImageReader acquire fence.";
    }
    return FenceWaitResult::kTimedOut;
  }
  if (ret < 0) {
    FML_LOG(WARNING) << "poll() on AImageReader acquire fence failed: "
                     << errno;
    fence_fd.reset();
    return FenceWaitResult::kError;
  }
  if ((pfd.revents & (POLLERR | POLLNVAL)) != 0 ||
      (pfd.revents & POLLIN) == 0) {
    FML_LOG(WARNING)
        << "poll() on AImageReader acquire fence returned error revents="
        << pfd.revents;
    fence_fd.reset();
    return FenceWaitResult::kError;
  }
  fence_fd.reset();
  return FenceWaitResult::kSignaled;
}

void NdkSurfaceProducer::PerReader::ShutdownReader() {
  if (shutdown_done.exchange(true, std::memory_order_acq_rel)) {
    return;
  }
  std::deque<QueuedImage> remaining_images;
  AImageReader* reader_to_delete = nullptr;
  {
    std::lock_guard<std::mutex> lock(shutdown_mutex);
    remaining_images = std::move(image_queue);
    image_queue.clear();
    reader_to_delete = reader;
    reader = nullptr;
    window = nullptr;
    java_surface.Reset();
  }
  const auto& procs = NdkSurfaceProducer::GetProcs();
  if (reader_to_delete != nullptr &&
      procs.AImageReader_setImageListener != nullptr) {
    procs.AImageReader_setImageListener(reader_to_delete, nullptr);
  }
  for (auto& item : remaining_images) {
    DeleteQueuedImageAsync(item.image, std::move(item.acquire_fence_fd));
  }
  if (reader_to_delete != nullptr && procs.AImageReader_delete != nullptr) {
    procs.AImageReader_delete(reader_to_delete);
  }
}

NdkSurfaceProducer::PerReader::~PerReader() {
  ShutdownReader();
}

NdkSurfaceProducer::NdkSurfaceProducer(
    int64_t texture_id,
    fml::WeakPtr<PlatformViewAndroid> platform_view,
    fml::RefPtr<fml::TaskRunner> platform_task_runner,
    bool is_displaying_flutter_ui)
    : texture_id_(texture_id),
      platform_view_(std::move(platform_view)),
      platform_task_runner_(std::move(platform_task_runner)),
      is_displaying_ui_(is_displaying_flutter_ui) {}

NdkSurfaceProducer::~NdkSurfaceProducer() {
  Release();
}

void NdkSurfaceProducer::SetSize(int32_t width, int32_t height) {
  width = std::max<int32_t>(1, width);
  height = std::max<int32_t>(1, height);
  std::lock_guard<std::mutex> lock(mutex_);
  if (requested_width_ == width && requested_height_ == height) {
    return;
  }
  requested_width_ = width;
  requested_height_ = height;
  create_new_reader_ = true;
}

std::shared_ptr<NdkSurfaceProducer::PerReader>
NdkSurfaceProducer::CreatePerReaderLocked(JNIEnv* env,
                                          int32_t width,
                                          int32_t height,
                                          bool create_java_surface) {
  const auto& procs = GetProcs();
  if (!procs.IsValid()) {
    return nullptr;
  }
  AImageReader* raw_reader = nullptr;
  const uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
  media_status_t status = procs.AImageReader_newWithUsage(
      width, height, AIMAGE_FORMAT_PRIVATE, usage, kMaxImages, &raw_reader);
  if (status != AMEDIA_OK || raw_reader == nullptr) {
    FML_LOG(ERROR) << "AImageReader_newWithUsage failed: " << status;
    return nullptr;
  }

  ANativeWindow* window = nullptr;
  status = procs.AImageReader_getWindow(raw_reader, &window);
  if (status != AMEDIA_OK || window == nullptr) {
    FML_LOG(ERROR) << "AImageReader_getWindow failed: " << status;
    procs.AImageReader_delete(raw_reader);
    return nullptr;
  }

  auto per_reader = std::make_shared<PerReader>();
  per_reader->reader = raw_reader;
  per_reader->window = window;
  per_reader->width = width;
  per_reader->height = height;

  if (create_java_surface) {
    if (env == nullptr) {
      procs.AImageReader_delete(raw_reader);
      return nullptr;
    }
    jobject local_surface = procs.ANativeWindow_toSurface(env, window);
    if (local_surface == nullptr) {
      FML_LOG(ERROR) << "ANativeWindow_toSurface returned null.";
      procs.AImageReader_delete(raw_reader);
      return nullptr;
    }
    per_reader->java_surface.Reset(env, local_surface);
    env->DeleteLocalRef(local_surface);
  }

  per_reader->listener_ctx = std::make_unique<ListenerContext>();
  per_reader->listener_ctx->producer = weak_from_this();
  per_reader->listener_ctx->reader = per_reader;
  per_reader->listener.context = per_reader->listener_ctx.get();
  per_reader->listener.onImageAvailable =
      &NdkSurfaceProducer::OnImageAvailableStatic;

  status =
      procs.AImageReader_setImageListener(raw_reader, &per_reader->listener);
  if (status != AMEDIA_OK) {
    FML_LOG(ERROR) << "AImageReader_setImageListener failed: " << status;
    per_reader->ShutdownReader();
    return nullptr;
  }

  readers_.push_back(per_reader);
  return per_reader;
}

fml::jni::ScopedJavaLocalRef<jobject> NdkSurfaceProducer::GetSurface(
    JNIEnv* env,
    bool force_new_reader) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (released_) {
    return fml::jni::ScopedJavaLocalRef<jobject>();
  }
  if (force_new_reader) {
    create_new_reader_ = true;
  }
  if (!create_new_reader_ && !readers_.empty()) {
    const auto& tail = readers_.back();
    if (!tail->closed && !tail->java_surface.is_null()) {
      return fml::jni::ScopedJavaLocalRef<jobject>(tail->java_surface);
    }
  }
  create_new_reader_ = false;
  auto per_reader = CreatePerReaderLocked(
      env, requested_width_, requested_height_, /*create_java_surface=*/true);
  if (!per_reader) {
    return fml::jni::ScopedJavaLocalRef<jobject>();
  }
  return fml::jni::ScopedJavaLocalRef<jobject>(per_reader->java_surface);
}

ANativeWindow* NdkSurfaceProducer::GetNativeWindowForTest(
    bool force_new_reader) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (released_) {
    return nullptr;
  }
  if (force_new_reader) {
    create_new_reader_ = true;
  }
  if (!create_new_reader_ && !readers_.empty()) {
    const auto& tail = readers_.back();
    if (!tail->closed && tail->window != nullptr) {
      return tail->window;
    }
  }
  create_new_reader_ = false;
  auto per_reader =
      CreatePerReaderLocked(/*env=*/nullptr, requested_width_,
                            requested_height_, /*create_java_surface=*/false);
  if (!per_reader) {
    return nullptr;
  }
  return per_reader->window;
}

void NdkSurfaceProducer::ScheduleFrameFromPlatform(
    bool is_displaying_flutter_ui) {
  latest_frame_only_mode_.store(true, std::memory_order_relaxed);
  is_displaying_ui_.store(is_displaying_flutter_ui, std::memory_order_relaxed);
  if (!platform_task_runner_) {
    return;
  }
  if (platform_task_runner_->RunsTasksOnCurrentThread()) {
    TriggerEngineTextureFrameOnPlatformThread();
    return;
  }
  platform_task_runner_->PostTask([weak = weak_from_this()]() {
    if (auto self = weak.lock()) {
      self->TriggerEngineTextureFrameOnPlatformThread();
    }
  });
}

void NdkSurfaceProducer::NotifyPendingFenceSignaled() {
  if (!platform_task_runner_) {
    return;
  }
  platform_task_runner_->PostTask([weak = weak_from_this()]() {
    if (auto self = weak.lock()) {
      bool is_released = false;
      {
        std::lock_guard<std::mutex> lock(self->mutex_);
        is_released = self->released_;
      }
      if (!is_released) {
        self->TriggerEngineTextureFrameOnPlatformThread();
      }
    }
  });
}

void NdkSurfaceProducer::NotifyFrameDisplayed(AHardwareBuffer* ahb) {
  if (ahb == nullptr) {
    return;
  }
  bool should_post_idle_cleanup = false;
  std::vector<fml::closure> ready_callbacks;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto displayed_it = dequeued_images_.end();
    for (auto it = dequeued_images_.begin(); it != dequeued_images_.end();
         ++it) {
      if (it->ahb == ahb) {
        displayed_it = it;
        break;
      }
    }
    if (displayed_it == dequeued_images_.end() ||
        displayed_it->state == ConsumerImageState::kDisplayed) {
      return;
    }

    // Demote any currently displayed entry to `kPreviousDisplayed` and mark
    // the newly imported frame as `kDisplayed`.
    for (auto& entry : dequeued_images_) {
      if (entry.state == ConsumerImageState::kDisplayed) {
        entry.state = ConsumerImageState::kPreviousDisplayed;
      }
    }
    displayed_it->state = ConsumerImageState::kDisplayed;
    if (displayed_it->frame_generation > ready_frame_generation_) {
      ready_frame_generation_ = displayed_it->frame_generation;
    }
    if (!displayed_it->frame_size.isEmpty()) {
      latest_ready_size_ = displayed_it->frame_size;
    }
    CollectReadySyncCallbacksLocked(ready_callbacks);

    // Retain up to `kMaxDequeuedImages` (2) previously displayed frames to
    // cover in-flight GPU command buffers (`kMaxFramesInFlight = 2`) and
    // multi-paint frames; move older `kPreviousDisplayed` entries to deferred
    // async deletion.
    size_t prev_count = 0;
    for (const auto& entry : dequeued_images_) {
      if (entry.state == ConsumerImageState::kPreviousDisplayed) {
        prev_count++;
      }
    }
    while (prev_count > kMaxDequeuedImages) {
      for (auto it = dequeued_images_.begin(); it != dequeued_images_.end();
           ++it) {
        if (it->state == ConsumerImageState::kPreviousDisplayed) {
          async_images_to_delete_.push_back(QueuedImage{
              .image = it->image,
              .ahb = nullptr,
              .acquire_fence_fd = fml::UniqueFD(),
          });
          dequeued_images_.erase(it);
          prev_count--;
          if (!async_cleanup_posted_) {
            async_cleanup_posted_ = true;
            should_post_idle_cleanup = true;
          }
          break;
        }
      }
    }

    if (readers_.size() > 1u) {
      std::vector<std::shared_ptr<PerReader>> pruned;
      std::vector<QueuedImage> pruned_images;
      CollectPrunableReadersLocked(pruned, pruned_images);
      for (auto& img : pruned_images) {
        async_images_to_delete_.push_back(std::move(img));
      }
      if (!pruned.empty()) {
        for (auto& r : pruned) {
          async_readers_to_shutdown_.push_back(std::move(r));
        }
        if (!async_cleanup_posted_) {
          async_cleanup_posted_ = true;
          should_post_idle_cleanup = true;
        }
      }
    }
  }
  if (should_post_idle_cleanup) {
    PostIdleAsyncCleanup();
  }
  for (auto& cb : ready_callbacks) {
    if (cb) {
      cb();
    }
  }
}

void NdkSurfaceProducer::RetireUnimportedFrame(
    AHardwareBuffer* ahb,
    fml::UniqueFD unused_acquire_fence) {
  if (ahb == nullptr) {
    unused_acquire_fence.reset();
    return;
  }
  AImage* image_to_retire = nullptr;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto it = dequeued_images_.begin(); it != dequeued_images_.end();
         ++it) {
      if (it->ahb == ahb && it->state == ConsumerImageState::kPending) {
        image_to_retire = it->image;
        dequeued_images_.erase(it);
        break;
      }
    }
  }
  if (image_to_retire != nullptr) {
    DeleteQueuedImageAsync(image_to_retire, std::move(unused_acquire_fence));
  } else {
    unused_acquire_fence.reset();
  }
}

void NdkSurfaceProducer::SetFrameScheduledCallbackForTest(
    std::function<void(int64_t)> callback) {
  std::lock_guard<std::mutex> lock(mutex_);
  const bool active = static_cast<bool>(callback);
  frame_scheduled_callback_for_test_ = std::move(callback);
  has_frame_scheduled_callback_for_test_.store(active,
                                               std::memory_order_release);
}

void NdkSurfaceProducer::SetDisplayingFlutterUi(bool is_displaying_flutter_ui) {
  is_displaying_ui_.store(is_displaying_flutter_ui, std::memory_order_relaxed);
}

void NdkSurfaceProducer::TriggerEngineTextureFrameOnPlatformThread() {
  if (has_frame_scheduled_callback_for_test_.load(std::memory_order_acquire)) {
    std::function<void(int64_t)> test_cb;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      test_cb = frame_scheduled_callback_for_test_;
    }
    if (test_cb) {
      test_cb(texture_id_);
    }
  }
  if (!platform_view_) {
    return;
  }
  if (is_displaying_ui_.load(std::memory_order_relaxed) &&
      has_been_acquired_with_image_.load(std::memory_order_acquire)) {
    platform_view_->MarkTextureFrameAvailable(texture_id_);
  } else {
    platform_view_->ScheduleFrame();
  }
}

void NdkSurfaceProducer::OnImageAvailableStatic(void* context,
                                                AImageReader* raw_reader) {
  if (context == nullptr) {
    return;
  }
  auto* ctx = static_cast<ListenerContext*>(context);
  auto producer = ctx->producer.lock();
  auto per_reader = ctx->reader.lock();
  if (!producer || !per_reader) {
    return;
  }
  producer->OnImageAvailable(per_reader, raw_reader);
}

void NdkSurfaceProducer::OnImageAvailable(
    const std::shared_ptr<PerReader>& per_reader,
    AImageReader* raw_reader) {
  TRACE_EVENT0("flutter", "NdkSurfaceProducer::OnImageAvailable");
  std::vector<QueuedImage> retired_images;
  std::vector<std::shared_ptr<PerReader>> retired_readers;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!async_images_to_delete_.empty()) {
      retired_images.assign(
          std::make_move_iterator(async_images_to_delete_.begin()),
          std::make_move_iterator(async_images_to_delete_.end()));
      async_images_to_delete_.clear();
    }
    if (!async_readers_to_shutdown_.empty()) {
      for (auto it = async_readers_to_shutdown_.begin();
           it != async_readers_to_shutdown_.end();) {
        if (it->get() != per_reader.get()) {
          retired_readers.push_back(std::move(*it));
          it = async_readers_to_shutdown_.erase(it);
        } else {
          ++it;
        }
      }
    }
    if (async_images_to_delete_.empty() && async_readers_to_shutdown_.empty()) {
      async_cleanup_posted_ = false;
    }
    if (released_ || per_reader->closed) {
      return;
    }
    per_reader->in_flight_acquire++;
  }

  for (auto& img : retired_images) {
    DeleteQueuedImageAsync(img.image, std::move(img.acquire_fence_fd));
  }
  for (auto& old_reader : retired_readers) {
    old_reader->ShutdownReader();
  }

  // Drain all currently available buffers via
  // AImageReader_acquireNextImageAsync rather than
  // AImageReader_acquireLatestImageAsync: in Android's libmediandk
  // implementation, AImageReader::acquireLatestImage passes the same `int*`
  // acquireFenceFd pointer to consecutive acquireNextImage calls, overwriting
  // fence_fd_1 with fence_fd_2 before calling prevImage->close(*acquireFenceFd)
  // (closing fence_fd_2 prematurely and returning a dangling FD whenever >= 2
  // buffers are queued). Looping manually with separate next_fence_fd locals
  // passes each superseded AImage's own fence FD to AImage_deleteAsync cleanly.
  const auto& procs = GetProcs();
  AImage* image = nullptr;
  int fence_fd = -1;
  while (true) {
    AImage* next_image = nullptr;
    int next_fence_fd = -1;
    media_status_t st = procs.AImageReader_acquireNextImageAsync(
        raw_reader, &next_image, &next_fence_fd);
    if (st != AMEDIA_OK || next_image == nullptr) {
      if (next_fence_fd >= 0) {
        ::close(next_fence_fd);
      }
      break;
    }
    if (image != nullptr) {
      DeleteQueuedImageAsync(image, fml::UniqueFD(fence_fd));
    }
    image = next_image;
    fence_fd = next_fence_fd;
  }

  if (image == nullptr) {
    std::shared_ptr<PerReader> deferred_shutdown;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      per_reader->in_flight_acquire--;
      if (waiting_dequeue_count_ > 0 && per_reader->in_flight_acquire == 0) {
        cv_.notify_all();
      }
      if (per_reader->close_when_idle && per_reader->in_flight_acquire == 0) {
        per_reader->close_when_idle = false;
        deferred_shutdown = per_reader;
      }
    }
    if (deferred_shutdown) {
      if (platform_task_runner_) {
        platform_task_runner_->PostTask(
            [r = std::move(deferred_shutdown)]() { r->ShutdownReader(); });
      } else {
        deferred_shutdown->ShutdownReader();
      }
    }
    return;
  }

  AHardwareBuffer* ahb = nullptr;
  media_status_t status = procs.AImage_getHardwareBuffer(image, &ahb);
  if (status != AMEDIA_OK || ahb == nullptr) {
    DeleteQueuedImageAsync(image, fml::UniqueFD(fence_fd));
    std::shared_ptr<PerReader> deferred_shutdown;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      per_reader->in_flight_acquire--;
      if (waiting_dequeue_count_ > 0 && per_reader->in_flight_acquire == 0) {
        cv_.notify_all();
      }
      if (per_reader->close_when_idle && per_reader->in_flight_acquire == 0) {
        per_reader->close_when_idle = false;
        deferred_shutdown = per_reader;
      }
    }
    if (deferred_shutdown) {
      if (platform_task_runner_) {
        platform_task_runner_->PostTask(
            [r = std::move(deferred_shutdown)]() { r->ShutdownReader(); });
      } else {
        deferred_shutdown->ShutdownReader();
      }
    }
    return;
  }

  const uint64_t frame_gen =
      produced_frame_generation_.fetch_add(1, std::memory_order_acq_rel) + 1;
  const SkISize frame_size = QueryHardwareBufferSize(
      ahb, procs, per_reader->width, per_reader->height);
  const bool fence_signaled_now = IsFenceSignaledNonBlocking(fence_fd);

  std::vector<QueuedImage> evicted;
  std::shared_ptr<PerReader> deferred_shutdown;
  std::vector<fml::closure> ready_callbacks;
  int raw_dup_watch_fd = -1;
  bool is_closed_or_released = false;
  bool woke_waiting_dequeue = false;
  bool should_post_schedule = false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    per_reader->in_flight_acquire--;
    if (per_reader->close_when_idle && per_reader->in_flight_acquire == 0) {
      per_reader->close_when_idle = false;
      deferred_shutdown = per_reader;
    }
    if (released_ || per_reader->closed) {
      is_closed_or_released = true;
    } else {
      per_reader->image_queue.push_back(QueuedImage{
          .image = image,
          .ahb = ahb,
          .acquire_fence_fd = fml::UniqueFD(fence_fd),
          .frame_generation = frame_gen,
          .frame_size = frame_size,
      });
      if (fence_signaled_now) {
        if (frame_gen > ready_frame_generation_) {
          ready_frame_generation_ = frame_gen;
        }
        if (!frame_size.isEmpty()) {
          latest_ready_size_ = frame_size;
        }
      }
      const size_t max_queued =
          latest_frame_only_mode_.load(std::memory_order_relaxed) ? 1u : 2u;
      while (per_reader->image_queue.size() > max_queued) {
        evicted.push_back(std::move(per_reader->image_queue.front()));
        per_reader->image_queue.pop_front();
      }
      if (!sync_waiters_.empty()) {
        CollectReadySyncCallbacksLocked(ready_callbacks);
        if (!sync_waiters_.empty() && !sync_watcher_active_) {
          raw_dup_watch_fd = FindUnsignaledMatchingFenceFdLocked();
        }
      }
    }
    if (waiting_dequeue_count_ > 0) {
      woke_waiting_dequeue = !is_closed_or_released;
      cv_.notify_all();
    }
    if (!is_closed_or_released && !woke_waiting_dequeue &&
        !schedule_from_reader_posted_) {
      schedule_from_reader_posted_ = true;
      should_post_schedule = true;
    }
  }

  if (is_closed_or_released) {
    DeleteQueuedImageAsync(image, fml::UniqueFD(fence_fd));
  }
  for (auto& item : evicted) {
    DeleteQueuedImageAsync(item.image, std::move(item.acquire_fence_fd));
  }
  if (deferred_shutdown) {
    if (platform_task_runner_) {
      platform_task_runner_->PostTask(
          [r = std::move(deferred_shutdown)]() { r->ShutdownReader(); });
    } else {
      deferred_shutdown->ShutdownReader();
    }
  }
  if (raw_dup_watch_fd >= 0) {
    StartSyncFenceWatcher(raw_dup_watch_fd);
  }
  for (auto& cb : ready_callbacks) {
    if (cb) {
      cb();
    }
  }
  if (should_post_schedule) {
    PostScheduleFromReader();
  }
}

bool NdkSurfaceProducer::HasQueuedImagesLocked() const {
  for (const auto& reader : readers_) {
    if (!reader->closed && !reader->image_queue.empty()) {
      return true;
    }
  }
  return false;
}

bool NdkSurfaceProducer::HasInFlightAcquireLocked() const {
  for (const auto& reader : readers_) {
    if (!reader->closed && reader->in_flight_acquire > 0) {
      return true;
    }
  }
  return false;
}

bool NdkSurfaceProducer::IsReaderReferencedByDequeuedImagesLocked(
    const PerReader* reader) const {
  if (reader == nullptr) {
    return false;
  }
  for (const auto& entry : dequeued_images_) {
    if (entry.reader == reader &&
        (entry.state == ConsumerImageState::kDisplayed ||
         entry.state == ConsumerImageState::kPreviousDisplayed)) {
      return true;
    }
  }
  return false;
}

void NdkSurfaceProducer::CollectPrunableReadersLocked(
    std::vector<std::shared_ptr<PerReader>>& out,
    std::vector<QueuedImage>& images_out) {
  while (readers_.size() > 1u) {
    const auto& front = readers_.front();
    if (front->image_queue.empty() && front->in_flight_acquire == 0 &&
        last_reader_dequeued_from_ != front.get() &&
        !IsReaderReferencedByDequeuedImagesLocked(front.get())) {
      front->closed = true;
      for (auto& item : front->image_queue) {
        images_out.push_back(std::move(item));
      }
      front->image_queue.clear();
      out.push_back(front);
      readers_.pop_front();
    } else {
      break;
    }
  }
}

NdkSurfaceProducer::AcquiredFrame NdkSurfaceProducer::AcquireLatestFrame() {
  TRACE_EVENT0("flutter", "NdkSurfaceProducer::AcquireLatestFrame");
  AcquiredFrame result;
  bool should_arm_next_vsync = false;
  bool should_post_idle_cleanup = false;
  const auto& procs = GetProcs();

  {
    std::unique_lock<std::mutex> lock(mutex_);
    if (released_) {
      return result;
    }

    if (!HasQueuedImagesLocked() && HasInFlightAcquireLocked()) {
      const auto deadline =
          std::chrono::steady_clock::now() + kDequeueRendezvousTimeout;
      waiting_dequeue_count_++;
      while (!released_ && !HasQueuedImagesLocked() &&
             HasInFlightAcquireLocked()) {
        if (cv_.wait_until(lock, deadline) == std::cv_status::timeout) {
          break;
        }
      }
      waiting_dequeue_count_--;
    }

    if (released_) {
      return result;
    }

    for (const auto& reader : readers_) {
      if (reader->closed || reader->image_queue.empty()) {
        continue;
      }
      QueuedImage frame = std::move(reader->image_queue.front());
      reader->image_queue.pop_front();

      // Track each dequeued `AImage*` by explicit consumer state:
      // - Entries in `kDisplayed` or `kPreviousDisplayed` are NEVER evicted by
      //   new dequeues or consecutive fence timeouts; they retire only when a
      //   newer frame has been imported and displayed via
      //   `NotifyFrameDisplayed()`.
      // - Unimported `kPending` frames superseded by a newer frame, rejected on
      //   `kError`, or dropped on detach are immediately retired with their own
      //   acquire fence via `RetireUnimportedFrame()`.
      // - For direct callers that invoke `AcquireLatestFrame()` without
      //   reporting `NotifyFrameDisplayed()`, bound un-acknowledged `kPending`
      //   entries to `kMaxDequeuedImages` (2) without ever touching
      //   `kDisplayed` or `kPreviousDisplayed` entries.
      size_t pending_count = 0;
      for (const auto& entry : dequeued_images_) {
        if (entry.state == ConsumerImageState::kPending) {
          pending_count++;
        }
      }
      while (pending_count >= kMaxDequeuedImages) {
        for (auto it = dequeued_images_.begin(); it != dequeued_images_.end();
             ++it) {
          if (it->state == ConsumerImageState::kPending) {
            async_images_to_delete_.push_back(QueuedImage{
                .image = it->image,
                .ahb = nullptr,
                .acquire_fence_fd = fml::UniqueFD(),
            });
            dequeued_images_.erase(it);
            pending_count--;
            if (!async_cleanup_posted_) {
              async_cleanup_posted_ = true;
              should_post_idle_cleanup = true;
            }
            break;
          }
        }
      }

      dequeued_images_.push_back(DequeuedImage{
          .image = frame.image,
          .ahb = frame.ahb,
          .reader = reader.get(),
          .state = ConsumerImageState::kPending,
          .frame_generation = frame.frame_generation,
          .frame_size = frame.frame_size,
      });
      last_reader_dequeued_from_ = reader.get();

      // Retain a caller-owned AHardwareBuffer reference while holding mutex_ so
      // a concurrent OnTrimMemoryCleanup() / Release() on the platform thread
      // cannot free the underlying buffer before ProcessFrame imports it.
      if (frame.ahb != nullptr && procs.AHardwareBuffer_acquire != nullptr) {
        procs.AHardwareBuffer_acquire(frame.ahb);
      }
      result.ahardware_buffer = frame.ahb;
      result.acquire_fence_fd = std::move(frame.acquire_fence_fd);
      result.frame_generation = frame.frame_generation;
      result.frame_size = frame.frame_size;
      break;
    }

    if (readers_.size() > 1u) {
      std::vector<std::shared_ptr<PerReader>> pruned;
      std::vector<QueuedImage> pruned_images;
      CollectPrunableReadersLocked(pruned, pruned_images);
      for (auto& img : pruned_images) {
        async_images_to_delete_.push_back(std::move(img));
      }
      if (!pruned.empty()) {
        for (auto& r : pruned) {
          async_readers_to_shutdown_.push_back(std::move(r));
        }
        if (!async_cleanup_posted_) {
          async_cleanup_posted_ = true;
          should_post_idle_cleanup = true;
        }
      }
    }

    if (HasQueuedImagesLocked() && !arm_next_vsync_posted_) {
      arm_next_vsync_posted_ = true;
      should_arm_next_vsync = true;
    }
  }

  if (result.ahardware_buffer != nullptr) {
    has_been_acquired_with_image_.store(true, std::memory_order_release);
  }
  if (should_post_idle_cleanup) {
    PostIdleAsyncCleanup();
  }
  if (should_arm_next_vsync) {
    PostArmNextVsync();
  }
  return result;
}

void NdkSurfaceProducer::PostScheduleFromReader() {
  if (!platform_task_runner_) {
    return;
  }
  fml::TaskRunner::RunNowOrPostTask(
      platform_task_runner_, [weak = weak_from_this()]() {
        auto self = weak.lock();
        if (!self) {
          return;
        }
        bool should_schedule = false;
        {
          std::lock_guard<std::mutex> lock(self->mutex_);
          self->schedule_from_reader_posted_ = false;
          if (self->released_) {
            return;
          }
          should_schedule = self->HasQueuedImagesLocked();
        }
        if (should_schedule) {
          self->TriggerEngineTextureFrameOnPlatformThread();
        }
      });
}

void NdkSurfaceProducer::PostArmNextVsync() {
  if (!platform_task_runner_) {
    return;
  }
  platform_task_runner_->PostTask([weak = weak_from_this()]() {
    auto self = weak.lock();
    if (!self) {
      return;
    }
    bool should_schedule = false;
    {
      std::lock_guard<std::mutex> lock(self->mutex_);
      self->arm_next_vsync_posted_ = false;
      if (self->released_) {
        return;
      }
      should_schedule = self->HasQueuedImagesLocked();
    }
    if (should_schedule) {
      self->TriggerEngineTextureFrameOnPlatformThread();
    }
  });
}

void NdkSurfaceProducer::PostIdleAsyncCleanup() {
  if (!platform_task_runner_) {
    return;
  }
  platform_task_runner_->PostDelayedTask(
      [weak = weak_from_this()]() {
        auto self = weak.lock();
        if (!self) {
          return;
        }
        std::vector<QueuedImage> images_to_delete;
        std::vector<std::shared_ptr<PerReader>> readers_to_shutdown;
        {
          std::lock_guard<std::mutex> lock(self->mutex_);
          self->async_cleanup_posted_ = false;
          images_to_delete.assign(
              std::make_move_iterator(self->async_images_to_delete_.begin()),
              std::make_move_iterator(self->async_images_to_delete_.end()));
          self->async_images_to_delete_.clear();
          readers_to_shutdown.assign(
              std::make_move_iterator(self->async_readers_to_shutdown_.begin()),
              std::make_move_iterator(self->async_readers_to_shutdown_.end()));
          self->async_readers_to_shutdown_.clear();
        }
        for (auto& img : images_to_delete) {
          DeleteQueuedImageAsync(img.image, std::move(img.acquire_fence_fd));
        }
        for (auto& r : readers_to_shutdown) {
          r->ShutdownReader();
        }
      },
      kAsyncCleanupIdleDelay);
}

void NdkSurfaceProducer::OnTrimMemoryCleanup() {
  CancelSyncFenceWatcher();
  has_been_acquired_with_image_.store(false, std::memory_order_release);
  std::vector<QueuedImage> images_to_delete;
  std::vector<std::shared_ptr<PerReader>> readers_to_shutdown;
  std::vector<SyncWaiter> canceled_waiters;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    canceled_waiters.swap(sync_waiters_);
    create_new_reader_ = true;
    schedule_from_reader_posted_ = false;
    arm_next_vsync_posted_ = false;
    async_cleanup_posted_ = false;
    last_reader_dequeued_from_ = nullptr;
    if (waiting_dequeue_count_ > 0) {
      cv_.notify_all();
    }

    for (auto& img : async_images_to_delete_) {
      images_to_delete.push_back(std::move(img));
    }
    async_images_to_delete_.clear();

    for (const auto& entry : dequeued_images_) {
      images_to_delete.push_back(QueuedImage{
          .image = entry.image,
          .ahb = nullptr,
          .acquire_fence_fd = fml::UniqueFD(),
      });
    }
    dequeued_images_.clear();

    for (auto& r : async_readers_to_shutdown_) {
      readers_to_shutdown.push_back(std::move(r));
    }
    async_readers_to_shutdown_.clear();

    for (auto& r : readers_) {
      r->closed = true;
      {
        std::lock_guard<std::mutex> qlock(r->shutdown_mutex);
        for (auto& item : r->image_queue) {
          images_to_delete.push_back(std::move(item));
        }
        r->image_queue.clear();
      }
      if (r->in_flight_acquire > 0) {
        r->close_when_idle = true;
      } else {
        readers_to_shutdown.push_back(std::move(r));
      }
    }
    readers_.clear();
  }

  for (auto& img : images_to_delete) {
    DeleteQueuedImageAsync(img.image, std::move(img.acquire_fence_fd));
  }
  for (auto& r : readers_to_shutdown) {
    r->ShutdownReader();
  }
}

void NdkSurfaceProducer::Release() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (released_) {
      return;
    }
    released_ = true;
  }
  OnTrimMemoryCleanup();
}

int32_t NdkSurfaceProducer::GetNumImageReadersForTest() {
  std::lock_guard<std::mutex> lock(mutex_);
  return static_cast<int32_t>(readers_.size());
}

int32_t NdkSurfaceProducer::GetNumQueuedImagesForTest() {
  std::lock_guard<std::mutex> lock(mutex_);
  int32_t count = 0;
  for (const auto& reader : readers_) {
    count += static_cast<int32_t>(reader->image_queue.size());
  }
  return count;
}

int32_t NdkSurfaceProducer::GetPendingDequeuedImagesForTest() {
  std::lock_guard<std::mutex> lock(mutex_);
  return static_cast<int32_t>(dequeued_images_.size());
}

int32_t NdkSurfaceProducer::GetDisplayedImagesCountForTest() {
  std::lock_guard<std::mutex> lock(mutex_);
  int32_t count = 0;
  for (const auto& entry : dequeued_images_) {
    if (entry.state == ConsumerImageState::kDisplayed) {
      count++;
    }
  }
  return count;
}

uint64_t NdkSurfaceProducer::GetReadyFrameGeneration() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return ready_frame_generation_;
}

SkISize NdkSurfaceProducer::GetLatestReadyFrameSize() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return latest_ready_size_;
}

namespace {

void JoinOrDetachThread(std::thread& thread) {
  if (!thread.joinable()) {
    return;
  }
  if (thread.get_id() == std::this_thread::get_id()) {
    thread.detach();
  } else {
    thread.join();
  }
}

}  // namespace

bool NdkSurfaceProducer::SatisfiesSyncRequirementLocked(uint64_t min_generation,
                                                        SkISize expected_size) {
  if (min_generation == 0 && expected_size.isEmpty()) {
    return true;
  }

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

  // Unified sync predicate (#613):
  // 1. If the newest queued frame's acquire fence is signaled, Paint will
  //    acquire and present that frame, so it alone decides satisfaction.
  // 2. Otherwise (the queue is empty, or the newest queued frame's fence is
  //    still unsignaled so PR #8 keeps displaying the current buffer), check
  //    the single most recent `kDisplayed` entry so steady-state frames when
  //    the displayed buffer already matches `expected_size` take the 0 ns fast
  //    path without holding or spawning a watcher thread.
  const QueuedImage* newest_queued = nullptr;
  for (const auto& reader : readers_) {
    if (!reader || reader->closed || reader->image_queue.empty()) {
      continue;
    }
    const QueuedImage& candidate = reader->image_queue.back();
    if (newest_queued == nullptr ||
        candidate.frame_generation >= newest_queued->frame_generation) {
      newest_queued = &candidate;
    }
  }

  if (newest_queued != nullptr &&
      IsFenceSignaledNonBlocking(newest_queued->acquire_fence_fd.get())) {
    if (!matches(newest_queued->frame_generation, newest_queued->frame_size)) {
      return false;
    }
    if (newest_queued->frame_generation > ready_frame_generation_) {
      ready_frame_generation_ = newest_queued->frame_generation;
    }
    if (!newest_queued->frame_size.isEmpty()) {
      latest_ready_size_ = newest_queued->frame_size;
    }
    return true;
  }

  for (auto it = dequeued_images_.rbegin(); it != dequeued_images_.rend();
       ++it) {
    if (it->state == ConsumerImageState::kDisplayed) {
      if (!matches(it->frame_generation, it->frame_size)) {
        return false;
      }
      if (it->frame_generation > ready_frame_generation_) {
        ready_frame_generation_ = it->frame_generation;
      }
      if (!it->frame_size.isEmpty()) {
        latest_ready_size_ = it->frame_size;
      }
      return true;
    }
  }

  if (newest_queued == nullptr) {
    return matches(ready_frame_generation_, latest_ready_size_);
  }

  return false;
}

bool NdkSurfaceProducer::HasSignaledNewestQueuedFrame() const {
  std::lock_guard<std::mutex> lock(mutex_);
  const QueuedImage* newest_queued = nullptr;
  for (const auto& reader : readers_) {
    if (!reader || reader->closed || reader->image_queue.empty()) {
      continue;
    }
    const QueuedImage& candidate = reader->image_queue.back();
    if (newest_queued == nullptr ||
        candidate.frame_generation >= newest_queued->frame_generation) {
      newest_queued = &candidate;
    }
  }
  return newest_queued != nullptr &&
         IsFenceSignaledNonBlocking(newest_queued->acquire_fence_fd.get());
}

bool NdkSurfaceProducer::SatisfiesSyncRequirement(uint64_t min_generation,
                                                  SkISize expected_size) {
  std::lock_guard<std::mutex> lock(mutex_);
  return SatisfiesSyncRequirementLocked(min_generation, expected_size);
}

void NdkSurfaceProducer::CollectReadySyncCallbacksLocked(
    std::vector<fml::closure>& out_callbacks) {
  for (auto it = sync_waiters_.begin(); it != sync_waiters_.end();) {
    if (SatisfiesSyncRequirementLocked(it->min_generation, it->expected_size)) {
      if (it->on_ready) {
        out_callbacks.push_back(std::move(it->on_ready));
      }
      it = sync_waiters_.erase(it);
    } else {
      ++it;
    }
  }
}

int NdkSurfaceProducer::FindUnsignaledMatchingFenceFdLocked() const {
  for (const auto& waiter : sync_waiters_) {
    for (const auto& reader : readers_) {
      if (!reader || reader->closed) {
        continue;
      }
      for (const auto& queued : reader->image_queue) {
        const bool gen_ok = waiter.min_generation == 0 ||
                            queued.frame_generation >= waiter.min_generation;
        const bool size_ok = waiter.expected_size.isEmpty() ||
                             queued.frame_size == waiter.expected_size;
        if (gen_ok && size_ok && queued.acquire_fence_fd.is_valid() &&
            !IsFenceSignaledNonBlocking(queued.acquire_fence_fd.get())) {
          int raw_dup =
              ::fcntl(queued.acquire_fence_fd.get(), F_DUPFD_CLOEXEC, 0);
          if (raw_dup < 0) {
            raw_dup = ::dup(queued.acquire_fence_fd.get());
          }
          if (raw_dup >= 0) {
            return raw_dup;
          }
        }
      }
    }
  }
  return -1;
}

uint64_t NdkSurfaceProducer::RegisterSyncReadyCallback(uint64_t min_generation,
                                                       SkISize expected_size,
                                                       fml::closure on_ready) {
  if (!on_ready) {
    return 0;
  }
  fml::closure canceled_callback;
  fml::closure immediate_callback;
  int raw_dup_watch_fd = -1;
  uint64_t assigned_token = 0;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (released_) {
      canceled_callback = std::move(on_ready);
      return 0;
    }
    if (SatisfiesSyncRequirementLocked(min_generation, expected_size)) {
      immediate_callback = std::move(on_ready);
    } else {
      assigned_token = next_sync_waiter_token_++;
      sync_waiters_.push_back(SyncWaiter{
          .token = assigned_token,
          .min_generation = min_generation,
          .expected_size = expected_size,
          .on_ready = std::move(on_ready),
      });
      if (!sync_watcher_active_) {
        raw_dup_watch_fd = FindUnsignaledMatchingFenceFdLocked();
      }
    }
  }
  if (immediate_callback) {
    immediate_callback();
    return 0;
  }
  if (raw_dup_watch_fd >= 0) {
    StartSyncFenceWatcher(raw_dup_watch_fd);
  }
  return assigned_token;
}

void NdkSurfaceProducer::CancelSyncReadyCallback(uint64_t token) {
  if (token == 0) {
    return;
  }
  fml::closure extracted_callback;
  fml::UniqueFD cancel_signal;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto it = sync_waiters_.begin(); it != sync_waiters_.end(); ++it) {
      if (it->token == token) {
        extracted_callback = std::move(it->on_ready);
        sync_waiters_.erase(it);
        break;
      }
    }
    // Signal the eventfd non-blockingly without joining on the caller (which
    // may be the high-priority raster thread). The watcher thread wakes
    // immediately and exits on its own; any residual std::thread handle is
    // reaped off the raster thread at the start of the next watcher thread or
    // in `CancelSyncFenceWatcher` on `Release()`.
    if (sync_waiters_.empty() && sync_watcher_active_) {
      ++sync_watcher_generation_;
      sync_watcher_active_ = false;
      cancel_signal = std::move(sync_watcher_cancel_fd_);
    }
  }
  if (cancel_signal.is_valid()) {
    const uint64_t wake = 1;
    ssize_t written = 0;
    do {
      written = ::write(cancel_signal.get(), &wake, sizeof(wake));
    } while (written == -1 && errno == EINTR);
    cancel_signal.reset();
  }
}

size_t NdkSurfaceProducer::GetSyncWaiterCountForTest() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return sync_waiters_.size();
}

uint64_t NdkSurfaceProducer::GetSyncWatcherPollIterationsForTest() const {
  return sync_watcher_poll_iterations_for_test_.load(std::memory_order_acquire);
}

size_t NdkSurfaceProducer::GetRetiredWatcherThreadCountForTest() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return retired_watcher_threads_.size();
}

void NdkSurfaceProducer::StartSyncFenceWatcher(int raw_dup_fence_fd) {
  if (raw_dup_fence_fd < 0) {
    return;
  }
  fml::UniqueFD dup_fence(raw_dup_fence_fd);

  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (released_ || sync_watcher_active_) {
      return;
    }
    // Never join on the caller (which may be the high-priority raster thread on
    // supersede). Park any previous handle in `retired_watcher_threads_` to be
    // reaped off the raster thread in `CancelSyncFenceWatcher` / `Release()`.
    if (sync_watcher_thread_.joinable()) {
      retired_watcher_threads_.push_back(std::move(sync_watcher_thread_));
    }
  }

  int raw_cancel_eventfd = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
  if (raw_cancel_eventfd < 0) {
    return;
  }
  int raw_cancel_wait_fd = ::fcntl(raw_cancel_eventfd, F_DUPFD_CLOEXEC, 0);
  if (raw_cancel_wait_fd < 0) {
    raw_cancel_wait_fd = ::dup(raw_cancel_eventfd);
  }
  if (raw_cancel_wait_fd < 0) {
    ::close(raw_cancel_eventfd);
    return;
  }
  fml::UniqueFD cancel_wait(raw_cancel_wait_fd);
  fml::UniqueFD cancel_signal(raw_cancel_eventfd);

  std::weak_ptr<NdkSurfaceProducer> weak_self = weak_from_this();
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (released_ || sync_watcher_active_) {
      return;
    }
    if (sync_watcher_thread_.joinable()) {
      retired_watcher_threads_.push_back(std::move(sync_watcher_thread_));
    }
    const uint64_t generation = ++sync_watcher_generation_;
    sync_watcher_active_ = true;
    sync_watcher_cancel_fd_ = std::move(cancel_signal);
    sync_watcher_thread_ =
        std::thread([weak_self, generation, dup_fence = std::move(dup_fence),
                     cancel_wait = std::move(cancel_wait)]() mutable {
          // Immediately reap any previously retired watcher thread handles off
          // the raster thread so finished std::thread stacks never accumulate
          // across long-lived sessions (`retired_watcher_threads_.size() ==
          // 0`).
          if (auto initial_self = weak_self.lock()) {
            std::vector<std::thread> to_reap;
            {
              std::lock_guard<std::mutex> lock(initial_self->mutex_);
              to_reap.swap(initial_self->retired_watcher_threads_);
            }
            for (auto& t : to_reap) {
              JoinOrDetachThread(t);
            }
          }
          while (dup_fence.is_valid()) {
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

            const bool canceled =
                ret > 0 && (pfds[1].revents &
                            (POLLIN | POLLHUP | POLLERR | POLLNVAL)) != 0;

            // Release the watched fence FD immediately upon wakeup so open FD
            // count returns to baseline before any observer callback executes.
            dup_fence.reset();

            auto self = weak_self.lock();
            if (!self) {
              cancel_wait.reset();
              return;
            }
            self->sync_watcher_poll_iterations_for_test_.fetch_add(
                1, std::memory_order_release);

            std::vector<fml::closure> ready_callbacks;
            int next_raw_dup_fd = -1;
            {
              std::lock_guard<std::mutex> lock(self->mutex_);
              if (self->sync_watcher_generation_ != generation || canceled ||
                  self->released_) {
                if (self->sync_watcher_generation_ == generation) {
                  self->sync_watcher_active_ = false;
                  self->sync_watcher_cancel_fd_.reset();
                }
                cancel_wait.reset();
                return;
              }
              self->CollectReadySyncCallbacksLocked(ready_callbacks);
              if (!self->sync_waiters_.empty()) {
                next_raw_dup_fd = self->FindUnsignaledMatchingFenceFdLocked();
              }
              if (next_raw_dup_fd >= 0) {
                dup_fence = fml::UniqueFD(next_raw_dup_fd);
              } else {
                self->sync_watcher_active_ = false;
                self->sync_watcher_cancel_fd_.reset();
                cancel_wait.reset();
              }
            }
            for (auto& cb : ready_callbacks) {
              if (cb) {
                cb();
              }
            }
          }
          cancel_wait.reset();
        });
  }
}

void NdkSurfaceProducer::CancelSyncFenceWatcher() {
  std::vector<std::thread> retired_threads;
  std::thread thread_to_join;
  fml::UniqueFD cancel_signal;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    ++sync_watcher_generation_;
    sync_watcher_active_ = false;
    cancel_signal = std::move(sync_watcher_cancel_fd_);
    retired_threads = std::move(retired_watcher_threads_);
    thread_to_join = std::move(sync_watcher_thread_);
  }
  if (cancel_signal.is_valid()) {
    const uint64_t wake = 1;
    ssize_t written = 0;
    do {
      written = ::write(cancel_signal.get(), &wake, sizeof(wake));
    } while (written == -1 && errno == EINTR);
    cancel_signal.reset();
  }
  for (auto& t : retired_threads) {
    JoinOrDetachThread(t);
  }
  JoinOrDetachThread(thread_to_join);
}

}  // namespace flutter
