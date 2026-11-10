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

#include "flutter/common/graphics/texture.h"
#include "flutter/fml/logging.h"
#include "flutter/fml/unique_fd.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"
#include "flutter/shell/platform/android/ndk_image_reader.h"
#include "flutter/shell/platform/android/platform_view_android_jni_impl.h"

namespace flutter {

using HardwareBufferKey = uint64_t;

class ImageExternalTexture : public flutter::Texture {
 public:
  enum class ImageLifecycle { kReset, kKeepAlive };

  struct ImageCacheKey {
    uint64_t reader_generation = 0;
    HardwareBufferKey hardware_buffer_id = 0;

    constexpr bool operator==(const ImageCacheKey& other) const = default;
  };

  struct ImageCacheKeyHash {
    size_t operator()(const ImageCacheKey& key) const noexcept {
      const size_t h1 = std::hash<uint64_t>{}(key.reader_generation);
      const size_t h2 = std::hash<uint64_t>{}(key.hardware_buffer_id);
      return h1 ^ (h2 + 0x9e3779b97f4a7c15ULL + (h1 << 6) + (h1 >> 2));
    }
  };

  explicit ImageExternalTexture(
      int64_t id,
      const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
      const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
      ImageLifecycle lifecycle,
      std::shared_ptr<NdkImageTextureBridge> ndk_bridge = nullptr);

  ~ImageExternalTexture() override;

  void DrainPendingEvictions();

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
    NdkImageReader::UniqueAHardwareBuffer owned_buffer_ref = {nullptr, nullptr};
    fml::UniqueFD acquire_fence_fd;
    JavaLocalRef java_hardware_buffer;
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

  sk_sp<flutter::DlImage> FindCachedImage(uint64_t reader_generation,
                                          std::optional<HardwareBufferKey> key);
  void CacheImage(uint64_t reader_generation,
                  std::optional<HardwareBufferKey> key,
                  sk_sp<flutter::DlImage> image);

  fml::jni::ScopedJavaGlobalRef<jobject> image_texture_entry_;
  std::shared_ptr<PlatformViewAndroidJNI> jni_facade_;
  std::shared_ptr<NdkImageTextureBridge> ndk_bridge_;

  enum class AttachmentState { kUninitialized, kAttached, kDetached };
  AttachmentState state_ = AttachmentState::kUninitialized;
  sk_sp<flutter::DlImage> dl_image_;

 private:
  struct CacheEntry {
    sk_sp<flutter::DlImage> dl_image;
    uint64_t last_used_seq = 0;
  };

  void Paint(PaintContext& context,
             const DlRect& bounds,
             bool freeze,
             const DlImageSampling sampling) override;
  void MarkNewFrameAvailable() override;
  void OnTextureUnregistered() override;
  void OnGrContextCreated() override;
  void OnGrContextDestroyed() override;
  void EnforceLegacyGenerationZeroCap();

  const ImageLifecycle texture_lifecycle_;
  std::unordered_map<ImageCacheKey, CacheEntry, ImageCacheKeyHash> image_cache_;
  uint64_t use_sequence_ = 0;

  FML_DISALLOW_COPY_AND_ASSIGN(ImageExternalTexture);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_H_
