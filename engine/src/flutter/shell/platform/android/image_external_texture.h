// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_H_

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <unordered_map>
#include <unordered_set>

#include "flutter/common/graphics/texture.h"
#include "flutter/fml/logging.h"
#include "flutter/fml/unique_fd.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"
#include "flutter/shell/platform/android/ndk_image_reader.h"
#include "flutter/shell/platform/android/platform_view_android_jni_impl.h"

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      External texture peered to a sequence of
///             android.hardware.HardwareBuffers.
///
///             Android Hardware Buffers are available on newer versions of
///             Android (API 29 and above).
///
///             This is an abstract base class and graphics packages provide
///             concrete implementations of this class that bind hardware
///             buffers to their own package-specific implementations of
///             textures (SkImages, impeller::Texture, etc...).
///
///             Android Hardware Buffers allow binding to both OpenGL and Vulkan
///             client-rendering APIs in a zero copy manner. Because of this
///             graphics packages that support OpenGL and Vulkan can have
///             multiple subclasses for each supported client-rendering API.
///
class ImageExternalTexture : public flutter::Texture {
 public:
  /// Whether the last image should be reset when the context is destroyed.
  enum class ImageLifecycle { kReset, kKeepAlive };

  struct ImageCacheKey {
    uint64_t reader_generation = 0;
    uint64_t buffer_key = 0;

    constexpr bool operator==(const ImageCacheKey& other) const = default;
  };

  struct ImageCacheKeyHash {
    size_t operator()(const ImageCacheKey& key) const noexcept {
      const size_t h1 = std::hash<uint64_t>{}(key.reader_generation);
      const size_t h2 = std::hash<uint64_t>{}(key.buffer_key);
      return h1 ^ (h2 + 0x9e3779b97f4a7c15ULL + (h1 << 6) + (h1 >> 2));
    }
  };

  explicit ImageExternalTexture(
      int64_t id,
      const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
      const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
      ImageLifecycle lifecycle,
      std::shared_ptr<NdkImageTextureBridge> ndk_bridge = nullptr,
      std::shared_ptr<IncrementalRetireQueue> retire_queue = nullptr);

  // |flutter::Texture|
  ~ImageExternalTexture() override;

  void SetNdkBridge(std::shared_ptr<NdkImageTextureBridge> ndk_bridge);

  void SetRetireQueue(std::shared_ptr<IncrementalRetireQueue> retire_queue);

  /// Drains pending onBufferRemoved, reader-generation close, and memory-trim
  /// notifications onto the raster thread.
  void DrainPendingEvictions();

  /// Purges all cached GPU imports except the currently displayed DlImage.
  void PurgeCacheExceptCurrent(bool synchronous);

  // |flutter::Texture|
  void OnLowMemoryWarning() override;

  size_t CachedEntryCountForTest() const { return image_cache_.size(); }

  bool HasCachedKeyForTest(const ImageCacheKey& key) const {
    return image_cache_.find(key) != image_cache_.end();
  }

  sk_sp<flutter::DlImage> CurrentDlImageForTest() const { return dl_image_; }

 protected:
  static constexpr size_t kLegacyJavaCacheMaxEntries = 6u;

  struct AcquiredFrame {
    uint64_t reader_generation = 0;
    AHardwareBuffer* ahardware_buffer = nullptr;
    std::shared_ptr<ScopedAHardwareBufferRef> caller_ahb_ref;
    fml::UniqueFD acquire_fence_fd;
    JavaLocalRef java_hardware_buffer;
  };

  struct CacheEntry {
    sk_sp<flutter::DlImage> dl_image;
    std::shared_ptr<ScopedAHardwareBufferRef> ahb_ref;
    AHardwareBuffer_Desc desc = {};
    std::function<bool(fml::UniqueFD&)> on_new_frame_hit;
    uint64_t last_used_seq = 0;
  };

  virtual void ProcessFrame(PaintContext& context, const SkRect& bounds) = 0;

  virtual void Attach(PaintContext& context) = 0;

  virtual void Detach() = 0;

  AcquiredFrame AcquireLatestFrame();

  JavaLocalRef AcquireLatestImage();

  void CloseImage(const fml::jni::JavaRef<jobject>& image);

  JavaLocalRef HardwareBufferFor(const fml::jni::JavaRef<jobject>& image);

  void CloseHardwareBuffer(const fml::jni::JavaRef<jobject>& hardware_buffer);

  AHardwareBuffer* AHardwareBufferFor(
      const fml::jni::JavaRef<jobject>& hardware_buffer);

  static bool IsProtectedContent(const AHardwareBuffer_Desc& desc);

  static bool DescriptorsMatch(const AHardwareBuffer_Desc& a,
                               const AHardwareBuffer_Desc& b);

  ImageCacheKey MakeCacheKey(uint64_t reader_generation,
                             AHardwareBuffer* buffer,
                             bool* out_needs_ahb_retain) const;

  CacheEntry* FindCachedEntry(const ImageCacheKey& key,
                              const AHardwareBuffer_Desc& desc);

  bool IsFailedImportKey(const ImageCacheKey& key) const;

  void RecordFailedImport(const ImageCacheKey& key);

  void InsertCacheEntry(
      const ImageCacheKey& key,
      const AHardwareBuffer_Desc& desc,
      sk_sp<flutter::DlImage> dl_image,
      std::shared_ptr<ScopedAHardwareBufferRef> ahb_ref,
      std::function<bool(fml::UniqueFD&)> on_new_frame_hit = nullptr);

  void SetCurrentDlImage(sk_sp<flutter::DlImage> dl_image,
                         std::shared_ptr<ScopedAHardwareBufferRef> ahb_ref);

  void ClearCacheSynchronously();

  fml::jni::ScopedJavaGlobalRef<jobject> image_texture_entry_;
  std::shared_ptr<PlatformViewAndroidJNI> jni_facade_;
  std::shared_ptr<NdkImageTextureBridge> ndk_bridge_;
  std::shared_ptr<IncrementalRetireQueue> retire_queue_;

  enum class AttachmentState { kUninitialized, kAttached, kDetached };
  AttachmentState state_ = AttachmentState::kUninitialized;
  sk_sp<flutter::DlImage> dl_image_;
  std::shared_ptr<ScopedAHardwareBufferRef> current_dl_image_ahb_ref_;

 private:
  // |flutter::Texture|
  void Paint(PaintContext& context,
             const DlRect& bounds,
             bool freeze,
             const DlImageSampling sampling) override;

  // |flutter::Texture|
  void MarkNewFrameAvailable() override;

  // |flutter::Texture|
  void OnTextureUnregistered() override;

  // |flutter::ContextListener|
  void OnGrContextCreated() override;

  // |flutter::ContextListener|
  void OnGrContextDestroyed() override;

  void EnforceLegacyGenerationZeroCap();

  const ImageLifecycle texture_lifecycle_;
  std::unordered_map<ImageCacheKey, CacheEntry, ImageCacheKeyHash> image_cache_;
  std::unordered_set<ImageCacheKey, ImageCacheKeyHash> failed_import_keys_;
  uint64_t use_sequence_ = 0;

  FML_DISALLOW_COPY_AND_ASSIGN(ImageExternalTexture);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_H_
