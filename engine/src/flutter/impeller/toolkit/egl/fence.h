// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_IMPELLER_TOOLKIT_EGL_FENCE_H_
#define FLUTTER_IMPELLER_TOOLKIT_EGL_FENCE_H_

#include <cstdint>
#include <memory>

#include "flutter/fml/unique_fd.h"
#include "impeller/toolkit/egl/egl.h"

namespace impeller {
namespace egl {

//------------------------------------------------------------------------------
/// @brief      A RAII wrapper around an `EGLSyncKHR` created from the
///             `EGL_ANDROID_native_fence_sync` extension.
///
///             Native fence syncs are the OpenGL ES analog of the exportable
///             and importable semaphores Vulkan uses to synchronize GPU work
///             with the Android compositor (`ASurfaceControl`). They allow:
///
///             * Exporting a sync `fd` once the GPU has finished rendering into
///               a hardware buffer, so the compositor can wait on it before
///               sampling the buffer (the "present ready" fence). See `Create`
///               followed by `CreateSyncFD`.
///
///             * Importing a release sync `fd` handed back by the compositor so
///               the GPU waits before reusing (rendering into) a recycled
///               hardware buffer (the "render ready" fence). See `CreateFromFD`
///               followed by `WaitOnGPU`.
///
/// @warning    All of the operations on this class require a context to be
///             current on the calling thread that is associated with the same
///             `EGLDisplay` the fence was created with.
///
/// @warning    This functionality is only available on Android and only when
///             the `EGL_KHR_fence_sync`, `EGL_ANDROID_native_fence_sync`, and
///             (for `WaitOnGPU`) `EGL_KHR_wait_sync` extensions are present.
///             Check `IsAvailableOnDisplay` before use and fall back if it is
///             not available.
///
class Fence {
 public:
  //----------------------------------------------------------------------------
  /// @brief      Whether the EGL extensions required to create and manipulate
  ///             native fence syncs are present on the given display.
  ///
  /// @param[in]  display  The display to query.
  ///
  /// @return     `true` if native fence syncs can be used on the display.
  ///
  static bool IsAvailableOnDisplay(EGLDisplay display);

  //----------------------------------------------------------------------------
  /// @brief      Create a native fence sync object. The fence becomes
  ///             associated with all the GPU commands issued prior to this call.
  ///
  ///             To obtain a sync `fd` for the fence, the commands must be
  ///             flushed (via `glFlush` or an equivalent) before calling
  ///             `CreateSyncFD`. The flush is intentionally left to the caller
  ///             so it can be batched with the rest of frame submission.
  ///
  /// @param[in]  display  A valid display with the required extensions.
  ///
  /// @return     A valid fence if one could be created. `nullptr` otherwise.
  ///
  static std::unique_ptr<Fence> Create(EGLDisplay display);

  //----------------------------------------------------------------------------
  /// @brief      Create a native fence sync object from a previously exported
  ///             sync `fd` (for instance, a release fence handed back by the
  ///             compositor).
  ///
  ///             On success, ownership of the file descriptor is transferred to
  ///             EGL; the descriptor will be closed when the fence is
  ///             destroyed. On failure, the descriptor remains owned by the
  ///             caller (it is closed when the passed `fd` goes out of scope).
  ///
  /// @param[in]  display  A valid display with the required extensions.
  /// @param[in]  fd       The sync file descriptor to import.
  ///
  /// @return     A valid fence if one could be created. `nullptr` otherwise.
  ///
  static std::unique_ptr<Fence> CreateFromFD(EGLDisplay display,
                                             fml::UniqueFD fd);

  ~Fence();

  Fence(const Fence&) = delete;

  Fence& operator=(const Fence&) = delete;

  //----------------------------------------------------------------------------
  /// @return     `true` if the underlying sync object is valid.
  ///
  bool IsValid() const;

  //----------------------------------------------------------------------------
  /// @brief      Duplicate the native fence `fd` backing this sync.
  ///
  /// @warning    The commands the fence was created after must have been
  ///             flushed before this call, otherwise the fence has no `fd` yet
  ///             and an invalid descriptor is returned.
  ///
  /// @return     A valid file descriptor if one could be obtained. An invalid
  ///             `fml::UniqueFD` otherwise.
  ///
  fml::UniqueFD CreateSyncFD() const;

  //----------------------------------------------------------------------------
  /// @brief      Instruct the GPU to wait for this fence to be signaled before
  ///             executing subsequent commands on the current context. This
  ///             does not block the calling (CPU) thread.
  ///
  ///             Requires the `EGL_KHR_wait_sync` extension.
  ///
  /// @return     `true` if the wait was successfully enqueued.
  ///
  bool WaitOnGPU() const;

  //----------------------------------------------------------------------------
  /// @brief      Block the calling (CPU) thread until this fence is signaled or
  ///             the timeout elapses.
  ///
  /// @param[in]  timeout_ns  The maximum time to wait in nanoseconds. Defaults
  ///                         to waiting indefinitely.
  ///
  /// @return     `true` if the fence was signaled before the timeout.
  ///
  bool WaitOnCPU(uint64_t timeout_ns = EGL_FOREVER_KHR) const;

 private:
  EGLDisplay display_ = EGL_NO_DISPLAY;
  EGLSyncKHR sync_ = EGL_NO_SYNC_KHR;

  Fence(EGLDisplay display, EGLSyncKHR sync);
};

}  // namespace egl
}  // namespace impeller

#endif  // FLUTTER_IMPELLER_TOOLKIT_EGL_FENCE_H_
