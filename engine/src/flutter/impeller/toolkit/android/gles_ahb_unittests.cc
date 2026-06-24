// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// On-device functional tests for the OpenGL ES AHardwareBuffer swapchain
// building blocks: the EGL native fence sync wrapper (`egl::Fence`) and
// rendering into an AHardwareBuffer imported as a GL_TEXTURE_2D render target.
//
// These tests bootstrap a minimal headless (pbuffer) OpenGL ES context rather
// than depending on a full Impeller `ContextGLES`, so they exercise the raw
// EGL/GL building blocks directly.

#include <GLES3/gl3.h>
#define GL_GLEXT_PROTOTYPES
#include <GLES2/gl2ext.h>

#include <array>
#include <memory>

#include "flutter/testing/testing.h"
#include "gtest/gtest.h"

#include "impeller/toolkit/android/hardware_buffer.h"
#include "impeller/toolkit/android/proc_table.h"
#include "impeller/toolkit/egl/config.h"
#include "impeller/toolkit/egl/context.h"
#include "impeller/toolkit/egl/display.h"
#include "impeller/toolkit/egl/fence.h"
#include "impeller/toolkit/egl/image.h"
#include "impeller/toolkit/egl/surface.h"

namespace impeller::android::testing {

namespace {

// A minimal headless OpenGL ES context backed by a 1x1 pbuffer surface. The
// context is made current for the lifetime of the instance.
class TestGLESContext {
 public:
  TestGLESContext() {
    display_ = std::make_unique<egl::Display>();
    if (!display_->IsValid()) {
      return;
    }

    egl::ConfigDescriptor config_desc;
    config_desc.api = egl::API::kOpenGLES2;
    config_desc.samples = egl::Samples::kOne;
    config_desc.color_format = egl::ColorFormat::kRGBA8888;
    config_desc.stencil_bits = egl::StencilBits::kZero;
    config_desc.depth_bits = egl::DepthBits::kZero;
    config_desc.surface_type = egl::SurfaceType::kPBuffer;

    config_ = display_->ChooseConfig(config_desc);
    if (!config_) {
      return;
    }

    surface_ = display_->CreatePixelBufferSurface(*config_, 1u, 1u);
    context_ = display_->CreateContext(*config_, nullptr);
    if (!surface_ || !context_) {
      return;
    }

    if (!context_->MakeCurrent(*surface_)) {
      return;
    }

    is_valid_ = true;
  }

  ~TestGLESContext() {
    if (context_) {
      context_->ClearCurrent();
    }
  }

  bool IsValid() const { return is_valid_; }

  EGLDisplay GetEGLDisplay() const { return display_->GetHandle(); }

 private:
  std::unique_ptr<egl::Display> display_;
  std::unique_ptr<egl::Config> config_;
  std::unique_ptr<egl::Surface> surface_;
  std::unique_ptr<egl::Context> context_;
  bool is_valid_ = false;

  TestGLESContext(const TestGLESContext&) = delete;
  TestGLESContext& operator=(const TestGLESContext&) = delete;
};

}  // namespace

TEST(GLESAHBFenceTest, CanExportAndImportNativeFence) {
  TestGLESContext gl_context;
  if (!gl_context.IsValid()) {
    GTEST_SKIP() << "Could not create a GLES context on this platform.";
  }
  if (!egl::Fence::IsAvailableOnDisplay(gl_context.GetEGLDisplay())) {
    GTEST_SKIP() << "Native fence syncs are not available on this platform.";
  }

  // Issue some GPU work so the fence has something to be signaled by.
  glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);

  // Create a native fence sync associated with the work above.
  auto fence = egl::Fence::Create(gl_context.GetEGLDisplay());
  ASSERT_NE(fence, nullptr);
  ASSERT_TRUE(fence->IsValid());

  // The fence fd is only available after the commands have been flushed.
  glFlush();

  // Export a sync fd. This is what would be handed to the surface control as
  // the "present ready" acquire fence.
  fml::UniqueFD exported = fence->CreateSyncFD();
  ASSERT_TRUE(exported.is_valid());

