// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_TEXTURE_POOL_GLES_H_
#define FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_TEXTURE_POOL_GLES_H_

#include <deque>

#include "flutter/fml/unique_fd.h"
#include "impeller/base/thread.h"
#include "impeller/renderer/backend/gles/android/ahb_texture_source_gles.h"
#include "impeller/toolkit/egl/egl.h"

namespace impeller {

//------------------------------------------------------------------------------
/// @brief      A recycling pool of hardware buffer backed texture sources that
///             can be used as swapchain images.
///
///             If a previously cached entry cannot be obtained from the pool, a
///             new entry is created. The only case where a valid texture source
///             cannot be obtained is due to resource exhaustion.
///
///             Pools are thread-safe.
///
/// @warning    The pool is not explicitly capped: `Push` always caches the
///             returned texture and `Pop` allocates a new one when empty.
///             Steady-state vsync pacing keeps the working set small (~the
///             number of in-flight frames), but unlike the Vulkan swapchain
///             (which bounds in-flight frames via per-frame acquire fences,
///             `kMaxPendingPresents`), this pool has no CPU backpressure, so a
///             stalled compositor could let the raster thread accumulate
///             buffers. Adding an explicit bound (the `egl::Fence::WaitOnCPU`
///             primitive already exists) is a follow-up (flutter#164252).
///
/// @warning    `Pop` may create a new texture source which issues OpenGL
///             commands. Callers must ensure a context is current on the
///             calling thread (the swapchain pops during drawable acquisition
///             while the context is current).
///
class AHBTexturePoolGLES {
 public:
  struct PoolEntry {
    std::shared_ptr<AHBTextureSourceGLES> texture;
    std::shared_ptr<fml::UniqueFD> render_ready_fence;

    explicit PoolEntry(std::shared_ptr<AHBTextureSourceGLES> p_item,
                       fml::UniqueFD p_render_ready_fence = {})
        : texture(std::move(p_item)),
          render_ready_fence(std::make_shared<fml::UniqueFD>(
              std::move(p_render_ready_fence))) {}

    constexpr bool IsValid() const { return !!texture; }
  };

  //----------------------------------------------------------------------------
  /// @brief      Create a new (empty) texture pool.
  ///
  /// @param[in]  context  The context whose reactor will be used to create the
  ///                      resources for the texture sources.
  /// @param[in]  display  The EGL display used to import hardware buffers as
  ///                      EGLImages.
  /// @param[in]  desc     The descriptor of the hardware buffers that will be
  ///                      used to create the backing stores of the texture
  ///                      sources.
  ///
  explicit AHBTexturePoolGLES(std::weak_ptr<Context> context,
                              EGLDisplay display,
                              android::HardwareBufferDescriptor desc);

  ~AHBTexturePoolGLES();

  AHBTexturePoolGLES(const AHBTexturePoolGLES&) = delete;

  AHBTexturePoolGLES& operator=(const AHBTexturePoolGLES&) = delete;

  //----------------------------------------------------------------------------
  /// @brief      If the pool can create and pool hardware buffer backed texture
  ///             sources. The only reason valid textures cannot be obtained
  ///             from a valid pool is because of resource exhaustion.
  ///
  /// @return     `true` if valid, `false` otherwise.
  ///
  bool IsValid() const;

  //----------------------------------------------------------------------------
  /// @brief      Pops a texture source from the pool. If the pool is empty, a
  ///             new texture source is created and returned.
  ///
  ///             This operation is thread-safe.
  ///
  /// @return     A texture source that can be used as a swapchain image. This
  ///             can be nullptr in case of resource exhaustion.
  ///
  PoolEntry Pop();

  //----------------------------------------------------------------------------
  /// @brief      Push a popped texture back into the pool.
  ///
  ///             This operation is thread-safe.
  ///
  /// @warning    Only a texture source obtained from the same pool can be
  ///             returned to it. It is user error to mix and match texture
  ///             sources from different pools.
  ///
  /// @param[in]  texture             The texture to be returned to the pool.
  /// @param[in]  render_ready_fence  A sync fd that is signaled once the
  ///                                 previous use of the buffer is no longer
  ///                                 being read by the compositor.
  ///
  void Push(std::shared_ptr<AHBTextureSourceGLES> texture,
            fml::UniqueFD render_ready_fence);

 private:
  const std::weak_ptr<Context> context_;
  const EGLDisplay display_;
  const android::HardwareBufferDescriptor desc_;
  bool is_valid_ = false;
  Mutex pool_mutex_;
  std::deque<PoolEntry> pool_ IPLR_GUARDED_BY(pool_mutex_);

  std::shared_ptr<AHBTextureSourceGLES> CreateTexture() const;
};

}  // namespace impeller

#endif  // FLUTTER_IMPELLER_RENDERER_BACKEND_GLES_SWAPCHAIN_AHB_AHB_TEXTURE_POOL_GLES_H_
