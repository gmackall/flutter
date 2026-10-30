// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_vk_impeller.h"

#include <errno.h>
#include <poll.h>

#include <cstdint>

#include "flutter/impeller/core/formats.h"
#include "flutter/impeller/core/texture_descriptor.h"
#include "flutter/impeller/display_list/dl_image_impeller.h"
#include "flutter/impeller/renderer/backend/vulkan/android/ahb_texture_source_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/capabilities_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/command_buffer_vk.h"
#include "flutter/impeller/renderer/backend/vulkan/texture_vk.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"

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

void ImageExternalTextureVKImpeller::Detach() {}

void ImageExternalTextureVKImpeller::ImportOrWaitAcquireFence(
    fml::UniqueFD acquire_fence_fd,
    impeller::vk::PipelineStageFlags wait_stage) {
  if (!acquire_fence_fd.is_valid()) {
    return;
  }
  const auto& caps_vk =
      impeller::CapabilitiesVK::Cast(*impeller_context_->GetCapabilities());
  if (caps_vk.HasExtension(impeller::OptionalAndroidDeviceExtensionVK::
                               kKHRExternalSemaphoreFd)) {
    const auto& device = impeller_context_->GetDevice();
    auto [sem_res, semaphore] = device.createSemaphoreUnique({});
    if (sem_res == impeller::vk::Result::eSuccess) {
      // Release Bionic fdsan ownership BEFORE vkImportSemaphoreFdKHR, because
      // the Vulkan driver closes the imported sync_file FD inside the call on
      // success. If vkImportSemaphoreFdKHR fails, reclaim ownership for poll().
      const int raw_fd = acquire_fence_fd.release();
      impeller::vk::ImportSemaphoreFdInfoKHR import_info;
      import_info.semaphore = *semaphore;
      import_info.fd = raw_fd;
      import_info.handleType =
          impeller::vk::ExternalSemaphoreHandleTypeFlagBits::eSyncFd;
      import_info.flags = impeller::vk::SemaphoreImportFlagBitsKHR::eTemporary;
      if (device.importSemaphoreFdKHR(import_info) ==
          impeller::vk::Result::eSuccess) {
        impeller_context_->EnqueueExternalWaitSemaphore(std::move(semaphore),
                                                        wait_stage);
        return;
      }
      acquire_fence_fd.reset(raw_fd);
    }
  }
  struct pollfd pfd = {
      .fd = acquire_fence_fd.get(),
      .events = POLLIN,
      .revents = 0,
  };
  int ret = 0;
  do {
    ret = ::poll(&pfd, 1, -1);
  } while (ret == -1 && errno == EINTR);
}

void ImageExternalTextureVKImpeller::ProcessFrame(PaintContext& context,
                                                  const SkRect& bounds) {
  auto acquired = AcquireLatestFrame();
  if (acquired.ahardware_buffer == nullptr) {
    return;
  }
  AHardwareBuffer* latest_hardware_buffer = acquired.ahardware_buffer;

  std::optional<HardwareBufferKey> key =
      impeller::android::HardwareBuffer::GetSystemUniqueID(
          latest_hardware_buffer);
  if (auto cached_image = image_lru_.TryFindImage(key)) {
    dl_image_ = std::move(cached_image);
    ImportOrWaitAcquireFence(
        std::move(acquired.acquire_fence_fd),
        impeller::vk::PipelineStageFlagBits::eFragmentShader);
    if (!acquired.owned_by_producer) {
      CloseHardwareBuffer(acquired.java_hardware_buffer);
    }
    return;
  }

  auto hb_desc =
      impeller::android::HardwareBuffer::Describe(latest_hardware_buffer);
  if (!hb_desc.has_value()) {
    if (!acquired.owned_by_producer) {
      CloseHardwareBuffer(acquired.java_hardware_buffer);
    }
    return;
  }
  auto existing_image = image_lru_.FindImage(key, hb_desc.value());
  if (existing_image != nullptr) {
    dl_image_ = existing_image;
    ImportOrWaitAcquireFence(
        std::move(acquired.acquire_fence_fd),
        impeller::vk::PipelineStageFlagBits::eFragmentShader);
    if (!acquired.owned_by_producer) {
      CloseHardwareBuffer(acquired.java_hardware_buffer);
    }
    return;
  }

  auto texture_source = std::make_shared<impeller::AHBTextureSourceVK>(
      impeller_context_, latest_hardware_buffer, hb_desc.value());
  if (!texture_source->IsValid()) {
    if (!acquired.owned_by_producer) {
      CloseHardwareBuffer(acquired.java_hardware_buffer);
    }
    return;
  }

  // Initial AHB wrap (cold cache miss, at most MAX_IMAGES=8 per PlatformView):
  // wait on the acquire fence before submitting the one-time SetLayout barrier
  // command buffer so we don't drain sibling PlatformViews' onscreen wait
  // semaphores onto an empty barrier submission.
  if (acquired.acquire_fence_fd.is_valid()) {
    struct pollfd pfd = {
        .fd = acquired.acquire_fence_fd.get(),
        .events = POLLIN,
        .revents = 0,
    };
    int ret = 0;
    do {
      ret = ::poll(&pfd, 1, -1);
    } while (ret == -1 && errno == EINTR);
    acquired.acquire_fence_fd.reset();
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
      if (!acquired.owned_by_producer) {
        CloseHardwareBuffer(acquired.java_hardware_buffer);
      }
      return;
    }
    if (!impeller_context_->GetCommandQueue()->Submit({buffer}).ok()) {
      if (!acquired.owned_by_producer) {
        CloseHardwareBuffer(acquired.java_hardware_buffer);
      }
      return;
    }
  }

  dl_image_ = impeller::DlImageImpeller::Make(texture);
  if (key.has_value()) {
    image_lru_.AddImage(dl_image_, key.value());
  }
  if (!acquired.owned_by_producer) {
    CloseHardwareBuffer(acquired.java_hardware_buffer);
  }
}

}  // namespace flutter
