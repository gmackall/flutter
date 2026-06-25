// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_surface_gl_impeller.h"

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#include "flutter/common/graphics/gl_context_switch.h"
#include "flutter/fml/logging.h"
#include "flutter/impeller/renderer/backend/gles/swapchain/ahb/ahb_swapchain_gles.h"
#include "flutter/impeller/toolkit/android/surface_control.h"
#include "flutter/impeller/toolkit/android/surface_transaction.h"
#include "flutter/impeller/toolkit/egl/surface.h"
#include "flutter/shell/gpu/gpu_surface_gl_impeller.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"

namespace flutter {

namespace {

// On some older MediaTek devices running Android 10 or below (specifically
// MT6762/Helio P22 and MT6765/Helio P35 with PowerVR Rogue GE8320 GPUs),
// keeping the EGL context current on the raster thread while the thread is idle
// triggers a driver-level race condition/crash inside the system RenderThread's
// eglMakeCurrent call during activity transitions or platform view rendering.
// Clearing the current context at the end of every frame resolves the conflict
// and avoids the driver crashes.
bool ShouldClearContextBetweenFrames() {
#if defined(__ANDROID__)
  char sdk_value[PROP_VALUE_MAX];
  int sdk_version = 0;
  if (__system_property_get("ro.build.version.sdk", sdk_value) > 0) {
    sdk_version = atoi(sdk_value);
  }
  if (sdk_version == 0 || sdk_version > 29) {
    return false;
  }

  auto is_bad_platform = [](const char* name) -> bool {
    char value[PROP_VALUE_MAX];
    if (__system_property_get(name, value) > 0) {
      std::string_view platform(value);
      if (platform.starts_with("mt6762") || platform.starts_with("mt6765") ||
          platform.starts_with("MT6762") || platform.starts_with("MT6765")) {
        return true;
      }
    }
    return false;
  };

  return is_bad_platform("ro.board.platform") ||
         is_bad_platform("ro.vendor.mediatek.platform");
#else
  return false;
#endif
}

class AndroidSwitchableGLContextImpeller : public SwitchableGLContext {
 public:
  explicit AndroidSwitchableGLContextImpeller(
      const std::shared_ptr<AndroidContextGLImpeller>& android_context)
      : android_context_(android_context) {}

  bool SetCurrent() override { return true; }

  bool RemoveCurrent() override {
    if (auto context = android_context_.lock()) {
      return context->OnscreenContextClearCurrent();
    }
    return false;
  }

