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
#include "flutter/impeller/renderer/backend/vulkan/capabilities_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/command_buffer_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/command_queue_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/texture_vk.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"

namespace flutter {

ImageExternalTextureVKImpeller::ImageExternalTextureVKImpeller(
    const std::shared_ptr<impeller::ContextVK>& impeller_context,
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageExternalTexture::ImageLifecycle lifecycle,
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge,
    std::shared_ptr<IncrementalRetireQueue> retire_queue)
    : ImageExternalTexture(id,
                           image_texture_entry,
                           jni_facade,
                           lifecycle,
                           std::move(ndk_bridge),
                           std::move(retire_queue)),
      impeller_context_(impeller_context) {}

ImageExternalTextureVKImpeller::~ImageExternalTextureVKImpeller() = default;

uint32_t ImageExternalTextureVKImpeller::SelectExternalSourceQueueFamily(
    const impeller::CapabilitiesVK& capabilities) {
  return capabilities.HasExtension(
             impeller::RequiredAndroidDeviceExtensionVK::kEXTQueueFamilyForeign)
             ? VK_QUEUE_FAMILY_FOREIGN_EXT
             : VK_QUEUE_FAMILY_EXTERNAL;
}

void ImageExternalTextureVKImpeller::Attach(PaintContext& /*context*/) {
  if (state_ == AttachmentState::kUninitialized) {
    state_ = AttachmentState::kAttached;
  }
}

void ImageExternalTextureVKImpeller::Detach() {}

impeller::vk::UniqueSemaphore
ImageExternalTextureVKImpeller::TryImportAcquireFenceSemaphore(
    fml::UniqueFD& acquire_fence_fd) {
  if (!acquire_fence_fd.is_valid() || !impeller_context_) {
    return {};
  }
  const auto& caps_vk =
      impeller::CapabilitiesVK::Cast(*impeller_context_->GetCapabilities());
  if (!caps_vk.SupportsExternalSemaphoreExtensions()) {
    return {};
  }

  const auto& device = impeller_context_->GetDevice();
  auto [sem_res, semaphore] = device.createSemaphoreUnique({});
  if (sem_res != impeller::vk::Result::eSuccess) {
    return {};
  }

  impeller::vk::ImportSemaphoreFdInfoKHR import_info;
  import_info.semaphore = *semaphore;
  import_info.fd = acquire_fence_fd.get();
  import_info.handleType =
      impeller::vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd;
  import_info.flags = impeller::vk::SemaphoreImportFlagBitsKHR::eTemporary;

  if (device.importSemaphoreFdKHR(import_info) !=
      impeller::vk::Result::eSuccess) {
    return {};
  }

  // Ownership of the sync_file FD transferred to the Vulkan driver.
  (void)acquire_fence_fd.release();
  return std::move(semaphore);
}

bool ImageExternalTextureVKImpeller::SubmitExternalAcquireBarrier(
    const std::shared_ptr<impeller::TextureVK>& texture,
    fml::UniqueFD& acquire_fence_fd) {
  if (!impeller_context_ || !texture) {
    acquire_fence_fd.reset();
    return false;
  }

  impeller::vk::UniqueSemaphore wait_semaphore =
      TryImportAcquireFenceSemaphore(acquire_fence_fd);
  if (acquire_fence_fd.is_valid()) {
    if (!WaitAcquireFenceFd(acquire_fence_fd)) {
      return false;
    }
  }

  auto buffer = impeller_context_->CreateCommandBuffer();
  if (!buffer) {
    return false;
  }
  impeller::CommandBufferVK& buffer_vk =
      impeller::CommandBufferVK::Cast(*buffer);
  if (!buffer_vk.Track(texture)) {
    return false;
  }

  const auto& caps_vk =
      impeller::CapabilitiesVK::Cast(*impeller_context_->GetCapabilities());
  const uint32_t src_queue_family = SelectExternalSourceQueueFamily(caps_vk);
  const uint32_t dst_queue_family = static_cast<uint32_t>(
      impeller_context_->GetGraphicsQueue()->GetIndex().family);

  // Transitioning from eUndefined on a queue-family ownership acquire from
  // VK_QUEUE_FAMILY_FOREIGN_EXT / VK_QUEUE_FAMILY_EXTERNAL preserves external
  // AHardwareBuffer contents per
  // VK_ANDROID_external_memory_android_hardware_buffer.
  texture->SetLayoutWithoutEncoding(impeller::vk::ImageLayout::eUndefined);

  impeller::BarrierVK barrier;
  barrier.cmd_buffer = buffer_vk.GetCommandBuffer();
  barrier.src_access = {};
  barrier.src_stage = impeller::vk::PipelineStageFlagBits::eTopOfPipe;
  barrier.dst_access = impeller::vk::AccessFlagBits::eShaderRead;
  barrier.dst_stage = impeller::vk::PipelineStageFlagBits::eFragmentShader;
  barrier.new_layout = impeller::vk::ImageLayout::eShaderReadOnlyOptimal;
  barrier.src_queue_family_index = src_queue_family;
  barrier.dst_queue_family_index = dst_queue_family;

  if (!texture->SetLayout(barrier)) {
    return false;
  }

  if (wait_semaphore) {
    auto& queue_vk = static_cast<impeller::CommandQueueVK&>(
        *impeller_context_->GetCommandQueue());
    return queue_vk
        .Submit(buffer, std::move(wait_semaphore),
                impeller::vk::PipelineStageFlagBits::eFragmentShader)
        .ok();
  }
  return impeller_context_->GetCommandQueue()->Submit({buffer}).ok();
}

sk_sp<flutter::DlImage> ImageExternalTextureVKImpeller::ImportHardwareBuffer(
    AHardwareBuffer* hardware_buffer,
    const AHardwareBuffer_Desc& desc,
    fml::UniqueFD& acquire_fence_fd,
    std::function<bool(fml::UniqueFD&)>* out_on_new_frame_hit) {
  if (!impeller_context_ || hardware_buffer == nullptr) {
    acquire_fence_fd.reset();
    return nullptr;
  }

  auto texture_source = std::make_shared<impeller::AHBTextureSourceVK>(
      impeller_context_, hardware_buffer, desc);
  if (!texture_source->IsValid()) {
    acquire_fence_fd.reset();
    return nullptr;
  }

  auto texture =
      std::make_shared<impeller::TextureVK>(impeller_context_, texture_source);
  if (!SubmitExternalAcquireBarrier(texture, acquire_fence_fd)) {
    return nullptr;
  }

  if (out_on_new_frame_hit != nullptr) {
    std::weak_ptr<impeller::TextureVK> weak_texture = texture;
    *out_on_new_frame_hit = [this, weak_texture](fml::UniqueFD& fence_fd) {
      auto tex = weak_texture.lock();
      if (!tex) {
        fence_fd.reset();
        return false;
      }
      return SubmitExternalAcquireBarrier(tex, fence_fd);
    };
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

  auto hb_desc =
      impeller::android::HardwareBuffer::Describe(frame.ahardware_buffer);
  if (!hb_desc.has_value() || hb_desc->width == 0 || hb_desc->height == 0) {
    return;
  }

  bool needs_ahb_retain = false;
  const ImageCacheKey key = MakeCacheKey(
      frame.reader_generation, frame.ahardware_buffer, &needs_ahb_retain);
  const bool is_protected = IsProtectedContent(hb_desc.value());

  if (!is_protected) {
    if (CacheEntry* hit = FindCachedEntry(key, hb_desc.value())) {
      if (hit->on_new_frame_hit) {
        if (!hit->on_new_frame_hit(frame.acquire_fence_fd)) {
          return;
        }
      } else if (!WaitAcquireFenceFd(frame.acquire_fence_fd)) {
        return;
      }
      SetCurrentDlImage(hit->dl_image, hit->ahb_ref);
      return;
    }
    if (IsFailedImportKey(key)) {
      return;
    }
  }

  std::function<bool(fml::UniqueFD&)> on_new_frame_hit;
  sk_sp<flutter::DlImage> imported = ImportHardwareBuffer(
      frame.ahardware_buffer, hb_desc.value(), frame.acquire_fence_fd,
      is_protected ? nullptr : &on_new_frame_hit);
  if (!imported) {
    if (!is_protected && impeller_context_ != nullptr) {
      RecordFailedImport(key);
    }
    return;
  }

  std::shared_ptr<ScopedAHardwareBufferRef> ahb_ref;
  if (needs_ahb_retain) {
    ahb_ref = frame.caller_ahb_ref
                  ? frame.caller_ahb_ref
                  : std::make_shared<ScopedAHardwareBufferRef>(
                        frame.ahardware_buffer, /*acquire=*/true);
  }

  SetCurrentDlImage(imported, ahb_ref);
  if (!is_protected) {
    InsertCacheEntry(key, hb_desc.value(), std::move(imported),
                     std::move(ahb_ref), std::move(on_new_frame_hit));
  }
}

}  // namespace flutter
