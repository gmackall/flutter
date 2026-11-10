// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/ndk_image_reader.h"

#include <android/api-level.h>
#include <errno.h>
#include <poll.h>
#include <unistd.h>

#include <algorithm>
#include <utility>

#include "flutter/fml/logging.h"
#include "flutter/fml/time/time_delta.h"
#include "flutter/fml/time/time_point.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/shell/common/platform_view.h"
#include "flutter/shell/platform/android/image_external_texture.h"

namespace flutter {

namespace {

void ReleaseAcquiredHardwareBuffer(AHardwareBuffer* buffer) {
  if (buffer == nullptr) {
    return;
  }
  const auto& procs = impeller::android::GetProcTable();
  if (procs.AHardwareBuffer_release.IsAvailable()) {
    procs.AHardwareBuffer_release(buffer);
  }
}

NdkImageReader::UniqueAHardwareBuffer RetainHardwareBuffer(
    AHardwareBuffer* buffer) {
  if (buffer == nullptr) {
    return {nullptr, nullptr};
  }
  const auto& procs = impeller::android::GetProcTable();
  if (!procs.AHardwareBuffer_acquire.IsAvailable()) {
    return {nullptr, nullptr};
  }
  procs.AHardwareBuffer_acquire(buffer);
  return {buffer, &ReleaseAcquiredHardwareBuffer};
}

}  // namespace

bool WaitAcquireFenceFd(fml::UniqueFD& fence_fd, int timeout_ms) {
  if (!fence_fd.is_valid()) {
    return true;
  }
  const fml::TimePoint deadline =
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
      const fml::TimePoint now = fml::TimePoint::Now();
      if (now < deadline) {
        remaining_ms = std::max<int>(
            1,
            static_cast<int>(((deadline - now).ToMicroseconds() + 999) / 1000));
      }
    }
    pfd.revents = 0;
    ret = ::poll(&pfd, 1, remaining_ms);
    if (ret == -1 && errno == EINTR) {
      if (timeout_ms >= 0 && fml::TimePoint::Now() >= deadline) {
        ret = 0;
        break;
      }
      continue;
    }
    break;
  }
  fence_fd.reset();
  if (ret <= 0) {
    FML_LOG(WARNING) << "WaitAcquireFenceFd failed or timed out (ret=" << ret
                     << ", errno=" << errno << ").";
    return false;
  }
  return (pfd.revents & (POLLERR | POLLNVAL)) == 0 &&
         (pfd.revents & POLLIN) != 0;
}

uint64_t NdkImageReader::NextGeneration() {
  static std::atomic<uint64_t> g_next_generation{1};
  return g_next_generation.fetch_add(1, std::memory_order_relaxed);
}

bool NdkImageReader::IsAvailable(bool require_java_surface) {
  if (android_get_device_api_level() < 29) {
    return false;
  }
  const auto& procs = impeller::android::GetProcTable();
  const bool base_ok =
      procs.IsValid() && procs.AImageReader_newWithUsage.IsAvailable() &&
      procs.AImageReader_delete.IsAvailable() &&
      procs.AImageReader_getWindow.IsAvailable() &&
      procs.AImageReader_acquireLatestImageAsync.IsAvailable() &&
      procs.AImageReader_setImageListener.IsAvailable() &&
      procs.AImageReader_setBufferRemovedListener.IsAvailable() &&
      procs.AImage_delete.IsAvailable() &&
      procs.AImage_getHardwareBuffer.IsAvailable();
  if (!base_ok) {
    return false;
  }
  return !require_java_surface || procs.ANativeWindow_toSurface.IsAvailable();
}

