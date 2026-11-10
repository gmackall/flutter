// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture_gl.h"

#include <android/hardware_buffer_jni.h>

#include <cstring>
#include <utility>

#include "flutter/common/graphics/texture.h"
#include "flutter/fml/closure.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/impeller/toolkit/egl/image.h"
#include "flutter/impeller/toolkit/gles/texture.h"

namespace flutter {

ImageExternalTextureGL::ImageExternalTextureGL(
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageExternalTexture::ImageLifecycle lifecycle,
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge,
    std::shared_ptr<IncrementalRetireQueue> retire_queue)
    : ImageExternalTexture(id,
                           image_texture_entry,
                           jni_facade,
                           lifecycle,
                           std::move(ndk_bridge),
                           std::move(retire_queue)) {}

void ImageExternalTextureGL::Attach(PaintContext& context) {
  if (state_ == AttachmentState::kUninitialized) {
    state_ = AttachmentState::kAttached;
  }
}

void ImageExternalTextureGL::Detach() {}

bool ImageExternalTextureGL::WaitAcquireFence(fml::UniqueFD& fence_fd) {
  if (!fence_fd.is_valid()) {
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
    if (create_sync != nullptr && wait_sync != nullptr &&
        destroy_sync != nullptr) {
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
        const int raw_fd = fence_fd.release();
        const EGLint attribs[] = {
            EGL_SYNC_NATIVE_FENCE_FD_ANDROID,
            raw_fd,
            EGL_NONE,
        };
        EGLSyncKHR sync =
            create_sync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
        if (sync != EGL_NO_SYNC_KHR) {
          const EGLint wait_result = wait_sync(display, sync, 0);
          destroy_sync(display, sync);
          return wait_result == EGL_TRUE;
        }
        fence_fd.reset(raw_fd);
      }
    }
  }
  return WaitAcquireFenceFd(fence_fd);
}

void ImageExternalTextureGL::ProcessFrame(PaintContext& context,
                                          const SkRect& /*bounds*/) {
  DrainPendingEvictions();

  AcquiredFrame frame = AcquireLatestFrame();
  if (frame.ahardware_buffer == nullptr) {
    return;
  }
  fml::ScopedCleanupClosure close_java_hb([this, &frame]() {
    if (!frame.java_hardware_buffer.is_null()) {
      CloseHardwareBuffer(frame.java_hardware_buffer);
    }
  });

  if (!WaitAcquireFence(frame.acquire_fence_fd)) {
    return;
  }

  auto hb_desc =
      impeller::android::HardwareBuffer::Describe(frame.ahardware_buffer);
  if (!hb_desc.has_value() || hb_desc->width == 0 || hb_desc->height == 0) {
    return;
  }

  bool needs_ahb_retain = false;
  const ImageCacheKey key = MakeCacheKey(
      frame.reader_generation, frame.ahardware_buffer, &needs_ahb_retain);
  const bool is_protected = IsProtectedContent(hb_desc.value());

  if (!is_protected) {
    if (CacheEntry* hit = FindCachedEntry(key, hb_desc.value())) {
      if (hit->on_new_frame_hit) {
        fml::UniqueFD empty_fd;
        if (!hit->on_new_frame_hit(empty_fd)) {
          return;
        }
      }
      SetCurrentDlImage(hit->dl_image, hit->ahb_ref);
      return;
    }
    if (IsFailedImportKey(key)) {
      return;
    }
  }

  auto egl_image = CreateEGLImage(frame.ahardware_buffer);
  if (!egl_image.is_valid()) {
    if (!is_protected && eglGetCurrentDisplay() != EGL_NO_DISPLAY) {
      RecordFailedImport(key);
    }
    return;
  }

  GlImageImport created =
      CreateDlImage(context, hb_desc.value(), std::move(egl_image));
  if (!created.dl_image) {
    if (!is_protected) {
      RecordFailedImport(key);
    }
    return;
  }

  std::shared_ptr<ScopedAHardwareBufferRef> ahb_ref;
  if (needs_ahb_retain) {
    ahb_ref = frame.caller_ahb_ref
                  ? frame.caller_ahb_ref
                  : std::make_shared<ScopedAHardwareBufferRef>(
                        frame.ahardware_buffer, /*acquire=*/true);
  }

  SetCurrentDlImage(created.dl_image, ahb_ref);
  if (!is_protected) {
    std::function<bool(fml::UniqueFD&)> hit_rebind;
    if (created.rebind_callback) {
      auto cb = std::move(created.rebind_callback);
      hit_rebind = [cb = std::move(cb)](fml::UniqueFD&) {
        cb();
        return true;
      };
    }
    InsertCacheEntry(key, hb_desc.value(), std::move(created.dl_image),
                     std::move(ahb_ref), std::move(hit_rebind));
  }
}

impeller::UniqueEGLImageKHR ImageExternalTextureGL::CreateEGLImage(
    AHardwareBuffer* hardware_buffer) {
  if (hardware_buffer == nullptr) {
    return impeller::UniqueEGLImageKHR();
  }

  EGLDisplay display = eglGetCurrentDisplay();
  if (display == EGL_NO_DISPLAY) {
    return impeller::UniqueEGLImageKHR();
  }

  const auto& proc_table = impeller::android::GetProcTable();
  if (!proc_table.eglGetNativeClientBufferANDROID.IsAvailable()) {
    return impeller::UniqueEGLImageKHR();
  }

  EGLClientBuffer client_buffer =
      proc_table.eglGetNativeClientBufferANDROID(hardware_buffer);
  FML_DCHECK(client_buffer != nullptr);
  if (client_buffer == nullptr) {
    FML_LOG(ERROR) << "eglGetNativeClientBufferANDROID returned null.";
    return impeller::UniqueEGLImageKHR();
  }

  const EGLint attribs[] = {
      EGL_IMAGE_PRESERVED_KHR,
      EGL_TRUE,
      EGL_NONE,
  };
  EGLImageKHR image =
      eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                        client_buffer, attribs);
  if (image == EGL_NO_IMAGE_KHR) {
    return impeller::UniqueEGLImageKHR();
  }

  return impeller::UniqueEGLImageKHR(
      impeller::EGLImageKHRWithDisplay{image, display});
}

}  // namespace flutter
