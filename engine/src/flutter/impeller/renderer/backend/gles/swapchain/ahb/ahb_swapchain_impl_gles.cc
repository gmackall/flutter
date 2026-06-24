// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "impeller/renderer/backend/gles/swapchain/ahb/ahb_swapchain_impl_gles.h"

#include "flutter/fml/trace_event.h"
#include "impeller/base/validation.h"
#include "impeller/geometry/color.h"
#include "impeller/renderer/backend/gles/context_gles.h"
#include "impeller/renderer/backend/gles/surface_gles.h"
#include "impeller/toolkit/android/surface_transaction_stats.h"
#include "impeller/toolkit/egl/fence.h"

namespace impeller {

std::shared_ptr<AHBSwapchainImplGLES> AHBSwapchainImplGLES::Create(
    const std::weak_ptr<Context>& context,
    EGLDisplay display,
    std::weak_ptr<android::SurfaceControl> surface_control,
    const CreateTransactionCB& cb,
    const ISize& size) {
  auto impl = std::shared_ptr<AHBSwapchainImplGLES>(new AHBSwapchainImplGLES(
      context, display, std::move(surface_control), cb, size));
  return impl->IsValid() ? impl : nullptr;
}

AHBSwapchainImplGLES::AHBSwapchainImplGLES(
    const std::weak_ptr<Context>& context,
    EGLDisplay display,
    std::weak_ptr<android::SurfaceControl> surface_control,
    const CreateTransactionCB& cb,
    const ISize& size)
    : context_(context),
      display_(display),
      surface_control_(std::move(surface_control)),
      cb_(cb) {
  if (display_ == EGL_NO_DISPLAY) {
    VALIDATION_LOG << "Invalid EGL display for swapchain.";
    return;
  }
  // The swapchain synchronizes GPU work with the compositor using native fence
  // syncs. Without them, fall back to a non-surface-control swapchain.
  if (!egl::Fence::IsAvailableOnDisplay(display_)) {
    VALIDATION_LOG << "Native fence syncs are not available; cannot use the "
                      "OpenGL ES AHB swapchain.";
    return;
  }
  desc_ = android::HardwareBufferDescriptor::MakeForSwapchainImage(size);
  pool_ = std::make_shared<AHBTexturePoolGLES>(context, display_, desc_);
  if (!pool_->IsValid()) {
    return;
  }
  auto control = surface_control_.lock();
  is_valid_ = control && control->IsValid();
}

AHBSwapchainImplGLES::~AHBSwapchainImplGLES() = default;

const ISize& AHBSwapchainImplGLES::GetSize() const {
  return desc_.size;
}

bool AHBSwapchainImplGLES::IsValid() const {
  return is_valid_;
}

const android::HardwareBufferDescriptor& AHBSwapchainImplGLES::GetDescriptor()
    const {
  return desc_;
}

RenderTarget AHBSwapchainImplGLES::BuildRenderTarget(
    const std::shared_ptr<Context>& context,
    const std::shared_ptr<AHBTextureSourceGLES>& texture) const {
  RenderTarget render_target;

  ColorAttachment color0;
  color0.texture = texture->GetTexture();
  color0.clear_color = Color::DarkSlateGray();
  color0.load_action = LoadAction::kClear;
  color0.store_action = StoreAction::kStore;
  render_target.SetColorAttachment(color0, 0u);

  // TODO(flutter/flutter#164252): cache the depth-stencil texture across
  // drawables (as the Vulkan swapchain does via SwapchainTransientsVK) and add
  // MSAA support. For now a fresh single-sample depth-stencil is created per
  // drawable.
  render_target.SetupDepthStencilAttachments(
      *context,                            //
      *context->GetResourceAllocator(),    //
      desc_.size,                          //
      /*msaa=*/false,                      //
      "GLESAHBSwapchain",                  //
      RenderTarget::kDefaultStencilAttachmentConfig,  //
      /*depth_stencil_texture=*/nullptr    //
  );

  return render_target;
}

std::unique_ptr<Surface> AHBSwapchainImplGLES::AcquireNextDrawable() {
  if (!is_valid_) {
    return nullptr;
  }

  auto context = context_.lock();
  if (!context) {
    return nullptr;
  }

  auto pool_entry = pool_->Pop();
  if (!pool_entry.IsValid()) {
    VALIDATION_LOG << "Could not create AHB texture source.";
    return nullptr;
  }

  // Wait for the compositor to finish reading the buffer's previous contents
  // before rendering into it again (the render-ready fence). Performed as a
  // server-side (GPU) wait so the CPU is not blocked.
  if (pool_entry.render_ready_fence && pool_entry.render_ready_fence->is_valid()) {
    auto fence = egl::Fence::CreateFromFD(
        display_, std::move(*pool_entry.render_ready_fence));
    if (fence) {
      fence->WaitOnGPU();
    }
  }

  auto texture = pool_entry.texture;
  auto render_target = BuildRenderTarget(context, texture);

  return SurfaceGLES::WrapRenderTarget(
      render_target,  //
      [weak = weak_from_this(), texture]() -> bool {
        auto thiz = weak.lock();
        if (!thiz) {
          VALIDATION_LOG << "Swapchain died before image could be presented.";
          return false;
        }
        return thiz->Present(texture);
      });
}

bool AHBSwapchainImplGLES::Present(
    const std::shared_ptr<AHBTextureSourceGLES>& texture) {
  if (!texture) {
    return false;
  }

  auto control = surface_control_.lock();
  if (!control || !control->IsValid()) {
    VALIDATION_LOG << "Surface control died before swapchain image could be "
                      "presented.";
    return false;
  }

  auto context = context_.lock();
  if (!context) {
    return false;
  }

  // The render commands for this frame have already been issued. Create a fence
  // that is signaled when they complete, then flush so the fence fd becomes
  // available. This is the "present ready" acquire fence the compositor waits
  // on before sampling the buffer.
  const auto& gl = ContextGLES::Cast(*context).GetReactor()->GetProcTable();
  auto present_ready = egl::Fence::Create(display_);
  gl.Flush();

  fml::UniqueFD present_ready_fd;
  if (present_ready) {
    present_ready_fd = present_ready->CreateSyncFD();
  }

  android::SurfaceTransaction transaction =
      cb_ ? cb_() : android::SurfaceTransaction();
  if (!transaction.SetContents(control.get(),               //
                               texture->GetBackingStore(),  //
                               std::move(present_ready_fd)   //
                               )) {
    VALIDATION_LOG << "Could not set swapchain image contents on the surface "
                      "control.";
    return false;
  }

  return transaction.Apply(
      [texture, weak = weak_from_this()](ASurfaceTransactionStats* stats) {
        auto thiz = weak.lock();
        if (!thiz) {
          return;
        }
        thiz->OnTextureUpdatedOnSurfaceControl(texture, stats);
      });
}

void AHBSwapchainImplGLES::OnTextureUpdatedOnSurfaceControl(
    std::shared_ptr<AHBTextureSourceGLES> texture,
    ASurfaceTransactionStats* stats) {
  auto control = surface_control_.lock();
  if (!control) {
    return;
  }

  // Ask for an fd that gets signaled when the previous buffer is released by
  // the compositor. This can be invalid if there is no wait necessary.
  auto render_ready_fence =
      android::CreatePreviousReleaseFence(*control, stats);

  // The transaction completion indicates that the surface control now
  // references the hardware buffer. The previously set buffer can be recycled.
  Lock lock(currently_displayed_texture_mutex_);
  auto old_texture = currently_displayed_texture_;
  currently_displayed_texture_ = std::move(texture);
  pool_->Push(std::move(old_texture), std::move(render_ready_fence));
}

}  // namespace impeller
