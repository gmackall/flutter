// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/ndk_surface_producer.h"

#include <android/api-level.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <utility>

#include "flutter/fml/cpu_affinity.h"
#include "flutter/fml/logging.h"
#include "flutter/fml/trace_event.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/shell/platform/android/platform_view_android.h"

namespace flutter {

namespace {

std::atomic<int> g_last_ndk_reader_tid{0};

constexpr auto kDequeueRendezvousTimeout = std::chrono::microseconds(1500);
constexpr auto kAsyncCleanupIdleDelay = fml::TimeDelta::FromMilliseconds(32);

void DeleteQueuedImageAsync(AImage* image, fml::UniqueFD fence_fd) {
  if (image == nullptr) {
    return;
  }
  const auto& procs = impeller::android::GetProcTable();
  if (procs.AImage_deleteAsync.IsAvailable()) {
    procs.AImage_deleteAsync(image, fence_fd.release());
  } else if (procs.AImage_delete.IsAvailable()) {
    fence_fd.reset();
    procs.AImage_delete(image);
  }
}

}  // namespace

bool NdkSurfaceProducer::IsAvailable() {
  if (android_get_device_api_level() < 29) {
    return false;
  }
  const auto& procs = impeller::android::GetProcTable();
  return procs.IsValid() && procs.AImageReader_newWithUsage.IsAvailable() &&
         procs.AImageReader_setImageListener.IsAvailable() &&
         procs.AImageReader_getWindow.IsAvailable() &&
         procs.AImageReader_acquireNextImageAsync.IsAvailable() &&
         procs.AImageReader_delete.IsAvailable() &&
         procs.AImage_getHardwareBuffer.IsAvailable() &&
         procs.AImage_deleteAsync.IsAvailable() &&
         procs.AImage_delete.IsAvailable() &&
         procs.ANativeWindow_toSurface.IsAvailable();
}

int NdkSurfaceProducer::GetLastReaderThreadTid() {
  return g_last_ndk_reader_tid.load(std::memory_order_relaxed);
}

void NdkSurfaceProducer::PerReader::ShutdownReader() {
  if (shutdown_done) {
    return;
  }
  shutdown_done = true;
  const int prev_tid = reader_tid.exchange(0, std::memory_order_relaxed);
  if (prev_tid != 0) {
    int expected = prev_tid;
    g_last_ndk_reader_tid.compare_exchange_strong(expected, 0,
                                                  std::memory_order_relaxed);
  }
  const auto& procs = impeller::android::GetProcTable();
  if (reader != nullptr) {
    if (procs.AImageReader_setImageListener.IsAvailable()) {
      procs.AImageReader_setImageListener(reader, nullptr);
    }
  }
  for (auto& item : image_queue) {
    DeleteQueuedImageAsync(item.image, std::move(item.acquire_fence_fd));
  }
  image_queue.clear();
  java_surface.Reset();
  window = nullptr;
  if (reader != nullptr) {
    if (procs.AImageReader_delete.IsAvailable()) {
      procs.AImageReader_delete(reader);
    }
    reader = nullptr;
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
                                          int32_t height) {
  const auto& procs = impeller::android::GetProcTable();
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

  jobject local_surface = procs.ANativeWindow_toSurface(env, window);
  if (local_surface == nullptr) {
    FML_LOG(ERROR) << "ANativeWindow_toSurface returned null.";
    procs.AImageReader_delete(raw_reader);
    return nullptr;
  }

  auto per_reader = std::make_shared<PerReader>();
  per_reader->reader = raw_reader;
  per_reader->window = window;
  per_reader->java_surface.Reset(env, local_surface);
  env->DeleteLocalRef(local_surface);

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
  auto per_reader =
      CreatePerReaderLocked(env, requested_width_, requested_height_);
  if (!per_reader) {
    return fml::jni::ScopedJavaLocalRef<jobject>();
  }
  return fml::jni::ScopedJavaLocalRef<jobject>(per_reader->java_surface);
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

void NdkSurfaceProducer::SetDisplayingFlutterUi(bool is_displaying_flutter_ui) {
  is_displaying_ui_.store(is_displaying_flutter_ui, std::memory_order_relaxed);
}

void NdkSurfaceProducer::TriggerEngineTextureFrameOnPlatformThread() {
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
  thread_local bool t_affinity_requested = false;
  if (!t_affinity_requested) {
    t_affinity_requested = true;
    fml::RequestAffinity(fml::CpuAffinity::kNotEfficiency);
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

  const int tid = static_cast<int>(gettid());
  if (per_reader->reader_tid.load(std::memory_order_relaxed) != tid) {
    per_reader->reader_tid.store(tid, std::memory_order_relaxed);
  }
  int expected_tid = 0;
  g_last_ndk_reader_tid.compare_exchange_strong(expected_tid, tid,
                                                std::memory_order_relaxed);

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
  const auto& procs = impeller::android::GetProcTable();
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
    if (deferred_shutdown && platform_task_runner_) {
      platform_task_runner_->PostTask(
          [r = std::move(deferred_shutdown)]() { r->ShutdownReader(); });
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
    if (deferred_shutdown && platform_task_runner_) {
      platform_task_runner_->PostTask(
          [r = std::move(deferred_shutdown)]() { r->ShutdownReader(); });
    }
    return;
  }

  std::vector<QueuedImage> evicted;
  std::shared_ptr<PerReader> deferred_shutdown;
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
      });
      const size_t max_queued =
          latest_frame_only_mode_.load(std::memory_order_relaxed) ? 1u : 2u;
      while (per_reader->image_queue.size() > max_queued) {
        evicted.push_back(std::move(per_reader->image_queue.front()));
        per_reader->image_queue.pop_front();
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
  if (deferred_shutdown && platform_task_runner_) {
    platform_task_runner_->PostTask(
        [r = std::move(deferred_shutdown)]() { r->ShutdownReader(); });
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

void NdkSurfaceProducer::CollectPrunableReadersLocked(
    std::vector<std::shared_ptr<PerReader>>& out) {
  while (readers_.size() > 1u) {
    const auto& front = readers_.front();
    if (front->image_queue.empty() && front->in_flight_acquire == 0 &&
        last_reader_dequeued_from_ != front.get()) {
      front->closed = true;
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

      dequeued_images_.push_back(frame.image);
      last_reader_dequeued_from_ = reader.get();
      while (dequeued_images_.size() > kMaxDequeuedImages) {
        AImage* retired = dequeued_images_.front();
        dequeued_images_.pop_front();
        async_images_to_delete_.push_back(QueuedImage{
            .image = retired,
            .ahb = nullptr,
            .acquire_fence_fd = fml::UniqueFD(),
        });
        if (!async_cleanup_posted_) {
          async_cleanup_posted_ = true;
          should_post_idle_cleanup = true;
        }
      }

      result.ahardware_buffer = frame.ahb;
      result.acquire_fence_fd = std::move(frame.acquire_fence_fd);
      break;
    }

    if (readers_.size() > 1u) {
      std::vector<std::shared_ptr<PerReader>> pruned;
      CollectPrunableReadersLocked(pruned);
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
  has_been_acquired_with_image_.store(false, std::memory_order_release);
  std::vector<QueuedImage> images_to_delete;
  std::vector<std::shared_ptr<PerReader>> readers_to_shutdown;
  {
    std::lock_guard<std::mutex> lock(mutex_);
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

    for (AImage* img : dequeued_images_) {
      images_to_delete.push_back(QueuedImage{
          .image = img,
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
  for (const auto& r : readers_) {
    count += static_cast<int32_t>(r->image_queue.size());
  }
  return count;
}

int32_t NdkSurfaceProducer::GetPendingDequeuedImagesForTest() {
  std::lock_guard<std::mutex> lock(mutex_);
  return static_cast<int32_t>(dequeued_images_.size());
}

}  // namespace flutter
