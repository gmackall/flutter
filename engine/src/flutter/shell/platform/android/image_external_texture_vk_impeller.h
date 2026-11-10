// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_VK_IMPELLER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_VK_IMPELLER_H_

#include <cstdint>
#include <memory>
#include <utility>

#include "flutter/impeller/renderer/backend/vulkan/android/ahb_texture_source_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/context_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/vk.h"
#include "flutter/shell/platform/android/android_context_vk_impeller.h"
#include "flutter/shell/platform/android/image_external_texture.h"

namespace flutter {

class ImageExternalTextureVKImpeller : public ImageExternalTexture {
 public:
  ImageExternalTextureVKImpeller(
      const std::shared_ptr<impeller::ContextVK>& impeller_context,
      int64_t id,
      const fml::jni::ScopedJavaGlobalRef<jobject>&
          hardware_buffer_texture_entry,
      const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
      ImageExternalTexture::ImageLifecycle lifecycle,
      std::shared_ptr<NdkImageTextureBridge> ndk_bridge = nullptr);

  ~ImageExternalTextureVKImpeller() override;

 protected:
  void Attach(PaintContext& context) override;
  void ProcessFrame(PaintContext& context, const SkRect& bounds) override;
  void Detach() override;

  virtual sk_sp<flutter::DlImage> ImportHardwareBuffer(
      AHardwareBuffer* hardware_buffer,
      const AHardwareBuffer_Desc& desc);

 private:
  const std::shared_ptr<impeller::ContextVK> impeller_context_;

  FML_DISALLOW_COPY_AND_ASSIGN(ImageExternalTextureVKImpeller);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_VK_IMPELLER_H_