std::shared_ptr<NdkImageReader> NdkImageReader::Create(
    JNIEnv* env,
    int32_t width,
    int32_t height,
    std::shared_ptr<NdkImageTextureBridge> bridge,
    jobject* out_java_surface) {
  const bool need_surface = env != nullptr && out_java_surface != nullptr;
  if (!bridge || !IsAvailable(need_surface)) {
    return nullptr;
  }
  width = std::max<int32_t>(1, width);
  height = std::max<int32_t>(1, height);

  const auto& procs = impeller::android::GetProcTable();
  AImageReader* raw_reader = nullptr;
  media_status_t status = procs.AImageReader_newWithUsage(
      width, height, AIMAGE_FORMAT_PRIVATE,
      AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, kMaxImages, &raw_reader);
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

  jobject local_surface = nullptr;
  if (need_surface) {
    local_surface = procs.ANativeWindow_toSurface(env, window);
    if (local_surface == nullptr) {
      FML_LOG(ERROR) << "ANativeWindow_toSurface returned null.";
      procs.AImageReader_delete(raw_reader);
      return nullptr;
    }
  }

  auto instance = std::shared_ptr<NdkImageReader>(new NdkImageReader(
      NextGeneration(), raw_reader, window, std::move(bridge)));

  instance->image_listener_.context = instance.get();
  instance->image_listener_.onImageAvailable =
      &NdkImageReader::OnImageAvailableCallback;
  status = procs.AImageReader_setImageListener(raw_reader,
                                               &instance->image_listener_);
  if (status != AMEDIA_OK) {
    if (local_surface != nullptr) {
      env->DeleteLocalRef(local_surface);
    }
    instance->CloseOnPlatformThread();
    return nullptr;
  }

  instance->buffer_removed_listener_.context = instance.get();
  instance->buffer_removed_listener_.onBufferRemoved =
      &NdkImageReader::OnBufferRemovedCallback;
  status = procs.AImageReader_setBufferRemovedListener(
      raw_reader, &instance->buffer_removed_listener_);
  if (status != AMEDIA_OK) {
    if (local_surface != nullptr) {
      env->DeleteLocalRef(local_surface);
    }
    instance->CloseOnPlatformThread();
    return nullptr;
  }

  instance->bridge_->AddReader(instance);
  if (out_java_surface != nullptr) {
    *out_java_surface = local_surface;
  }
  return instance;
}

NdkImageReader::NdkImageReader(uint64_t generation,
                               AImageReader* reader,
                               ANativeWindow* window,
                               std::shared_ptr<NdkImageTextureBridge> bridge)
    : generation_(generation),
      bridge_(std::move(bridge)),
      reader_(reader),
      window_(window) {}

NdkImageReader::~NdkImageReader() {
  CloseOnPlatformThread();
}

size_t NdkImageReader::OpenImageCountForTest() const {
  std::lock_guard<std::mutex> lock(reader_mutex_);
  return open_images_.size();
}

void NdkImageReader::OnImageAvailableCallback(void* context,
                                              AImageReader* /*reader*/) {
  if (context == nullptr) {
    return;
  }
  auto* self = static_cast<NdkImageReader*>(context);
  if (self->close_requested_.load(std::memory_order_acquire)) {
    return;
  }
  self->has_pending_image_.store(true, std::memory_order_release);
  if (self->bridge_) {
    self->bridge_->NotifyImageAvailable();
  }
}

void NdkImageReader::OnBufferRemovedCallback(void* context,
                                             AImageReader* /*reader*/,
                                             AHardwareBuffer* buffer) {
  if (context == nullptr || buffer == nullptr) {
    return;
  }
  auto* self = static_cast<NdkImageReader*>(context);
  auto id = impeller::android::HardwareBuffer::GetSystemUniqueID(buffer);
  if (self->bridge_ && id.has_value()) {
    self->bridge_->EnqueueBufferRemoved(self->generation_, id.value());
  }
}

NdkImageReader::AcquiredNativeImage NdkImageReader::AcquireLatestImage() {
  if (close_requested_.load(std::memory_order_acquire)) {
    return {};
  }
  std::lock_guard<std::mutex> lock(reader_mutex_);
  if (reader_ == nullptr || close_requested_.load(std::memory_order_acquire)) {
    return {};
  }

  has_pending_image_.store(false, std::memory_order_release);

  const auto& procs = impeller::android::GetProcTable();
  AImage* image = nullptr;
  int raw_fence_fd = -1;
  media_status_t status = procs.AImageReader_acquireLatestImageAsync(
      reader_, &image, &raw_fence_fd);
  if (status != AMEDIA_OK || image == nullptr) {
    if (raw_fence_fd >= 0) {
      ::close(raw_fence_fd);
    }
    return {};
  }

  fml::UniqueFD acquire_fence(raw_fence_fd);
  AHardwareBuffer* ahb = nullptr;
  status = procs.AImage_getHardwareBuffer(image, &ahb);
  if (status != AMEDIA_OK || ahb == nullptr) {
    if (procs.AImage_deleteAsync.IsAvailable() && acquire_fence.is_valid()) {
      procs.AImage_deleteAsync(image, acquire_fence.release());
    } else {
      acquire_fence.reset();
      procs.AImage_delete(image);
    }
    return {};
  }

  auto owned_ref = RetainHardwareBuffer(ahb);

  open_images_.push_back(image);
  while (open_images_.size() > static_cast<size_t>(kMaxDequeuedImages)) {
    AImage* oldest = open_images_.front();
    open_images_.pop_front();
    procs.AImage_delete(oldest);
  }

  return AcquiredNativeImage{
      .reader_generation = generation_,
      .hardware_buffer = ahb,
      .owned_buffer_ref = std::move(owned_ref),
      .acquire_fence_fd = std::move(acquire_fence),
  };
}

