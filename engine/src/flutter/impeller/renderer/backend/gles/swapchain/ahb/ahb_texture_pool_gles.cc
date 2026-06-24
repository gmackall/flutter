// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "impeller/renderer/backend/gles/swapchain/ahb/ahb_texture_pool_gles.h"

#include "flutter/fml/trace_event.h"
#include "impeller/base/validation.h"

namespace impeller {

AHBTexturePoolGLES::AHBTexturePoolGLES(std::weak_ptr<Context> context,
                                       EGLDisplay display,
                                       android::HardwareBufferDescriptor desc)
    : context_(std::move(context)), display_(display), desc_(desc) {
  if (!desc_.IsAllocatable()) {
    VALIDATION_LOG << "Swapchain image is not allocatable.";
    return;
  }
  is_valid_ = true;
}

AHBTexturePoolGLES::~AHBTexturePoolGLES() = default;

AHBTexturePoolGLES::PoolEntry AHBTexturePoolGLES::Pop() {
  {
    Lock lock(pool_mutex_);
    if (!pool_.empty()) {
      // Buffers are pushed to the back of the queue. To give the ready fences
      // the most time to signal, pick a buffer from the front of the queue.
      auto entry = pool_.front();
      pool_.pop_front();
      return entry;
    }
  }
  return PoolEntry{CreateTexture()};
}

void AHBTexturePoolGLES::Push(std::shared_ptr<AHBTextureSourceGLES> texture,
                              fml::UniqueFD render_ready_fence) {
  if (!texture) {
    return;
  }
  Lock lock(pool_mutex_);
  pool_.push_back(PoolEntry{std::move(texture), std::move(render_ready_fence)});
}

std::shared_ptr<AHBTextureSourceGLES> AHBTexturePoolGLES::CreateTexture() const {
  TRACE_EVENT0("impeller", "CreateSwapchainTexture");
  auto context = context_.lock();
  if (!context) {
    VALIDATION_LOG << "Context died before image could be created.";
    return nullptr;
  }

  auto ahb = std::make_unique<android::HardwareBuffer>(desc_);
  if (!ahb->IsValid()) {
    VALIDATION_LOG << "Could not create hardware buffer of size: "
                   << desc_.size;
    return nullptr;
  }

  auto ahb_texture_source = std::make_shared<AHBTextureSourceGLES>(
      context, display_, std::move(ahb), /*is_swapchain_image=*/true);
  if (!ahb_texture_source->IsValid()) {
    VALIDATION_LOG << "Could not create hardware buffer texture source for "
                      "swapchain image of size: "
                   << desc_.size;
    return nullptr;
  }

  return ahb_texture_source;
}

bool AHBTexturePoolGLES::IsValid() const {
  return is_valid_;
}

}  // namespace impeller
