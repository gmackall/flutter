// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_gl_skia.h"

#include <utility>

#include "flutter/display_list/image/dl_image_skia.h"
#include "flutter/third_party/skia/include/gpu/ganesh/SkImageGanesh.h"
#include "flutter/third_party/skia/include/gpu/ganesh/gl/GrGLBackendSurface.h"

namespace flutter {

namespace {

struct SkiaGlExternalResource {
  impeller::UniqueEGLImageKHR egl_image;
  impeller::UniqueGLTexture texture;
};

void ReleaseSkiaGlExternalResource(SkImages::ReleaseContext release_context) {
  auto* resource = static_cast<SkiaGlExternalResource*>(release_context);
  if (resource == nullptr) {
    return;
  }
  if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
    (void)resource->texture.release();
  }
  delete resource;
}

}  // namespace

ImageExternalTextureGLSkia::ImageExternalTextureGLSkia(
    const std::shared_ptr<AndroidContextGLSkia>& /*context*/,
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageExternalTexture::ImageLifecycle lifecycle,
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge)
    : ImageExternalTextureGL(id,
                             image_texture_entry,
                             jni_facade,
                             lifecycle,
                             std::move(ndk_bridge)) {}

void ImageExternalTextureGLSkia::Attach(PaintContext& context) {
  if (state_ == AttachmentState::kUninitialized) {
    ImageExternalTextureGL::Attach(context);
  }
}

void ImageExternalTextureGLSkia::Detach() {
  ImageExternalTextureGL::Detach();
}

void ImageExternalTextureGLSkia::BindImageToTexture(
    const impeller::UniqueEGLImageKHR& image,
    GLuint tex) {
  if (!image.is_valid() || tex == 0) {
    return;
  }
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
  glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES,
                               static_cast<GLeglImageOES>(image.get().image));
}

sk_sp<flutter::DlImage> ImageExternalTextureGLSkia::CreateDlImage(
    PaintContext& context,
    const SkRect& /*bounds*/,
    impeller::UniqueEGLImageKHR&& egl_image) {
  if (!egl_image.is_valid()) {
    return nullptr;
  }
  GLuint texture_name = 0;
  glGenTextures(1, &texture_name);
  if (texture_name == 0) {
    return nullptr;
  }
  impeller::UniqueGLTexture unique_texture;
  unique_texture.reset(impeller::GLTexture{texture_name});

  BindImageToTexture(egl_image, unique_texture.get().texture_name);
  GrGLTextureInfo texture_info = {
      GL_TEXTURE_EXTERNAL_OES, unique_texture.get().texture_name, GL_RGBA8_OES};
  auto backend_texture =
      GrBackendTextures::MakeGL(1, 1, skgpu::Mipmapped::kNo, texture_info);

  auto* resource = new SkiaGlExternalResource{
      .egl_image = std::move(egl_image),
      .texture = std::move(unique_texture),
  };
  sk_sp<SkImage> sk_image = SkImages::BorrowTextureFrom(
      context.gr_context, backend_texture, kTopLeft_GrSurfaceOrigin,
      kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr,
      &ReleaseSkiaGlExternalResource, resource);
  return sk_image ? DlImageSkia::Make(std::move(sk_image)) : nullptr;
}

}  // namespace flutter