void NdkImageReader::RequestClose() {
  if (!close_requested_.exchange(true, std::memory_order_acq_rel)) {
    if (bridge_) {
      bridge_->RemoveReader(generation_);
      bridge_->EnqueueGenerationClosed(generation_);
    }
  }
  const auto& platform_runner =
      bridge_ ? bridge_->platform_task_runner() : nullptr;
  if (!platform_runner || platform_runner->RunsTasksOnCurrentThread()) {
    CloseOnPlatformThread();
    return;
  }
  std::shared_ptr<NdkImageReader> self = shared_from_this();
  platform_runner->PostTask([self]() { self->CloseOnPlatformThread(); });
}

void NdkImageReader::CloseOnPlatformThread() {
  if (!close_requested_.exchange(true, std::memory_order_acq_rel)) {
    if (bridge_) {
      bridge_->RemoveReader(generation_);
      bridge_->EnqueueGenerationClosed(generation_);
    }
  }
  if (closed_.exchange(true, std::memory_order_acq_rel)) {
    return;
  }

  const auto& procs = impeller::android::GetProcTable();
  AImageReader* to_delete = nullptr;
  {
    std::lock_guard<std::mutex> lock(reader_mutex_);
    for (AImage* img : open_images_) {
      if (img != nullptr && procs.AImage_delete.IsAvailable()) {
        procs.AImage_delete(img);
      }
    }
    open_images_.clear();
    to_delete = reader_;
    reader_ = nullptr;
    window_ = nullptr;
  }

  if (to_delete != nullptr) {
    if (procs.AImageReader_setImageListener.IsAvailable()) {
      procs.AImageReader_setImageListener(to_delete, nullptr);
    }
    if (procs.AImageReader_setBufferRemovedListener.IsAvailable()) {
      procs.AImageReader_setBufferRemovedListener(to_delete, nullptr);
    }
    if (procs.AImageReader_delete.IsAvailable()) {
      procs.AImageReader_delete(to_delete);
    }
  }
}

NdkImageTextureBridge::NdkImageTextureBridge(
    int64_t texture_id,
    fml::WeakPtr<PlatformView> platform_view,
    fml::RefPtr<fml::TaskRunner> platform_task_runner,
    fml::RefPtr<fml::TaskRunner> raster_task_runner,
    std::function<void()> schedule_frame_override)
    : texture_id_(texture_id),
      platform_view_(std::move(platform_view)),
      platform_task_runner_(std::move(platform_task_runner)),
      raster_task_runner_(std::move(raster_task_runner)),
      schedule_frame_override_(std::move(schedule_frame_override)) {}

void NdkImageTextureBridge::BindTexture(
    const std::shared_ptr<ImageExternalTexture>& texture) {
  weak_texture_raster_only_ = texture;
  if (texture) {
    std::lock_guard<std::mutex> lock(eviction_mutex_);
    if (!pending_buffers_.empty() || !pending_closed_generations_.empty()) {
      PostCoalescedRasterDrainLocked();
    }
  }
}

void NdkImageTextureBridge::AddReader(
    const std::shared_ptr<NdkImageReader>& reader) {
  if (!reader) {
    return;
  }
  std::vector<std::shared_ptr<NdkImageReader>> to_close;
  {
    std::lock_guard<std::mutex> lock(readers_mutex_);
    while (active_readers_.size() >= 4 && active_readers_.front() &&
           !active_readers_.front()->HasPendingImage()) {
      to_close.push_back(std::move(active_readers_.front()));
      active_readers_.erase(active_readers_.begin());
    }
    active_readers_.push_back(reader);
  }
  for (const auto& old_reader : to_close) {
    old_reader->RequestClose();
  }
}

void NdkImageTextureBridge::RemoveReader(uint64_t generation) {
  std::lock_guard<std::mutex> lock(readers_mutex_);
  active_readers_.erase(
      std::remove_if(active_readers_.begin(), active_readers_.end(),
                     [generation](const std::shared_ptr<NdkImageReader>& r) {
                       return !r || r->generation() == generation;
                     }),
      active_readers_.end());
}

