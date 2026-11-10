// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_vk_impeller.h"

#include <cstdint>
#include <utility>

#include "flutter/fml/closure.h"
#include "flutter/impeller/core/formats.h"
#include "flutter/impeller/core/texture_descriptor.h"
#include "flutter/impeller/display_list/dl_image_impeller.h"
#include "flutter/impeller/renderer/backend/vulkan/android/ahb_texture_source_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/command_buffer_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/texture_vk.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"

namespace flutter {

ImageExternalTextureVKImpeller::ImageExternalTextureVKImpeller(
    const std::shared_ptr<impeller::ContextVK>& impeller_context,
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageExternalTexture::ImageLifecycle lifecycle,
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge)
    : ImageExternalTexture(id,
                           image_texture_entry,
                           jni_facade,
                           lifecycle,
                           std::move(ndk_bridge)),
      impeller_context_(impeller_context) {}

ImageExternalTextureVKImpeller::~ImageExternalTextureVKImpeller() = default;

void ImageExternalTextureVKImpeller::Attach(PaintContext& /*context*/) {
  if (state_ == AttachmentState::kUninitialized) {
    state_ = AttachmentState::kAttached;
  }
}

void ImageExternalTextureVKImpeller::Detach() {}

sk_sp<flutter::DlImage> ImageExternalTextureVKImpeller::ImportHardwareBuffer(
    AHardwareBuffer* hardware_buffer,
    const AHardwareBuffer_Desc& desc) {
  if (!impeller_context_ || hardware_buffer == nullptr) {
    return nullptr;
  }
  auto texture_source = std::make_shared<impeller::AHBTextureSourceVK>(
      impeller_context_, hardware_buffer, desc);
  if (!texture_source->IsValid()) {
    return nullptr;
  }

  auto texture =
      std::make_shared<impeller::TextureVK>(impeller_context_, texture_source);
  auto buffer = impeller_context_->CreateCommandBuffer();
  if (!buffer) {
    return nullptr;
  }
  impeller::CommandBufferVK& buffer_vk =
      impeller::CommandBufferVK::Cast(*buffer);
  if (!buffer_vk.Track(texture)) {
    return nullptr;
  }

  impeller::BarrierVK barrier;
  barrier.cmd_buffer = buffer_vk.GetCommandBuffer();
  barrier.src_access = impeller::vk::AccessFlagBits::eColorAttachmentWrite |
                       impeller::vk::AccessFlagBits::eTransferWrite;
  barrier.src_stage =
      impeller::vk::PipelineStageFlagBits::eColorAttachmentOutput |
      impeller::vk::PipelineStageFlagBits::eTransfer;
  barrier.dst_access = impeller::vk::AccessFlagBits::eShaderRead;
  barrier.dst_stage = impeller::vk::PipelineStageFlagBits::eFragmentShader;
  barrier.new_layout = impeller::vk::ImageLayout::eShaderReadOnlyOptimal;

  if (!texture->SetLayout(barrier)) {
    return nullptr;
  }
  if (!impeller_context_->GetCommandQueue()->Submit({buffer}).ok()) {
    return nullptr;
  }
  return impeller::DlImageImpeller::Make(texture);
}

void ImageExternalTextureVKImpeller::ProcessFrame(PaintContext& /*context*/,
                                                  const SkRect& /*bounds*/) {
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

  auto hb_desc =
      impeller::android::HardwareBuffer::Describe(frame.ahardware_buffer);
  if (!hb_desc.has_value()) {
    return;
  }

  sk_sp<flutter::DlImage> imported =
      ImportHardwareBuffer(frame.ahardware_buffer, hb_desc.value());
  if (!imported) {
    return;
  }
  dl_image_ = imported;
  CacheImage(frame.reader_generation, key, std::move(imported));
}

}  // namespace flutter
