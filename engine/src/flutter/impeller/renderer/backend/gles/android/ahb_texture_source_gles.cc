// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "impeller/renderer/backend/gles/android/ahb_texture_source_gles.h"

#include "flutter/fml/make_copyable.h"
#include "impeller/base/validation.h"
#include "impeller/renderer/backend/gles/context_gles.h"
#include "impeller/renderer/backend/gles/gles.h"
#include "impeller/toolkit/android/proc_table.h"

namespace impeller {

namespace {

PixelFormat ToPixelFormat(android::HardwareBufferFormat format) {
  switch (format) {
    case android::HardwareBufferFormat::kR8G8B8A8UNormInt:
      return PixelFormat::kR8G8B8A8UNormInt;
  }
  return PixelFormat::kR8G8B8A8UNormInt;
}

TextureDescriptor ToTextureDescriptor(
    const android::HardwareBufferDescriptor& ahb_desc) {
  TextureDescriptor desc;
  // The hardware buffer is never touched on the CPU and is used as a render
  // target. Treat it as device private.
  desc.storage_mode = StorageMode::kDevicePrivate;
  desc.type = TextureType::kTexture2D;
  desc.format = ToPixelFormat(ahb_desc.format);
  desc.size = ahb_desc.size;
  desc.mip_count = 1u;
  desc.usage = TextureUsage::kRenderTarget;
  desc.sample_count = SampleCount::kCount1;
  desc.compression_type = CompressionType::kLossless;
  return desc;
}

UniqueEGLImageKHR CreateEGLImage(EGLDisplay display,
                                 AHardwareBuffer* hardware_buffer) {
  if (display == EGL_NO_DISPLAY || hardware_buffer == nullptr) {
    return {};
  }

  const auto& proc_table = android::GetProcTable();
  if (!proc_table.eglGetNativeClientBufferANDROID.IsAvailable()) {
    VALIDATION_LOG << "eglGetNativeClientBufferANDROID is not available.";
    return {};
  }

  EGLClientBuffer client_buffer =
      proc_table.eglGetNativeClientBufferANDROID(hardware_buffer);
  if (client_buffer == nullptr) {
    VALIDATION_LOG
        << "Could not create EGL client buffer from hardware buffer.";
    return {};
  }

  EGLImageKHR image = ::eglCreateImageKHR(display,                    //
                                          EGL_NO_CONTEXT,             //
                                          EGL_NATIVE_BUFFER_ANDROID,  //
                                          client_buffer,              //
                                          nullptr                     //
  );
  if (image == EGL_NO_IMAGE_KHR) {
    VALIDATION_LOG << "Could not create EGLImage from hardware buffer.";
    return {};
  }

  return UniqueEGLImageKHR(EGLImageKHRWithDisplay{image, display});
}

}  // namespace

AHBTextureSourceGLES::AHBTextureSourceGLES(
    const std::shared_ptr<Context>& context,
    EGLDisplay display,
    std::unique_ptr<android::HardwareBuffer> backing_store,
    bool is_swapchain_image)
    : backing_store_(std::move(backing_store)),
      is_swapchain_image_(is_swapchain_image) {
  if (!context || !backing_store_ || !backing_store_->IsValid()) {
    return;
  }

  // The GLES backend resolves GL functions dynamically rather than linking
  // them, so resolve the extension entry point used to associate an EGLImage
  // with a GL texture the same way.
  auto egl_image_target_texture_2d_oes =
      reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
          ::eglGetProcAddress("glEGLImageTargetTexture2DOES"));
  if (egl_image_target_texture_2d_oes == nullptr) {
    VALIDATION_LOG << "glEGLImageTargetTexture2DOES is not available.";
    return;
  }

  // Import the hardware buffer as an EGLImage.
  auto egl_image = CreateEGLImage(display, backing_store_->GetHandle());
  if (!egl_image.is_valid()) {
    return;
  }

  // Create a GL texture and associate the EGLImage with it. This issues GL
  // commands and therefore requires the context to be current on this thread.
  auto reactor = ContextGLES::Cast(*context).GetReactor();
  auto texture = std::make_shared<TextureGLES>(
      reactor, ToTextureDescriptor(backing_store_->GetDescriptor()));
  // The contents are provided by the EGLImage association below rather than
  // being initialized by Impeller.
  texture->MarkContentsInitialized();
  if (!texture->IsValid() || !texture->Bind()) {
    VALIDATION_LOG << "Could not create or bind GL texture for the hardware "
                      "buffer.";
    return;
  }
  egl_image_target_texture_2d_oes(
      GL_TEXTURE_2D, static_cast<GLeglImageOES>(egl_image.get().image));

  egl_image_ = std::move(egl_image);
  reactor_ = std::move(reactor);
  texture_ = std::move(texture);
  is_valid_ = true;
}

AHBTextureSourceGLES::~AHBTextureSourceGLES() {
  // Mali and PowerVR Rogue drivers can crash if the EGLImage is destroyed
  // before the GL texture that wraps it. TextureGLES defers its GL texture
  // deletion to the reactor (the deletion happens on the next reaction), so
  // defer EGLImage destruction onto the reactor as well. ReactorGLES::ReactOnce
  // runs ConsolidateHandles() (which deletes pending GL textures) before
  // FlushOps() (which runs reactor operations), so the texture is freed first;
  // the captured UniqueEGLImageKHR is then destroyed when this operation's
  // lambda is destroyed.
  if (!reactor_ || !egl_image_.is_valid()) {
    return;
  }
  // Drop our reference to the texture so its handle is queued for collection,
  // then schedule the EGLImage destruction to run after that collection.
  texture_.reset();
  [[maybe_unused]] auto scheduled = reactor_->AddOperation(fml::MakeCopyable(
      [egl_image = std::move(egl_image_)](const ReactorGLES&) {}));
}

bool AHBTextureSourceGLES::IsValid() const {
  return is_valid_;
}

bool AHBTextureSourceGLES::IsSwapchainImage() const {
  return is_swapchain_image_;
}

const std::shared_ptr<TextureGLES>& AHBTextureSourceGLES::GetTexture() const {
  return texture_;
}

const android::HardwareBuffer* AHBTextureSourceGLES::GetBackingStore() const {
  return backing_store_.get();
}

}  // namespace impeller
