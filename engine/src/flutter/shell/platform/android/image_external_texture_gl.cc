// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_gl.h"

#include <android/hardware_buffer_jni.h>
#include <android/sensor.h>
#include <errno.h>
#include <poll.h>

#include <cstring>

#include "flutter/common/graphics/texture.h"
#include "flutter/fml/closure.h"
#include "flutter/impeller/core/formats.h"
#include "flutter/impeller/display_list/dl_image_impeller.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/impeller/toolkit/egl/image.h"
#include "flutter/impeller/toolkit/gles/texture.h"
#include "third_party/skia/include/core/SkAlphaType.h"
#include "third_party/skia/include/core/SkColorType.h"
#include "third_party/skia/include/gpu/ganesh/SkImageGanesh.h"
#include "third_party/skia/include/gpu/ganesh/gl/GrGLBackendSurface.h"
#include "third_party/skia/include/gpu/ganesh/gl/GrGLTypes.h"

namespace flutter {

ImageExternalTextureGL::ImageExternalTextureGL(
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageExternalTexture::ImageLifecycle lifecycle)
    : ImageExternalTexture(id, image_texture_entry, jni_facade, lifecycle) {}

void ImageExternalTextureGL::Attach(PaintContext& context) {
  if (state_ == AttachmentState::kUninitialized) {
    // TODO(johnmccurtchan): We currently display the first frame after an
    // attach-detach cycle as blank. There seems to be an issue on some
    // devices where ImageReaders/Images from before the detach aren't
    // valid after the attach. According to Android folks this doesn't
    // match the spec. Revisit this in the future.
    // See https://github.com/flutter/flutter/issues/142978 and
    // https://github.com/flutter/flutter/issues/139039.
    state_ = AttachmentState::kAttached;
  }
}

void ImageExternalTextureGL::UpdateImage(
    AHardwareBuffer* latest_hardware_buffer,
    const SkRect& bounds,
    PaintContext& context) {
  if (latest_hardware_buffer == nullptr) {
    return;
  }
  std::optional<HardwareBufferKey> key =
      impeller::android::HardwareBuffer::GetSystemUniqueID(
          latest_hardware_buffer);
  auto evict_cb = [this](HardwareBufferKey evicted_key) {
    gl_entries_.erase(evicted_key);
  };
  if (auto cached_image = image_lru_.TryFindImage(key, evict_cb)) {
    dl_image_ = std::move(cached_image);
    return;
  }
  auto descriptor =
      impeller::android::HardwareBuffer::Describe(latest_hardware_buffer);
  if (!descriptor.has_value()) {
    return;
  }
  auto existing_image = image_lru_.FindImage(key, descriptor.value(), evict_cb);
  if (existing_image != nullptr) {
    dl_image_ = existing_image;
    return;
  }

  auto egl_image = CreateEGLImage(latest_hardware_buffer);
  if (!egl_image.is_valid()) {
    return;
  }

  auto image = CreateDlImage(context, bounds, key, std::move(egl_image));
  if (!image) {
    return;
  }
  dl_image_ = std::move(image);
  if (key.has_value()) {
    gl_entries_.erase(image_lru_.AddImage(dl_image_, key.value()));
  }
}

bool ImageExternalTextureGL::WaitAcquireFenceOnGL(
    fml::UniqueFD acquire_fence_fd) {
  if (!acquire_fence_fd.is_valid()) {
    return true;
  }
  EGLDisplay display = eglGetCurrentDisplay();
  if (display != EGL_NO_DISPLAY) {
    static const auto create_sync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
        eglGetProcAddress("eglCreateSyncKHR"));
    static const auto wait_sync = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(
        eglGetProcAddress("eglWaitSyncKHR"));
    static const auto destroy_sync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
        eglGetProcAddress("eglDestroySyncKHR"));
    if (create_sync && wait_sync && destroy_sync) {
      thread_local EGLDisplay t_checked_display = EGL_NO_DISPLAY;
      thread_local bool t_has_native_fence = false;
      if (t_checked_display != display) {
        t_checked_display = display;
        const char* exts = eglQueryString(display, EGL_EXTENSIONS);
        t_has_native_fence =
            exts != nullptr &&
            std::strstr(exts, "EGL_ANDROID_native_fence_sync") != nullptr &&
            std::strstr(exts, "EGL_KHR_wait_sync") != nullptr;
      }
      if (t_has_native_fence) {
        // Release Bionic fdsan ownership BEFORE eglCreateSyncKHR, because the
        // EGL driver takes ownership of (and may close/dup) the sync_file FD
        // inside the call on success. Reclaim on failure for poll() fallback.
        const int raw_fd = acquire_fence_fd.release();
        const EGLint attribs[] = {
            EGL_SYNC_NATIVE_FENCE_FD_ANDROID,
            raw_fd,
            EGL_NONE,
        };
        EGLSyncKHR sync =
            create_sync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
        if (sync != EGL_NO_SYNC_KHR) {
          wait_sync(display, sync, 0);
          destroy_sync(display, sync);
          return true;
        }
        acquire_fence_fd.reset(raw_fd);
      }
    }
  }
  return NdkSurfaceProducer::WaitAcquireFenceFdBounded(
      std::move(acquire_fence_fd));
}

