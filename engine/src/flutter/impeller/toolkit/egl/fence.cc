// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "impeller/toolkit/egl/fence.h"

#include <cstring>

#include "flutter/fml/logging.h"

namespace impeller {
namespace egl {

//------------------------------------------------------------------------------
/// @brief      The EGL extension entry points used to manipulate native fence
///             syncs. Extension entry points must be resolved via
///             `eglGetProcAddress`; they are not guaranteed to be exported by
///             libEGL even when the prototypes are visible.
///
struct FenceProcs {
  PFNEGLCREATESYNCKHRPROC eglCreateSyncKHR = nullptr;
  PFNEGLDESTROYSYNCKHRPROC eglDestroySyncKHR = nullptr;
  PFNEGLCLIENTWAITSYNCKHRPROC eglClientWaitSyncKHR = nullptr;
  PFNEGLWAITSYNCKHRPROC eglWaitSyncKHR = nullptr;
  PFNEGLDUPNATIVEFENCEFDANDROIDPROC eglDupNativeFenceFDANDROID = nullptr;

  FenceProcs() {
    eglCreateSyncKHR = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
        ::eglGetProcAddress("eglCreateSyncKHR"));
    eglDestroySyncKHR = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
        ::eglGetProcAddress("eglDestroySyncKHR"));
    eglClientWaitSyncKHR = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(
        ::eglGetProcAddress("eglClientWaitSyncKHR"));
    eglWaitSyncKHR = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(
        ::eglGetProcAddress("eglWaitSyncKHR"));
    eglDupNativeFenceFDANDROID =
        reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
            ::eglGetProcAddress("eglDupNativeFenceFDANDROID"));
  }

  /// Whether the entry points required to create and export a native fence sync
  /// are available. `eglWaitSyncKHR` is checked separately in `WaitOnGPU` as it
  /// belongs to a distinct extension.
  bool IsComplete() const {
    return eglCreateSyncKHR &&        //
           eglDestroySyncKHR &&       //
           eglClientWaitSyncKHR &&    //
           eglDupNativeFenceFDANDROID;
  }
};

static const FenceProcs& GetFenceProcs() {
  // The resolved entry points do not depend on the display so they can be
  // resolved once for the lifetime of the process.
  static const FenceProcs procs;
  return procs;
}

/// Whether `name` appears as a whole token in the space-delimited EGL
/// extensions string `extensions`.
static bool HasExtension(const char* extensions, const char* name) {
  if (extensions == nullptr || name == nullptr) {
    return false;
  }
  const size_t name_length = std::strlen(name);
  const char* cursor = extensions;
  while ((cursor = std::strstr(cursor, name)) != nullptr) {
    // Ensure the match is preceded by the start of the string or a space.
    const bool starts_token = (cursor == extensions) || (cursor[-1] == ' ');
    // Ensure the match is followed by the end of the string or a space.
    const char terminator = cursor[name_length];
    const bool ends_token = (terminator == ' ') || (terminator == '\0');
    if (starts_token && ends_token) {
      return true;
    }
    cursor += name_length;
  }
  return false;
}

bool Fence::IsAvailableOnDisplay(EGLDisplay display) {
  if (display == EGL_NO_DISPLAY) {
    return false;
  }
  if (!GetFenceProcs().IsComplete()) {
    return false;
  }
  const char* extensions = ::eglQueryString(display, EGL_EXTENSIONS);
  return HasExtension(extensions, "EGL_KHR_fence_sync") &&
         HasExtension(extensions, "EGL_ANDROID_native_fence_sync");
}

std::unique_ptr<Fence> Fence::Create(EGLDisplay display) {
  if (display == EGL_NO_DISPLAY) {
    return nullptr;
  }
  const auto& procs = GetFenceProcs();
  if (!procs.IsComplete()) {
    return nullptr;
  }
  EGLSyncKHR sync = procs.eglCreateSyncKHR(
      display, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
  if (sync == EGL_NO_SYNC_KHR) {
    IMPELLER_LOG_EGL_ERROR;
    return nullptr;
  }
  return std::unique_ptr<Fence>(new Fence(display, sync));
}

std::unique_ptr<Fence> Fence::CreateFromFD(EGLDisplay display,
                                           fml::UniqueFD fd) {
  if (display == EGL_NO_DISPLAY || !fd.is_valid()) {
    return nullptr;
  }
  const auto& procs = GetFenceProcs();
  if (!procs.IsComplete()) {
    return nullptr;
  }
  const EGLint attributes[] = {
      EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd.get(),  //
      EGL_NONE,                                     //
  };
  EGLSyncKHR sync = procs.eglCreateSyncKHR(
      display, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
  if (sync == EGL_NO_SYNC_KHR) {
    IMPELLER_LOG_EGL_ERROR;
    return nullptr;
  }
  // On success, ownership of the file descriptor is transferred to EGL. It must
  // not be touched by the application after this point.
  [[maybe_unused]] int released = fd.release();
  return std::unique_ptr<Fence>(new Fence(display, sync));
}

Fence::Fence(EGLDisplay display, EGLSyncKHR sync)
    : display_(display), sync_(sync) {}

Fence::~Fence() {
  if (sync_ != EGL_NO_SYNC_KHR) {
    GetFenceProcs().eglDestroySyncKHR(display_, sync_);
  }
}

bool Fence::IsValid() const {
  return sync_ != EGL_NO_SYNC_KHR;
}

fml::UniqueFD Fence::CreateSyncFD() const {
  if (!IsValid()) {
    return {};
  }
  EGLint fd = GetFenceProcs().eglDupNativeFenceFDANDROID(display_, sync_);
  if (fd == EGL_NO_NATIVE_FENCE_FD_ANDROID) {
    IMPELLER_LOG_EGL_ERROR;
    return {};
  }
  return fml::UniqueFD(fd);
}

bool Fence::WaitOnGPU() const {
  if (!IsValid()) {
    return false;
  }
  const auto& procs = GetFenceProcs();
  if (!procs.eglWaitSyncKHR) {
    // EGL_KHR_wait_sync (which provides the server-side wait) is not present.
    return false;
  }
  if (procs.eglWaitSyncKHR(display_, sync_, 0) != EGL_TRUE) {
    IMPELLER_LOG_EGL_ERROR;
    return false;
  }
  return true;
}

bool Fence::WaitOnCPU(uint64_t timeout_ns) const {
  if (!IsValid()) {
    return false;
  }
  EGLint result = GetFenceProcs().eglClientWaitSyncKHR(
      display_, sync_, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR,
      static_cast<EGLTimeKHR>(timeout_ns));
  if (result == EGL_FALSE) {
    IMPELLER_LOG_EGL_ERROR;
    return false;
  }
  return result == EGL_CONDITION_SATISFIED_KHR;
}

}  // namespace egl
}  // namespace impeller