  // Re-import the exported fd as a new fence. This is the "render ready" path
  // where a release fence handed back by the compositor is imported.
  auto imported =
      egl::Fence::CreateFromFD(gl_context.GetEGLDisplay(), std::move(exported));
  ASSERT_NE(imported, nullptr);
  ASSERT_TRUE(imported->IsValid());

  // Both a server-side (GPU) and client-side (CPU) wait should succeed.
  EXPECT_TRUE(imported->WaitOnGPU());
  EXPECT_TRUE(imported->WaitOnCPU());
}

TEST(GLESAHBFenceTest, CanRenderIntoHardwareBuffer) {
  TestGLESContext gl_context;
  if (!gl_context.IsValid()) {
    GTEST_SKIP() << "Could not create a GLES context on this platform.";
  }
  if (!HardwareBuffer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Hardware buffers are not available on this platform.";
  }

  // Allocate a hardware buffer suitable for use as a swapchain image.
  auto descriptor = HardwareBufferDescriptor::MakeForSwapchainImage(ISize{4, 4});
  auto hardware_buffer = std::make_unique<HardwareBuffer>(descriptor);
  ASSERT_TRUE(hardware_buffer->IsValid());

  // Import the hardware buffer as an EGLImage.
  const auto& proc_table = GetProcTable();
  ASSERT_TRUE(proc_table.eglGetNativeClientBufferANDROID.IsAvailable());
  EGLClientBuffer client_buffer =
      proc_table.eglGetNativeClientBufferANDROID(hardware_buffer->GetHandle());
  ASSERT_NE(client_buffer, nullptr);

  EGLImageKHR egl_image =
      ::eglCreateImageKHR(gl_context.GetEGLDisplay(), EGL_NO_CONTEXT,
                          EGL_NATIVE_BUFFER_ANDROID, client_buffer, nullptr);
  ASSERT_NE(egl_image, EGL_NO_IMAGE_KHR);
  UniqueEGLImageKHR unique_egl_image(
      EGLImageKHRWithDisplay{egl_image, gl_context.GetEGLDisplay()});

  // Associate the EGLImage with a GL_TEXTURE_2D.
  GLuint texture = GL_NONE;
  glGenTextures(1u, &texture);
  glBindTexture(GL_TEXTURE_2D, texture);
  glEGLImageTargetTexture2DOES(GL_TEXTURE_2D,
                               static_cast<GLeglImageOES>(egl_image));

  // Attach the texture to a framebuffer and render into it.
  GLuint fbo = GL_NONE;
  glGenFramebuffers(1u, &fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         texture, 0);
  ASSERT_EQ(glCheckFramebufferStatus(GL_FRAMEBUFFER),
            static_cast<GLenum>(GL_FRAMEBUFFER_COMPLETE));

  const auto& size = descriptor.size;
  glViewport(0, 0, size.width, size.height);
  glClearColor(1.0f, 0.0f, 0.0f, 1.0f);  // Opaque red.
  glClear(GL_COLOR_BUFFER_BIT);

  // Synchronize the render before reading back, exercising the fence as a real
  // GPU completion signal.
  if (egl::Fence::IsAvailableOnDisplay(gl_context.GetEGLDisplay())) {
    auto fence = egl::Fence::Create(gl_context.GetEGLDisplay());
    ASSERT_NE(fence, nullptr);
    glFlush();
    EXPECT_TRUE(fence->WaitOnCPU());
  } else {
    glFinish();
  }

  // Read back the rendered pixel and verify it is red.
  std::array<uint8_t, 4> pixel = {0, 0, 0, 0};
  glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel.data());
  EXPECT_EQ(pixel[0], 255u);  // R
  EXPECT_EQ(pixel[1], 0u);    // G
  EXPECT_EQ(pixel[2], 0u);    // B
  EXPECT_EQ(pixel[3], 255u);  // A

  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  glDeleteFramebuffers(1u, &fbo);
  glDeleteTextures(1u, &texture);
}

}  // namespace impeller::android::testing
