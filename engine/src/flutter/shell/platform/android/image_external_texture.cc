// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture.h"

#include <android/hardware_buffer_jni.h>
#include <android/sensor.h>

#include "flutter/fml/platform/android/jni_util.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"

namespace flutter {

ImageExternalTexture::ImageExternalTexture(
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageLifecycle lifecycle)
    : Texture(id),
      image_texture_entry_(image_texture_entry),
      jni_facade_(jni_facade),
      texture_lifecycle_(lifecycle) {}

ImageExternalTexture::~ImageExternalTexture() = default;

void ImageExternalTexture::SetNdkSurfaceProducer(
    std::shared_ptr<NdkSurfaceProducer> ndk_producer) {
  ndk_producer_ = std::move(ndk_producer);
}

// Implementing flutter::Texture.
void ImageExternalTexture::Paint(PaintContext& context,
                                 const DlRect& bounds,
                                 bool freeze,
                                 const DlImageSampling sampling) {
  if (state_ == AttachmentState::kDetached) {
    return;
  }
  Attach(context);
  const bool should_process_frame = !freeze;
  if (should_process_frame) {
    ProcessFrame(context, ToSkRect(bounds));
  }
  if (dl_image_) {
    context.canvas->DrawImageRect(
        dl_image_,                             // image
        DlRect::Make(dl_image_->GetBounds()),  // source rect
        bounds,                                // destination rect
        sampling,                              // sampling
        context.paint,                         // paint
        flutter::DlSrcRectConstraint::kStrict  // enforce edges
    );
  } else {
    FML_LOG(INFO) << "No DlImage available for ImageExternalTexture to paint.";
  }
}

// Implementing flutter::Texture.
void ImageExternalTexture::MarkNewFrameAvailable() {
  // NOOP.
}

// Implementing flutter::Texture.
void ImageExternalTexture::OnTextureUnregistered() {}

// Implementing flutter::ContextListener.
void ImageExternalTexture::OnGrContextCreated() {
  state_ = AttachmentState::kUninitialized;
}

// Implementing flutter::ContextListener.
void ImageExternalTexture::OnGrContextDestroyed() {
  if (state_ == AttachmentState::kAttached) {
    switch (texture_lifecycle_) {
      case ImageLifecycle::kReset: {
        dl_image_.reset();
        image_lru_.Clear();
      } break;
      case ImageLifecycle::kKeepAlive:
        // Intentionally do nothing.
        ///
        // If we reset the image, we are not able to re-acquire it, but the
        // producer of the image will not know to reproduce it, resulting in a
        // blank image. See https://github.com/flutter/flutter/issues/163561.
        break;
    }
    Detach();
  }
  state_ = AttachmentState::kDetached;
}

JavaLocalRef ImageExternalTexture::AcquireLatestImage() {
  JNIEnv* env = fml::jni::AttachCurrentThread();
  FML_CHECK(env != nullptr);

  // ImageTextureEntry.acquireLatestImage.
  JavaLocalRef image_java =
      jni_facade_->ImageProducerTextureEntryAcquireLatestImage(
          JavaLocalRef(image_texture_entry_));
  return image_java;
}

PlatformViewAndroidJNI::AcquiredHardwareBuffer
ImageExternalTexture::AcquireLatestHardwareBuffer() {
  return jni_facade_->ImageProducerTextureEntryAcquireLatestHardwareBuffer(
      JavaLocalRef(image_texture_entry_), &cached_consumer_,
      &cached_is_surface_producer_);
}

ImageExternalTexture::AcquiredFrame ImageExternalTexture::AcquireLatestFrame() {
  if (ndk_producer_) {
    auto ndk_frame = ndk_producer_->AcquireLatestFrame();
    if (ndk_frame.ahardware_buffer == nullptr) {
      return {};
    }
    return AcquiredFrame{
        .java_hardware_buffer = JavaLocalRef(),
        .ahardware_buffer = ndk_frame.ahardware_buffer,
        .acquire_fence_fd = std::move(ndk_frame.acquire_fence_fd),
        .owned_by_producer = true,
        .caller_owns_ahb_ref = true,
    };
  }
  auto acquired = AcquireLatestHardwareBuffer();
  if (acquired.hardware_buffer.is_null()) {
    return {};
  }
  AHardwareBuffer* ahb = AHardwareBufferFor(acquired.hardware_buffer);
  if (ahb == nullptr) {
    if (!acquired.owned_by_producer) {
      CloseHardwareBuffer(acquired.hardware_buffer);
    }
    return {};
  }
  return AcquiredFrame{
      .java_hardware_buffer = std::move(acquired.hardware_buffer),
      .ahardware_buffer = ahb,
      .acquire_fence_fd = fml::UniqueFD(),
      .owned_by_producer = acquired.owned_by_producer,
      .caller_owns_ahb_ref = false,
  };
}

void ImageExternalTexture::CloseImage(const fml::jni::JavaRef<jobject>& image) {
  if (image.obj() == nullptr) {
    return;
  }
  jni_facade_->ImageClose(JavaLocalRef(image));
}

void ImageExternalTexture::CloseHardwareBuffer(
    const fml::jni::JavaRef<jobject>& hardware_buffer) {
  if (hardware_buffer.obj() == nullptr) {
    return;
  }
  jni_facade_->HardwareBufferClose(JavaLocalRef(hardware_buffer));
}

JavaLocalRef ImageExternalTexture::HardwareBufferFor(
    const fml::jni::JavaRef<jobject>& image) {
  if (image.obj() == nullptr) {
    return JavaLocalRef();
  }
  // Image.getHardwareBuffer.
  return jni_facade_->ImageGetHardwareBuffer(JavaLocalRef(image));
}

AHardwareBuffer* ImageExternalTexture::AHardwareBufferFor(
    const fml::jni::JavaRef<jobject>& hardware_buffer) {
  if (hardware_buffer.is_null()) {
    return nullptr;
  }
  JNIEnv* env = fml::jni::AttachCurrentThread();
  FML_CHECK(env != nullptr);
  const auto& proc =
      impeller::android::GetProcTable().AHardwareBuffer_fromHardwareBuffer;
  return proc ? proc(env, hardware_buffer.obj()) : nullptr;
}

}  // namespace flutter
