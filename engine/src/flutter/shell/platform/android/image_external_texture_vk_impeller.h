// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_VK_IMPELLER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_VK_IMPELLER_H_

#include <cstdint>
#include <utility>
#include "flutter/shell/platform/android/image_external_texture.h"

#include "flutter/impeller/renderer/backend/vulkan/android/ahb_texture_source_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/context_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/vk.h"
#include "flutter/shell/platform/android/android_context_vk_impeller.h"

namespace flutter {

class ImageExternalTextureVKImpeller : public ImageExternalTexture {
 public:
  ImageExternalTextureVKImpeller(
      const std::shared_ptr<impeller::ContextVK>& impeller_context,
      int64_t id,
      const fml::jni::ScopedJavaGlobalRef<jobject>&
          hardware_buffer_texture_entry,
      const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
      ImageExternalTexture::ImageLifecycle lifecycle);

  ~ImageExternalTextureVKImpeller() override;

  //----------------------------------------------------------------------------
  /// @brief      Imports the latest image produced by the image consumer, if
  ///             there is a new one, and returns the most recent image.
  ///
  ///             This is for consumers that draw the image themselves rather
  ///             than through |Paint| (for example to capture HCPP platform
  ///             views under a backdrop filter).
  ///
  /// @note       Must be called from the raster thread.
  ///
  sk_sp<flutter::DlImage> AcquireLatestDlImage();

 private:
  void Attach(PaintContext& context) override;
  void ProcessFrame(PaintContext& context, const SkRect& bounds) override;
  void Detach() override;

  // Imports the latest image from the image consumer into dl_image_.
  void UpdateLatestImage();

  const std::shared_ptr<impeller::ContextVK> impeller_context_;
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_VK_IMPELLER_H_