void ImageExternalTextureGL::ProcessFrame(PaintContext& context,
                                          const SkRect& bounds) {
  auto acquired = AcquireLatestFrame();
  if (acquired.ahardware_buffer == nullptr) {
    return;
  }
  fml::ScopedCleanupClosure close_java_hb([this, &acquired]() {
    if (!acquired.owned_by_producer &&
        !acquired.java_hardware_buffer.is_null()) {
      CloseHardwareBuffer(acquired.java_hardware_buffer);
    }
  });
  AHardwareBuffer* latest_hardware_buffer = acquired.ahardware_buffer;
  const auto& proc_table = impeller::android::GetProcTable();
  if (!acquired.caller_owns_ahb_ref &&
      proc_table.AHardwareBuffer_acquire.IsAvailable()) {
    proc_table.AHardwareBuffer_acquire(latest_hardware_buffer);
  }
  fml::ScopedCleanupClosure release_native_hb(
      [latest_hardware_buffer, &proc_table]() {
        if (proc_table.AHardwareBuffer_release.IsAvailable()) {
          proc_table.AHardwareBuffer_release(latest_hardware_buffer);
        }
      });
  if (!WaitAcquireFenceOnGL(std::move(acquired.acquire_fence_fd))) {
    return;
  }
  UpdateImage(latest_hardware_buffer, bounds, context);
}

void ImageExternalTextureGL::Detach() {
  image_lru_.Clear();
  gl_entries_.clear();
}

impeller::UniqueEGLImageKHR ImageExternalTextureGL::CreateEGLImage(
    AHardwareBuffer* hardware_buffer) {
  if (hardware_buffer == nullptr) {
    return impeller::UniqueEGLImageKHR();
  }

  EGLDisplay display = eglGetCurrentDisplay();
  if (display == EGL_NO_DISPLAY) {
    // This could happen when running in a deferred task that executes after
    // the thread has lost its EGL state.
    return impeller::UniqueEGLImageKHR();
  }

  EGLClientBuffer client_buffer =
      impeller::android::GetProcTable().eglGetNativeClientBufferANDROID(
          hardware_buffer);
  FML_DCHECK(client_buffer != nullptr);
  if (client_buffer == nullptr) {
    FML_LOG(ERROR) << "eglGetNativeClientBufferAndroid returned null.";
    return impeller::UniqueEGLImageKHR();
  }

  impeller::EGLImageKHRWithDisplay maybe_image =
      impeller::EGLImageKHRWithDisplay{
          CreateEGLImageKHR(display, client_buffer), display};

  if (maybe_image.image == EGL_NO_IMAGE_KHR) {
    return {};
  }
  return impeller::UniqueEGLImageKHR(maybe_image);
}

EGLImageKHR ImageExternalTextureGL::CreateEGLImageKHR(EGLDisplay display,
                                                      EGLClientBuffer buffer) {
  return eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                           buffer, nullptr);
}

}  // namespace flutter
