// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_GL_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_GL_H_

#include <functional>
#include <memory>

#include "flutter/impeller/toolkit/egl/image.h"
#include "flutter/impeller/toolkit/gles/texture.h"
#include "flutter/shell/platform/android/image_external_texture.h"

namespace flutter {

class ImageExternalTextureGL : public ImageExternalTexture {
 public:
  ImageExternalTextureGL(
      int64_t id,
      const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
      const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
      ImageExternalTexture::ImageLifecycle lifecycle,
      std::shared_ptr<NdkImageTextureBridge> ndk_bridge = nullptr,
      std::shared_ptr<IncrementalRetireQueue> retire_queue = nullptr);

 protected:
  struct GlImageImport {
    sk_sp<flutter::DlImage> dl_image;
    std::function<void()> rebind_callback;
  };

  // |ImageExternalTexture|
  void Attach(PaintContext& context) override;

  // |ImageExternalTexture|
  void Detach() override;

  // |ImageExternalTexture|
  void ProcessFrame(PaintContext& context, const SkRect& bounds) override;

  virtual GlImageImport CreateDlImage(
      PaintContext& context,
      const AHardwareBuffer_Desc& desc,
      impeller::UniqueEGLImageKHR&& egl_image) = 0;

  virtual impeller::UniqueEGLImageKHR CreateEGLImage(AHardwareBuffer* buffer);

  virtual bool WaitAcquireFence(fml::UniqueFD& fence_fd);

 private:
  FML_DISALLOW_COPY_AND_ASSIGN(ImageExternalTextureGL);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_GL_H_