 private:
  std::weak_ptr<AndroidContextGLImpeller> android_context_;
};

}  // namespace

AndroidSurfaceGLImpeller::AndroidSurfaceGLImpeller(
    const std::shared_ptr<AndroidContextGLImpeller>& android_context)
    : android_context_(android_context) {
  offscreen_surface_ = android_context_->CreateOffscreenSurface();

  if (!offscreen_surface_) {
    FML_DLOG(ERROR) << "Could not create offscreen surface.";
    return;
  }

  // The onscreen surface will be acquired once the native window is set.

  is_valid_ = true;
}

AndroidSurfaceGLImpeller::~AndroidSurfaceGLImpeller() = default;

// |AndroidSurface|
bool AndroidSurfaceGLImpeller::IsValid() const {
  return is_valid_;
}

// |AndroidSurface|
std::unique_ptr<Surface> AndroidSurfaceGLImpeller::CreateGPUSurface(
    GrDirectContext* gr_context) {
  auto surface = std::make_unique<GPUSurfaceGLImpeller>(
      this,                                    // delegate
      android_context_->GetImpellerContext(),  // context
      true,                                    // render to surface
      this                                     // swapchain provider
  );
  if (!surface->IsValid()) {
    return nullptr;
  }
  return surface;
}

// |AndroidSurface|
void AndroidSurfaceGLImpeller::TeardownOnScreenContext() {
  GLContextClearCurrent();
  ahb_swapchain_.reset();
  surface_control_.reset();
  onscreen_surface_.reset();
}

// |AndroidSurface|
bool AndroidSurfaceGLImpeller::OnScreenSurfaceResize(const DlISize& size) {
  // The size is unused. It was added only for iOS where the sizes were
  // necessary to re-create auxiliary buffers (stencil, depth, etc.).
  return RecreateOnscreenSurfaceAndMakeOnscreenContextCurrent();
}

// |AndroidSurface|
bool AndroidSurfaceGLImpeller::ResourceContextMakeCurrent() {
  if (!offscreen_surface_) {
    return false;
  }
  return android_context_->ResourceContextMakeCurrent(offscreen_surface_.get());
}

// |AndroidSurface|
bool AndroidSurfaceGLImpeller::ResourceContextClearCurrent() {
  return android_context_->ResourceContextClearCurrent();
}

// |AndroidSurface|
bool AndroidSurfaceGLImpeller::SetNativeWindow(
    fml::RefPtr<AndroidNativeWindow> window,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade) {
  native_window_ = std::move(window);
  auto result = RecreateOnscreenSurfaceAndMakeOnscreenContextCurrent();

  if (result && native_window_ && ShouldUseSurfaceControlSwapchain()) {
    impeller::CreateTransactionCB cb = [jni_facade]() {
      FML_CHECK(jni_facade) << "JNI was nullptr";
      ASurfaceTransaction* tx = jni_facade->createTransaction();
      if (tx == nullptr) {
        return impeller::android::SurfaceTransaction();
      }
      return impeller::android::SurfaceTransaction(tx);
    };
    surface_control_ = std::shared_ptr<impeller::android::SurfaceControl>(
        impeller::android::SurfaceControl::Create(native_window_->handle(),
                                                  "ImpellerSurface"));
    const auto size = native_window_->GetSize();
    ahb_swapchain_ = std::make_unique<impeller::AHBSwapchainGLES>(
        android_context_->GetImpellerContext(),    // context
        android_context_->GetGLDisplay(),          // display
        surface_control_,                          // surface control
        cb,                                        // transaction callback
        impeller::ISize{size.width, size.height}   // size
    );
  }

  return result;
}

// |AndroidSurface|
std::unique_ptr<Surface> AndroidSurfaceGLImpeller::CreateSnapshotSurface() {
  if (!onscreen_surface_ || !onscreen_surface_->IsValid()) {
    onscreen_surface_ = android_context_->CreateOffscreenSurface();
    if (!onscreen_surface_) {
      FML_DLOG(ERROR) << "Could not create offscreen surface for snapshot.";
      return nullptr;
    }
  }
  // Make the snapshot surface current because constucting a
  // GPUSurfaceGLImpeller and its AiksContext may invoke graphics APIs.
  if (!android_context_->OnscreenContextMakeCurrent(onscreen_surface_.get())) {
    FML_DLOG(ERROR) << "Could not make snapshot surface current.";
    return nullptr;
  }
  return std::make_unique<GPUSurfaceGLImpeller>(
      this,                                    // delegate
      android_context_->GetImpellerContext(),  // context
      true                                     // render to surface
  );
}

// |AndroidSurface|
std::shared_ptr<impeller::Context>
AndroidSurfaceGLImpeller::GetImpellerContext() {
  return android_context_->GetImpellerContext();
}

// |GPUSurfaceGLDelegate|
std::unique_ptr<GLContextResult>
AndroidSurfaceGLImpeller::GLContextMakeCurrent() {
  bool success = OnGLContextMakeCurrent();
  if (!success) {
    return std::make_unique<GLContextDefaultResult>(false);
  }
  if (!should_clear_context_between_frames_.has_value()) {
    should_clear_context_between_frames_ = ShouldClearContextBetweenFrames();
  }
  if (should_clear_context_between_frames_.value()) {
    return std::make_unique<GLContextSwitch>(
        std::make_unique<AndroidSwitchableGLContextImpeller>(android_context_));
  }
  return std::make_unique<GLContextDefaultResult>(true);
}

bool AndroidSurfaceGLImpeller::OnGLContextMakeCurrent() {
  if (!onscreen_surface_) {
    return false;
  }

  return android_context_->OnscreenContextMakeCurrent(onscreen_surface_.get());
}

// |GPUSurfaceGLDelegate|
bool AndroidSurfaceGLImpeller::GLContextClearCurrent() {
  if (!onscreen_surface_) {
    return false;
  }

  return android_context_->OnscreenContextClearCurrent();
}

// |GPUSurfaceGLDelegate|
SurfaceFrame::FramebufferInfo
AndroidSurfaceGLImpeller::GLContextFramebufferInfo() const {
  auto info = SurfaceFrame::FramebufferInfo{};
  info.supports_readback = true;
  info.supports_partial_repaint = false;
  return info;
}

// |GPUSurfaceGLDelegate|
void AndroidSurfaceGLImpeller::GLContextSetDamageRegion(
    const std::optional<DlIRect>& region) {
  // Not supported.
}

// |GPUSurfaceGLDelegate|
bool AndroidSurfaceGLImpeller::GLContextPresent(
    const GLPresentInfo& present_info) {
  // The FBO ID is superfluous and was introduced for iOS where the default
  // framebuffer was not FBO0.
  if (!onscreen_surface_) {
    return false;
  }
  return onscreen_surface_->Present();
}

// |GPUSurfaceGLDelegate|
GLFBOInfo AndroidSurfaceGLImpeller::GLContextFBO(GLFrameInfo frame_info) const {
  // FBO0 is the default window bound framebuffer in EGL environments.
  return GLFBOInfo{
      .fbo_id = 0,
  };
}

// |GPUSurfaceGLDelegate|
sk_sp<const GrGLInterface> AndroidSurfaceGLImpeller::GetGLInterface() const {
  return nullptr;
}

bool AndroidSurfaceGLImpeller::
    RecreateOnscreenSurfaceAndMakeOnscreenContextCurrent() {
  GLContextClearCurrent();
  if (!native_window_) {
    return false;
  }
  onscreen_surface_.reset();
  std::unique_ptr<impeller::egl::Surface> onscreen_surface;
  if (ShouldUseSurfaceControlSwapchain()) {
    // In HCPP mode the main Flutter layer is presented through the AHB
    // swapchain on a SurfaceControl created from this ANativeWindow; the raster
    // thread renders into hardware-buffer FBOs and never presents to an EGL
    // window surface. Creating an EGL window surface on the same ANativeWindow
    // as the SurfaceControl makes them contend as buffer producers, which
    // surfaces as EGL_BAD_ACCESS on eglMakeCurrent during resize/teardown. Use
    // an offscreen pbuffer purely to give the context a current surface.
    onscreen_surface = android_context_->CreateOffscreenSurface();
  } else {
    onscreen_surface =
        android_context_->CreateOnscreenSurface(native_window_->handle());
  }
  if (!onscreen_surface) {
    FML_DLOG(ERROR) << "Could not create onscreen surface.";
    return false;
  }
  onscreen_surface_ = std::move(onscreen_surface);
  return OnGLContextMakeCurrent();
}

// |GLImpellerSurfaceProvider|
std::unique_ptr<impeller::Surface> AndroidSurfaceGLImpeller::AcquireImpellerSurface(
    const DlISize& size) {
  if (!ahb_swapchain_) {
    return nullptr;
  }
  ahb_swapchain_->UpdateSurfaceSize(impeller::ISize{size.width, size.height});
  return ahb_swapchain_->AcquireNextDrawable();
}

bool AndroidSurfaceGLImpeller::ShouldUseSurfaceControlSwapchain() const {
  return android_context_ &&
         android_context_->ShouldEnableSurfaceControlSwapchain();
}

}  // namespace flutter
