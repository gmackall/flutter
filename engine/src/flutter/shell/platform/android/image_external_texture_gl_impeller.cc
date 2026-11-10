// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_gl_impeller.h"

#include <algorithm>
#include <utility>

#include "flutter/impeller/display_list/dl_image_impeller.h"
#include "flutter/impeller/renderer/backend/gles/texture_gles.h"

namespace flutter {

namespace {

class DlImageImpellerExternalGLES final : public impeller::DlImageImpeller {
 public:
  DlImageImpellerExternalGLES(std::shared_ptr<impeller::TextureGLES> texture,
                              impeller::UniqueEGLImageKHR egl_image)
      : texture_(std::move(texture)), egl_image_(std::move(egl_image)) {}

  ~DlImageImpellerExternalGLES() override = default;

  std::shared_ptr<impeller::Texture> GetImpellerTexture(
      impeller::ContentContext& /*renderer*/) const override {
    return texture_;
  }

  flutter::DlColorSpace GetColorSpace() const override {
    return flutter::DlColorSpace::kSRGB;
  }

  bool isOpaque() const override { return false; }
  bool isUIThreadSafe() const override { return true; }

  flutter::DlISize GetSize() const override {
    return texture_ ? flutter::DlISize(texture_->GetTextureDescriptor().size)
                    : flutter::DlISize();
  }

  size_t GetApproximateByteSize() const override {
    size_t size = sizeof(*this);
    if (texture_) {
      size += texture_->GetTextureDescriptor().GetByteSizeOfBaseMipLevel();
    }
    return size;
  }

  OwningContext owning_context() const override { return OwningContext::kIO; }

 private:
  std::shared_ptr<impeller::TextureGLES> texture_;
  impeller::UniqueEGLImageKHR egl_image_;

  FML_DISALLOW_COPY_AND_ASSIGN(DlImageImpellerExternalGLES);
};

}  // namespace

ImageExternalTextureGLImpeller::ImageExternalTextureGLImpeller(
    const std::shared_ptr<impeller::ContextGLES>& context,
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageExternalTexture::ImageLifecycle lifecycle,
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge)
    : ImageExternalTextureGL(id,
                             image_texture_entry,
                             jni_facade,
                             lifecycle,
                             std::move(ndk_bridge)),
      impeller_context_(context) {}

void ImageExternalTextureGLImpeller::Detach() {}

void ImageExternalTextureGLImpeller::Attach(PaintContext& context) {
  if (state_ == AttachmentState::kUninitialized) {
    ImageExternalTextureGL::Attach(context);
  }
}

sk_sp<flutter::DlImage> ImageExternalTextureGLImpeller::CreateDlImage(
    PaintContext& /*context*/,
    const SkRect& bounds,
    impeller::UniqueEGLImageKHR&& egl_image) {
  if (!impeller_context_ || !egl_image.is_valid()) {
    return nullptr;
  }
  impeller::TextureDescriptor desc;
  desc.type = impeller::TextureType::kTextureExternalOES;
  desc.storage_mode = impeller::StorageMode::kDevicePrivate;
  desc.format = impeller::PixelFormat::kR8G8B8A8UNormInt;
  desc.size = {std::max(1, static_cast<int>(bounds.width())),
               std::max(1, static_cast<int>(bounds.height()))};
  desc.mip_count = 1;
  auto texture = std::make_shared<impeller::TextureGLES>(
      impeller_context_->GetReactor(), desc);
  texture->MarkContentsInitialized();
  if (!texture->Bind()) {
    return nullptr;
  }
  glEGLImageTargetTexture2DOES(
      GL_TEXTURE_EXTERNAL_OES,
      static_cast<GLeglImageOES>(egl_image.get().image));
  return sk_make_sp<DlImageImpellerExternalGLES>(std::move(texture),
                                                 std::move(egl_image));
}

}  // namespace flutter
