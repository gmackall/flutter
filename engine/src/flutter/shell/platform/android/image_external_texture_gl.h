// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_GL_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_GL_H_

#include <memory>
#include <optional>
#include <unordered_map>

#include "flutter/impeller/toolkit/egl/image.h"
#include "flutter/impeller/toolkit/gles/texture.h"
#include "flutter/shell/platform/android/image_external_texture.h"

namespace flutter {

class ImageExternalTextureGL : public ImageExternalTexture {
 public:
  ImageExternalTextureGL(
      int64_t id,
      const fml::jni::ScopedJavaGlobalRef<jobject>& image_textury_entry,
      const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
      ImageExternalTexture::ImageLifecycle lifecycle);

 protected:
  // |ImageExternalTexture|
  void Attach(PaintContext& context) override;

  // |ImageExternalTexture|
  void Detach() override;

  // |ImageExternalTexture|
  void ProcessFrame(PaintContext& context, const SkRect& bounds) override;

  virtual sk_sp<flutter::DlImage> CreateDlImage(
      PaintContext& context,
      const SkRect& bounds,
      std::optional<HardwareBufferKey> id,
      impeller::UniqueEGLImageKHR&& egl_image) = 0;

  bool UpdateImage(AHardwareBuffer* latest_hardware_buffer,
                   const SkRect& bounds,
                   PaintContext& context);

  impeller::UniqueEGLImageKHR CreateEGLImage(AHardwareBuffer* buffer);

  virtual EGLImageKHR CreateEGLImageKHR(EGLDisplay display,
                                        EGLClientBuffer buffer);

  struct EglSyncProcs {
    PFNEGLCREATESYNCKHRPROC create_sync = nullptr;
    PFNEGLWAITSYNCKHRPROC wait_sync = nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroy_sync = nullptr;
    bool force_native_fence_supported = false;
  };

  void SetEglSyncProcsForTest(std::optional<EglSyncProcs> procs) {
    egl_sync_procs_for_test_ = std::move(procs);
  }

  NdkSurfaceProducer::FenceWaitResult WaitAcquireFenceOnGL(
      fml::UniqueFD& acquire_fence_fd,
      int timeout_ms = NdkSurfaceProducer::kDefaultFenceWaitTimeoutMs);

  NdkSurfaceProducer::FenceWaitResult WaitAcquireFenceOnGL(
      fml::UniqueFD&& acquire_fence_fd,
      int timeout_ms = NdkSurfaceProducer::kDefaultFenceWaitTimeoutMs) {
    return WaitAcquireFenceOnGL(acquire_fence_fd, timeout_ms);
  }

  struct GlEntry {
    impeller::UniqueEGLImageKHR egl_image;
    impeller::UniqueGLTexture texture;
  };

  // Impeller's EGL images are keyed by hardware buffer and evicted with the
  // LRU cache entries. Skia's images own their GlEntry via a release callback.
  std::unordered_map<HardwareBufferKey, GlEntry> gl_entries_;
  std::optional<EglSyncProcs> egl_sync_procs_for_test_;

  FML_DISALLOW_COPY_AND_ASSIGN(ImageExternalTextureGL);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_IMAGE_EXTERNAL_TEXTURE_GL_H_
