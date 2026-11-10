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

#include "flutter/fml/macros.h"
#include "flutter/fml/memory/ref_ptr.h"
#include "flutter/fml/memory/weak_ptr.h"
#include "flutter/fml/task_runner.h"
#include "flutter/fml/unique_fd.h"

namespace flutter {

class ImageExternalTexture;
class NdkImageTextureBridge;
class PlatformView;

bool WaitAcquireFenceFd(fml::UniqueFD& fence_fd, int timeout_ms = 3000);

class NdkImageReader : public std::enable_shared_from_this<NdkImageReader> {
 public:
  static constexpr int32_t kMaxDequeuedImages = 2;
  static constexpr int32_t kMaxImages = 5 + kMaxDequeuedImages;

  using UniqueAHardwareBuffer =
      std::unique_ptr<AHardwareBuffer, void (*)(AHardwareBuffer*)>;

  struct AcquiredNativeImage {
    uint64_t reader_generation = 0;
    AHardwareBuffer* hardware_buffer = nullptr;
    UniqueAHardwareBuffer owned_buffer_ref = {nullptr, nullptr};
    fml::UniqueFD acquire_fence_fd;
  };

  static bool IsAvailable(bool require_java_surface = true);

  static std::shared_ptr<NdkImageReader> Create(
      JNIEnv* env,
      int32_t width,
      int32_t height,
      std::shared_ptr<NdkImageTextureBridge> bridge,
      jobject* out_java_surface = nullptr);

  ~NdkImageReader();

  uint64_t generation() const { return generation_; }
  ANativeWindow* window() const { return window_; }
  bool IsClosed() const {
    return close_requested_.load(std::memory_order_acquire);
  }
  bool HasPendingImage() const {
    return has_pending_image_.load(std::memory_order_acquire);
  }
  size_t OpenImageCountForTest() const;

  AcquiredNativeImage AcquireLatestImage();
  void RequestClose();
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

  mutable std::mutex reader_mutex_;
  AImageReader* reader_ = nullptr;
  ANativeWindow* window_ = nullptr;
  std::deque<AImage*> open_images_;

  std::atomic<bool> close_requested_{false};
  std::atomic<bool> closed_{false};
  std::atomic<bool> has_pending_image_{false};

  AImageReader_ImageListener image_listener_{};
  AImageReader_BufferRemovedListener buffer_removed_listener_{};

  FML_DISALLOW_COPY_AND_ASSIGN(NdkImageReader);
};

class NdkImageTextureBridge
    : public std::enable_shared_from_this<NdkImageTextureBridge> {
 public:
  struct BufferEviction {
    uint64_t reader_generation = 0;
    uint64_t buffer_id = 0;
  };

  NdkImageTextureBridge(
      int64_t texture_id,
      fml::WeakPtr<PlatformView> platform_view,
      fml::RefPtr<fml::TaskRunner> platform_task_runner,
      fml::RefPtr<fml::TaskRunner> raster_task_runner,
      std::function<void()> schedule_frame_override = nullptr);

  ~NdkImageTextureBridge() = default;

  const fml::RefPtr<fml::TaskRunner>& platform_task_runner() const {
    return platform_task_runner_;
  }

  void BindTexture(const std::shared_ptr<ImageExternalTexture>& texture);
  void AddReader(const std::shared_ptr<NdkImageReader>& reader);
  void RemoveReader(uint64_t generation);
  bool HasReaders() const;
  void CloseAllReaders();

  NdkImageReader::AcquiredNativeImage AcquireLatestFromReaders();
  void NotifyImageAvailable();
  void EnqueueBufferRemoved(uint64_t reader_generation, uint64_t buffer_id);
  void EnqueueGenerationClosed(uint64_t reader_generation);
  void TakePendingEvictions(std::vector<BufferEviction>* out_buffers,
                            std::vector<uint64_t>* out_closed_generations);

 private:
  void PostCoalescedRasterDrainLocked();

  const int64_t texture_id_;
  fml::WeakPtr<PlatformView> platform_view_;
  fml::RefPtr<fml::TaskRunner> platform_task_runner_;
  fml::RefPtr<fml::TaskRunner> raster_task_runner_;
  std::function<void()> schedule_frame_override_;

  std::weak_ptr<ImageExternalTexture> weak_texture_raster_only_;

  mutable std::mutex readers_mutex_;
  std::vector<std::shared_ptr<NdkImageReader>> active_readers_;

  std::atomic<bool> frame_schedule_pending_{false};

  std::mutex eviction_mutex_;
  std::vector<BufferEviction> pending_buffers_;
  std::vector<uint64_t> pending_closed_generations_;
  bool raster_drain_scheduled_ = false;

  FML_DISALLOW_COPY_AND_ASSIGN(NdkImageTextureBridge);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_NDK_IMAGE_READER_H_
