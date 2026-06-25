// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_SWAPCHAIN_GLES_H_
#define FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_SWAPCHAIN_GLES_H_

#include <memory>

#include "impeller/geometry/size.h"
#include "impeller/renderer/backend/gles/swapchain/ahb/ahb_swapchain_impl_gles.h"
#include "impeller/renderer/context.h"
#include "impeller/renderer/surface.h"
#include "impeller/toolkit/android/surface_control.h"
#include "impeller/toolkit/egl/egl.h"

namespace impeller {

//------------------------------------------------------------------------------
/// @brief      A swapchain that uses hardware buffers presented to a surface
///             control on Android using the OpenGL ES backend. This is the
///             OpenGL ES analog of `AHBSwapchainVK` and enables HCPP platform
///             views on non-Vulkan-capable devices.
///
/// @warning    This swapchain is only available on Android API levels >= 29
///             and requires the native fence sync EGL extensions. Perform the
///             `IsAvailableOnPlatform` check and validate the instance before
///             use; fall back to the EGL window surface otherwise.
///
class AHBSwapchainGLES final {
 public:
  static bool IsAvailableOnPlatform();

  AHBSwapchainGLES(
      const std::shared_ptr<Context>& context,
      EGLDisplay display,
      const std::shared_ptr<android::SurfaceControl>& surface_control,
      const CreateTransactionCB& cb,
      const ISize& size);

  ~AHBSwapchainGLES();

  AHBSwapchainGLES(const AHBSwapchainGLES&) = delete;

  AHBSwapchainGLES& operator=(const AHBSwapchainGLES&) = delete;

  bool IsValid() const;

  //----------------------------------------------------------------------------
  /// @brief      Acquire the next surface to render into and present.
  ///
  /// @warning    Must be called with the OpenGL ES context current on the
  ///             calling thread.
  ///
  std::unique_ptr<Surface> AcquireNextDrawable();

  //----------------------------------------------------------------------------
  /// @brief      Update the size of the swapchain images. If the size changes,
  ///             the underlying swapchain (and its caches) is rebuilt.
  ///
  void UpdateSurfaceSize(const ISize& size);

 private:
  std::weak_ptr<Context> context_;
  EGLDisplay display_ = EGL_NO_DISPLAY;
  std::shared_ptr<android::SurfaceControl> surface_control_;
  CreateTransactionCB cb_;
  std::shared_ptr<AHBSwapchainImplGLES> impl_;
};

}  // namespace impeller

#endif  // FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_SWAPCHAIN_GLES_H_
