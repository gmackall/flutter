// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_gl.h"

#include <android/hardware_buffer_jni.h>

#include <utility>

#include "flutter/common/graphics/texture.h"
#include "flutter/fml/closure.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/impeller/toolkit/egl/image.h"

namespace flutter {

ImageExternalTextureGL::ImageExternalTextureGL(
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageExternalTexture::ImageLifecycle lifecycle,
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge)
    : ImageExternalTexture(id,
                           image_texture_entry,
                           jni_facade,
                           lifecycle,
                           std::move(ndk_bridge)) {}

void ImageExternalTextureGL::Attach(PaintContext& /*context*/) {
  if (state_ == AttachmentState::kUninitialized) {
    state_ = AttachmentState::kAttached;
  }
}

void ImageExternalTextureGL::Detach() {}

void ImageExternalTextureGL::ProcessFrame(PaintContext& context,
                                          const SkRect& bounds) {
  DrainPendingEvictions();

  AcquiredFrame frame = AcquireLatestFrame();
  if (frame.ahardware_buffer == nullptr) {
    return;
  }
  fml::ScopedCleanupClosure close_java_hb([this, &frame]() {
    if (!frame.java_hardware_buffer.is_null()) {
      CloseHardwareBuffer(frame.java_hardware_buffer);
    }
  });

  if (!WaitAcquireFenceFd(frame.acquire_fence_fd)) {
    return;
  }

  std::optional<HardwareBufferKey> key =
      impeller::android::HardwareBuffer::GetSystemUniqueID(
          frame.ahardware_buffer);
  auto existing_image = FindCachedImage(frame.reader_generation, key);
  if (existing_image != nullptr) {
    dl_image_ = std::move(existing_image);
    return;
  }

  auto egl_image = CreateEGLImage(frame.ahardware_buffer);
  if (!egl_image.is_valid()) {
    return;
  }

  sk_sp<flutter::DlImage> created =
      CreateDlImage(context, bounds, std::move(egl_image));
  if (!created) {
    return;
  }
  dl_image_ = created;
  CacheImage(frame.reader_generation, key, std::move(created));
}

impeller::UniqueEGLImageKHR ImageExternalTextureGL::CreateEGLImage(
    AHardwareBuffer* hardware_buffer) {
  if (hardware_buffer == nullptr) {
    return impeller::UniqueEGLImageKHR();
  }

  EGLDisplay display = eglGetCurrentDisplay();
  if (display == EGL_NO_DISPLAY) {
    return impeller::UniqueEGLImageKHR();
  }

  const auto& proc_table = impeller::android::GetProcTable();
  if (!proc_table.eglGetNativeClientBufferANDROID.IsAvailable()) {
    return impeller::UniqueEGLImageKHR();
  }

  EGLClientBuffer client_buffer =
      proc_table.eglGetNativeClientBufferANDROID(hardware_buffer);
  FML_DCHECK(client_buffer != nullptr);
  if (client_buffer == nullptr) {
    FML_LOG(ERROR) << "eglGetNativeClientBufferANDROID returned null.";
    return impeller::UniqueEGLImageKHR();
  }

  EGLImageKHR image = eglCreateImageKHR(
      display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client_buffer, 0);
  if (image == EGL_NO_IMAGE_KHR) {
    return impeller::UniqueEGLImageKHR();
  }

  return impeller::UniqueEGLImageKHR(
      impeller::EGLImageKHRWithDisplay{image, display});
}

}  // namespace flutter
