// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_SWAPCHAIN_IMPL_GLES_H_
#define FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_SWAPCHAIN_IMPL_GLES_H_

#include <functional>
#include <memory>

#include "impeller/base/thread.h"
#include "impeller/geometry/size.h"
#include "impeller/renderer/backend/gles/android/ahb_texture_source_gles.h"
#include "impeller/renderer/backend/gles/swapchain/ahb/ahb_texture_pool_gles.h"
#include "impeller/renderer/context.h"
#include "impeller/renderer/render_target.h"
#include "impeller/renderer/surface.h"
#include "impeller/toolkit/android/hardware_buffer.h"
#include "impeller/toolkit/android/surface_control.h"
#include "impeller/toolkit/android/surface_transaction.h"
#include "impeller/toolkit/egl/egl.h"

namespace impeller {

using CreateTransactionCB = std::function<android::SurfaceTransaction()>;

//------------------------------------------------------------------------------
/// @brief      The implementation of an OpenGL ES swapchain, at a specific
///             size, that renders into hardware buffers presented to a surface
///             control on Android. This is the OpenGL ES analog of
///             `AHBSwapchainImplVK`.
///
///             Resizes to the surface cause the instance of the swapchain impl
///             at that size to be discarded along with all its caches.
///
class AHBSwapchainImplGLES final
    : public std::enable_shared_from_this<AHBSwapchainImplGLES> {
 public:
  //----------------------------------------------------------------------------
  /// @brief      Create a swapchain of a specific size whose images will be
  ///             presented to the provided surface control.
  ///
  /// @param[in]  context          The OpenGL ES context.
  /// @param[in]  display          The EGL display.
  /// @param[in]  surface_control  The surface control to present images to.
  /// @param[in]  cb               A callback that vends surface transactions
  ///                              (for platform view interop). May be empty.
  /// @param[in]  size             The size of the swapchain images.
  ///
  /// @return     A valid swapchain impl if one can be created. `nullptr`
  ///             otherwise.
  ///
  static std::shared_ptr<AHBSwapchainImplGLES> Create(
      const std::weak_ptr<Context>& context,
      EGLDisplay display,
      std::weak_ptr<android::SurfaceControl> surface_control,
      const CreateTransactionCB& cb,
      const ISize& size);

  ~AHBSwapchainImplGLES();

  AHBSwapchainImplGLES(const AHBSwapchainImplGLES&) = delete;

  AHBSwapchainImplGLES& operator=(const AHBSwapchainImplGLES&) = delete;

  //----------------------------------------------------------------------------
  /// @return     The size of the swapchain images.
  ///
  const ISize& GetSize() const;

  //----------------------------------------------------------------------------
  /// @return     If the swapchain impl is valid. If it is not, the instance
  ///             must be discarded. There is no error recovery.
  ///
  bool IsValid() const;

  //----------------------------------------------------------------------------
  /// @return     The descriptor used to create the hardware buffers.
  ///
  const android::HardwareBufferDescriptor& GetDescriptor() const;

  //----------------------------------------------------------------------------
  /// @brief      Acquire the next surface to render into and present.
  ///
  /// @warning    Must be called with the OpenGL ES context current on the
  ///             calling thread.
  ///
  /// @return     A surface if one can be created. `nullptr` otherwise.
  ///
  std::unique_ptr<Surface> AcquireNextDrawable();

 private:
  std::weak_ptr<Context> context_;
  EGLDisplay display_ = EGL_NO_DISPLAY;
  std::weak_ptr<android::SurfaceControl> surface_control_;
  android::HardwareBufferDescriptor desc_;
  std::shared_ptr<AHBTexturePoolGLES> pool_;
  CreateTransactionCB cb_;

  Mutex currently_displayed_texture_mutex_;
  std::shared_ptr<AHBTextureSourceGLES> currently_displayed_texture_
      IPLR_GUARDED_BY(currently_displayed_texture_mutex_);

  bool is_valid_ = false;

  AHBSwapchainImplGLES(const std::weak_ptr<Context>& context,
                       EGLDisplay display,
                       std::weak_ptr<android::SurfaceControl> surface_control,
                       const CreateTransactionCB& cb,
                       const ISize& size);

  RenderTarget BuildRenderTarget(
      const std::shared_ptr<Context>& context,
      const std::shared_ptr<AHBTextureSourceGLES>& texture) const;

  bool Present(const std::shared_ptr<AHBTextureSourceGLES>& texture);

  void OnTextureUpdatedOnSurfaceControl(
      std::shared_ptr<AHBTextureSourceGLES> texture,
      ASurfaceTransactionStats* stats);
};

}  // namespace impeller

#endif  // FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_SWAPCHAIN_IMPL_GLES_H_
