// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_ANDROID_AHB_TEXTURE_SOURCE_GLES_H_
#define FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_ANDROID_AHB_TEXTURE_SOURCE_GLES_H_

#include <memory>

#include "impeller/renderer/backend/gles/texture_gles.h"
#include "impeller/toolkit/android/hardware_buffer.h"
#include "impeller/toolkit/egl/egl.h"
#include "impeller/toolkit/egl/image.h"

namespace impeller {

class Context;

//------------------------------------------------------------------------------
/// @brief      Wraps an `AHardwareBuffer` as an OpenGL ES `GL_TEXTURE_2D` that
///             can be used as a color render target.
///
///             The hardware buffer is imported as an `EGLImage` (via
///             `EGL_ANDROID_image_native_buffer`) which is then associated with
///             a GL texture using `glEGLImageTargetTexture2DOES`. This is the
///             OpenGL ES analog of `AHBTextureSourceVK` and is used to back
///             swapchain images for the OpenGL ES AHB swapchain.
///
/// @warning    Construction issues OpenGL commands and therefore requires a
///             context to be current on the calling thread. The buffer is only
///             available on Android API levels >= 26 (and the swapchain that
///             uses it requires >= 29).
///
class AHBTextureSourceGLES {
 public:
  //----------------------------------------------------------------------------
  /// @brief      Wrap the provided hardware buffer as a GL render target
  ///             texture.
  ///
  /// @param[in]  context          The OpenGL ES context.
  /// @param[in]  display          The EGL display used to create the EGLImage.
  /// @param[in]  backing_store    The hardware buffer to wrap.
  /// @param[in]  is_swapchain_image  Whether this texture is being used as a
  ///                                 swapchain image.
  ///
  AHBTextureSourceGLES(const std::shared_ptr<Context>& context,
                       EGLDisplay display,
                       std::unique_ptr<android::HardwareBuffer> backing_store,
                       bool is_swapchain_image);

  ~AHBTextureSourceGLES();

  AHBTextureSourceGLES(const AHBTextureSourceGLES&) = delete;

  AHBTextureSourceGLES& operator=(const AHBTextureSourceGLES&) = delete;

  //----------------------------------------------------------------------------
  /// @return     If the texture source is valid. Invalid texture sources must
  ///             be discarded.
  ///
  bool IsValid() const;

  //----------------------------------------------------------------------------
  /// @return     If this texture is being used as a swapchain image.
  ///
  bool IsSwapchainImage() const;

  //----------------------------------------------------------------------------
  /// @return     The Impeller texture wrapping the hardware buffer. Can be used
  ///             as a color render target.
  ///
  const std::shared_ptr<TextureGLES>& GetTexture() const;

  //----------------------------------------------------------------------------
  /// @return     The hardware buffer backing this texture. Used to set the
  ///             contents of a surface control.
  ///
  const android::HardwareBuffer* GetBackingStore() const;

 private:
  std::unique_ptr<android::HardwareBuffer> backing_store_;
  UniqueEGLImageKHR egl_image_;
  std::shared_ptr<TextureGLES> texture_;
  bool is_swapchain_image_ = false;
  bool is_valid_ = false;
};

}  // namespace impeller

#endif  // FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_ANDROID_AHB_TEXTURE_SOURCE_GLES_H_
