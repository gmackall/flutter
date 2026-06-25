// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "impeller/renderer/backend/gles/swapchain/ahb/ahb_swapchain_gles.h"

#include "flutter/fml/trace_event.h"
#include "impeller/base/validation.h"
#include "impeller/toolkit/android/hardware_buffer.h"

namespace impeller {

bool AHBSwapchainGLES::IsAvailableOnPlatform() {
  return android::SurfaceControl::IsAvailableOnPlatform() &&
         android::HardwareBuffer::IsAvailableOnPlatform();
}

AHBSwapchainGLES::AHBSwapchainGLES(
    const std::shared_ptr<Context>& context,
    EGLDisplay display,
    const std::shared_ptr<android::SurfaceControl>& surface_control,
    const CreateTransactionCB& cb,
    const ISize& size)
    : context_(context),
      display_(display),
      surface_control_(surface_control),
      cb_(cb) {
  UpdateSurfaceSize(size);
}

AHBSwapchainGLES::~AHBSwapchainGLES() = default;

bool AHBSwapchainGLES::IsValid() const {
  return impl_ ? impl_->IsValid() : false;
}

std::unique_ptr<Surface> AHBSwapchainGLES::AcquireNextDrawable() {
  if (!IsValid()) {
    return nullptr;
  }
  TRACE_EVENT0("impeller", __FUNCTION__);
  return impl_->AcquireNextDrawable();
}

void AHBSwapchainGLES::UpdateSurfaceSize(const ISize& size) {
  if (impl_ && impl_->GetSize() == size) {
    return;
  }
  TRACE_EVENT0("impeller", __FUNCTION__);
  auto impl = AHBSwapchainImplGLES::Create(context_,          //
                                           display_,          //
                                           surface_control_,  //
                                           cb_,               //
                                           size               //
  );
  if (!impl || !impl->IsValid()) {
    VALIDATION_LOG << "Could not resize swapchain to size: " << size;
    return;
  }
  impl_ = std::move(impl);
}

}  // namespace impeller