bool NdkImageTextureBridge::HasReaders() const {
  std::lock_guard<std::mutex> lock(readers_mutex_);
  return !active_readers_.empty();
}

void NdkImageTextureBridge::CloseAllReaders() {
  std::vector<std::shared_ptr<NdkImageReader>> readers;
  {
    std::lock_guard<std::mutex> lock(readers_mutex_);
    readers.swap(active_readers_);
  }
  for (const auto& reader : readers) {
    if (reader) {
      reader->RequestClose();
    }
  }
}

NdkImageReader::AcquiredNativeImage
NdkImageTextureBridge::AcquireLatestFromReaders() {
  std::vector<std::shared_ptr<NdkImageReader>> snapshot;
  {
    std::lock_guard<std::mutex> lock(readers_mutex_);
    snapshot = active_readers_;
  }
  if (snapshot.empty()) {
    return {};
  }

  NdkImageReader::AcquiredNativeImage result;
  size_t acquired_idx = snapshot.size();
  for (size_t i = 0; i < snapshot.size(); ++i) {
    if (!snapshot[i] || snapshot[i]->IsClosed()) {
      continue;
    }
    auto candidate = snapshot[i]->AcquireLatestImage();
    if (candidate.hardware_buffer != nullptr) {
      result = std::move(candidate);
      acquired_idx = i;
      break;
    }
  }

  if (result.hardware_buffer == nullptr) {
    return {};
  }

  for (size_t i = 0; i < acquired_idx; ++i) {
    if (snapshot[i]) {
      snapshot[i]->RequestClose();
    }
  }

  for (size_t i = acquired_idx + 1; i < snapshot.size(); ++i) {
    if (snapshot[i] && !snapshot[i]->IsClosed() &&
        snapshot[i]->HasPendingImage()) {
      NotifyImageAvailable();
      break;
    }
  }

  return result;
}

void NdkImageTextureBridge::NotifyImageAvailable() {
  if (frame_schedule_pending_.exchange(true, std::memory_order_acq_rel)) {
    return;
  }
  if (!platform_task_runner_) {
    frame_schedule_pending_.store(false, std::memory_order_release);
    if (schedule_frame_override_) {
      schedule_frame_override_();
    }
    return;
  }
  std::shared_ptr<NdkImageTextureBridge> self = shared_from_this();
  platform_task_runner_->PostTask([self]() {
    self->frame_schedule_pending_.store(false, std::memory_order_release);
    if (self->schedule_frame_override_) {
      self->schedule_frame_override_();
      return;
    }
    if (self->platform_view_) {
      self->platform_view_->ScheduleFrame();
    }
  });
}

void NdkImageTextureBridge::EnqueueBufferRemoved(uint64_t reader_generation,
                                                 uint64_t buffer_id) {
  std::lock_guard<std::mutex> lock(eviction_mutex_);
  pending_buffers_.push_back(BufferEviction{
      .reader_generation = reader_generation,
      .buffer_id = buffer_id,
  });
  PostCoalescedRasterDrainLocked();
}

void NdkImageTextureBridge::EnqueueGenerationClosed(
    uint64_t reader_generation) {
  std::lock_guard<std::mutex> lock(eviction_mutex_);
  pending_closed_generations_.push_back(reader_generation);
  PostCoalescedRasterDrainLocked();
}

void NdkImageTextureBridge::TakePendingEvictions(
    std::vector<BufferEviction>* out_buffers,
    std::vector<uint64_t>* out_closed_generations) {
  std::lock_guard<std::mutex> lock(eviction_mutex_);
  if (out_buffers != nullptr) {
    out_buffers->swap(pending_buffers_);
  } else {
    pending_buffers_.clear();
  }
  if (out_closed_generations != nullptr) {
    out_closed_generations->swap(pending_closed_generations_);
  } else {
    pending_closed_generations_.clear();
  }
}

void NdkImageTextureBridge::PostCoalescedRasterDrainLocked() {
  if (raster_drain_scheduled_ || !raster_task_runner_) {
    return;
  }
  raster_drain_scheduled_ = true;
  std::shared_ptr<NdkImageTextureBridge> self = shared_from_this();
  raster_task_runner_->PostTask([self]() {
    {
      std::lock_guard<std::mutex> lock(self->eviction_mutex_);
      self->raster_drain_scheduled_ = false;
    }
    if (auto texture = self->weak_texture_raster_only_.lock()) {
      texture->DrainPendingEvictions();
    }
  });
}

}  // namespace flutter
