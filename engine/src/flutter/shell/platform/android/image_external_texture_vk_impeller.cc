// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_vk_impeller.h"

#include <cstdint>

#include "flutter/fml/closure.h"
#include "flutter/impeller/core/formats.h"
#include "flutter/impeller/core/texture_descriptor.h"
#include "flutter/impeller/display_list/dl_image_impeller.h"
#include "flutter/impeller/renderer/backend/vulkan/android/ahb_texture_source_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/command_buffer_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/texture_vk.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"
#include "flutter/impeller/toolkit/android/proc_table.h"

namespace flutter {

ImageExternalTextureVKImpeller::ImageExternalTextureVKImpeller(
    const std::shared_ptr<impeller::ContextVK>& impeller_context,
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageExternalTexture::ImageLifecycle lifecycle)
    : ImageExternalTexture(id, image_texture_entry, jni_facade, lifecycle),
      impeller_context_(impeller_context) {}

ImageExternalTextureVKImpeller::~ImageExternalTextureVKImpeller() {}

void ImageExternalTextureVKImpeller::Attach(PaintContext& context) {
  if (state_ == AttachmentState::kUninitialized) {
    // First processed frame we are attached.
    state_ = AttachmentState::kAttached;
  }
}

void ImageExternalTextureVKImpeller::Detach() {
  ClearPendingFrameAndWaiter();
}

sk_sp<flutter::DlImage> ImageExternalTextureVKImpeller::ImportHardwareBuffer(
    AHardwareBuffer* latest_hardware_buffer,
    const AHardwareBuffer_Desc& hb_desc) {
  auto texture_source = std::make_shared<impeller::AHBTextureSourceVK>(
      impeller_context_, latest_hardware_buffer, hb_desc);
  if (!texture_source->IsValid()) {
    return nullptr;
  }

  auto texture =
      std::make_shared<impeller::TextureVK>(impeller_context_, texture_source);
  // Transition the layout to shader read.
  {
    auto buffer = impeller_context_->CreateCommandBuffer();
    impeller::CommandBufferVK& buffer_vk =
        impeller::CommandBufferVK::Cast(*buffer);

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
  }

  return impeller::DlImageImpeller::Make(texture);
}

void ImageExternalTextureVKImpeller::ProcessFrame(PaintContext& context,
                                                  const SkRect& bounds) {
  AcquiredFrame acquired = AcquireNextOrPendingFrame();
  if (acquired.ahardware_buffer == nullptr) {
    return;
  }

  // Wait on the acquire fence before `ImportHardwareBuffer` so a slow fence
  // that hits `kTimedOut` leaves `pending_frame_` untouched without allocating
  // a transient Vulkan image or submitting a premature layout transition.
  // Pending-frame retries use a 0 ms non-blocking check so intermediate
  // repaints never re-stall the raster thread.
  const int wait_timeout_ms =
      acquired.is_pending_retry ? 0 : fence_wait_timeout_ms_;
  const auto wait_result = NdkSurfaceProducer::WaitAcquireFenceFdBounded(
      acquired.acquire_fence_fd, wait_timeout_ms);
  if (wait_result == NdkSurfaceProducer::FenceWaitResult::kTimedOut) {
    RetainPendingFrameOnTimeout(std::move(acquired));
    return;
  }
  CancelPendingFrameWaiter();
  fml::ScopedCleanupClosure release_frame(
      [this, &acquired]() { ReleaseAcquiredFrame(acquired); });
  if (wait_result != NdkSurfaceProducer::FenceWaitResult::kSignaled) {
    return;
  }

  AHardwareBuffer* latest_hardware_buffer = acquired.ahardware_buffer;
  std::optional<HardwareBufferKey> key =
      impeller::android::HardwareBuffer::GetSystemUniqueID(
          latest_hardware_buffer);
  if (auto cached_image = image_lru_.TryFindImage(key)) {
    dl_image_ = std::move(cached_image);
    MarkFrameDisplayed(acquired);
    return;
  }

  auto hb_desc =
      impeller::android::HardwareBuffer::Describe(latest_hardware_buffer);
  if (!hb_desc.has_value()) {
    return;
  }
  auto existing_image = image_lru_.FindImage(key, hb_desc.value());
  if (existing_image != nullptr) {
    dl_image_ = existing_image;
    MarkFrameDisplayed(acquired);
    return;
  }

  auto imported = ImportHardwareBuffer(latest_hardware_buffer, hb_desc.value());
  if (!imported) {
    return;
  }

  dl_image_ = std::move(imported);
  if (key.has_value()) {
    image_lru_.AddImage(dl_image_, key.value());
  }
  MarkFrameDisplayed(acquired);
}

}  // namespace flutter
