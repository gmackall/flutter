// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <memory>
#include "flutter/testing/testing.h"  // IWYU pragma: keep
#include "fml/synchronization/count_down_latch.h"
#include "gtest/gtest.h"
#include "impeller/renderer/backend/vulkan/context_vk.h"
#include "impeller/renderer/backend/vulkan/gpu_tracer_vk.h"
#include "impeller/renderer/backend/vulkan/test/mock_vulkan.h"

namespace impeller {
namespace testing {

TEST(GPUTracerVK, CanBeDisabled) {
  auto const context =
      MockVulkanContextBuilder()
          .SetSettingsCallback([](ContextVK::Settings& settings) {
            settings.enable_gpu_tracing = false;
          })
          .Build();
  auto tracer = context->GetGPUTracer();

  ASSERT_FALSE(tracer->IsEnabled());
  EXPECT_EQ(context->GetLastFrameGpuTimeNs(), 0);
}

TEST(GPUTracerVK, DisabledFrameCycle) {
  auto const context =
      MockVulkanContextBuilder()
          .SetSettingsCallback([](ContextVK::Settings& settings) {
            settings.enable_gpu_tracing = false;
          })
          .Build();
  auto tracer = context->GetGPUTracer();

  // Check that a repeated frame start/end cycle does not fail any assertions.
  for (int i = 0; i < 2; i++) {
    tracer->MarkFrameStart();
    tracer->MarkFrameEnd();
  }
}

TEST(GPUTracerVK, CanTraceCmdBuffer) {
  auto const context =
      MockVulkanContextBuilder()
          .SetSettingsCallback([](ContextVK::Settings& settings) {
            settings.enable_gpu_tracing = true;
          })
          .Build();
  auto tracer = context->GetGPUTracer();

  ASSERT_TRUE(tracer->IsEnabled());
  tracer->MarkFrameStart();

  auto cmd_buffer = context->CreateCommandBuffer();
  auto blit_pass = cmd_buffer->CreateBlitPass();
  blit_pass->EncodeCommands();

  auto latch = std::make_shared<fml::CountDownLatch>(1u);

  if (!context->GetCommandQueue()
           ->Submit(
               {cmd_buffer},
               [latch](CommandBuffer::Status status) { latch->CountDown(); })
           .ok()) {
    GTEST_FAIL() << "Failed to submit cmd buffer";
  }

  tracer->MarkFrameEnd();
  latch->Wait();

  auto called = GetMockVulkanFunctions(context->GetDevice());
  ASSERT_NE(called, nullptr);
  ASSERT_TRUE(std::find(called->begin(), called->end(), "vkCreateQueryPool") !=
              called->end());
  ASSERT_TRUE(std::find(called->begin(), called->end(),
                        "vkGetQueryPoolResults") != called->end());
  EXPECT_GT(tracer->GetLastFrameGpuTimeNs(), 0);
  EXPECT_EQ(context->GetLastFrameGpuTimeNs(), tracer->GetLastFrameGpuTimeNs());
}

TEST(GPUTracerVK, DoesNotTraceOutsideOfFrameWorkload) {
  auto const context =
      MockVulkanContextBuilder()
          .SetSettingsCallback([](ContextVK::Settings& settings) {
            settings.enable_gpu_tracing = true;
          })
          .Build();
  auto tracer = context->GetGPUTracer();

  ASSERT_TRUE(tracer->IsEnabled());

  auto cmd_buffer = context->CreateCommandBuffer();
  auto blit_pass = cmd_buffer->CreateBlitPass();
  blit_pass->EncodeCommands();

  auto latch = std::make_shared<fml::CountDownLatch>(1u);
  if (!context->GetCommandQueue()
           ->Submit(
               {cmd_buffer},
               [latch](CommandBuffer::Status status) { latch->CountDown(); })
           .ok()) {
    GTEST_FAIL() << "Failed to submit cmd buffer";
  }

  latch->Wait();

  auto called = GetMockVulkanFunctions(context->GetDevice());

  ASSERT_NE(called, nullptr);
  ASSERT_TRUE(std::find(called->begin(), called->end(),
                        "vkGetQueryPoolResults") == called->end());
  EXPECT_EQ(context->GetLastFrameGpuTimeNs(), 0);
}

// This cmd buffer starts when there is a frame but finishes when there is none
// (e.g., when MarkFrameEnd is called before the final swapchain command buffer
// is ended and submitted). This should result in the same recorded work.
TEST(GPUTracerVK, TracesWithPartialFrameOverlap) {
  auto const context =
      MockVulkanContextBuilder()
          .SetSettingsCallback([](ContextVK::Settings& settings) {
            settings.enable_gpu_tracing = true;
          })
          .Build();
  auto tracer = context->GetGPUTracer();

  ASSERT_TRUE(tracer->IsEnabled());
  tracer->MarkFrameStart();

  auto cmd_buffer = context->CreateCommandBuffer();
  auto blit_pass = cmd_buffer->CreateBlitPass();
  blit_pass->EncodeCommands();

  tracer->MarkFrameEnd();

  auto latch = std::make_shared<fml::CountDownLatch>(1u);
  if (!context->GetCommandQueue()
           ->Submit(
               {cmd_buffer},
               [latch](CommandBuffer::Status status) { latch->CountDown(); })
           .ok()) {
    GTEST_FAIL() << "Failed to submit cmd buffer";
  }

  latch->Wait();

  auto called = GetMockVulkanFunctions(context->GetDevice());
  ASSERT_NE(called, nullptr);
  ASSERT_TRUE(std::find(called->begin(), called->end(), "vkCreateQueryPool") !=
              called->end());
  ASSERT_TRUE(std::find(called->begin(), called->end(),
                        "vkGetQueryPoolResults") != called->end());
  EXPECT_GT(context->GetLastFrameGpuTimeNs(), 0);
}

TEST(GPUTracerVK, TracesNestedFrameWorkload) {
  auto const context =
      MockVulkanContextBuilder()
          .SetSettingsCallback([](ContextVK::Settings& settings) {
            settings.enable_gpu_tracing = true;
          })
          .Build();
  auto tracer = context->GetGPUTracer();
  ASSERT_TRUE(tracer->IsEnabled());

  // Outer swapchain AcquireNextDrawable -> MarkFrameStart, then inner overlay
  // swapchain AcquireNextDrawable -> MarkFrameStart, then inner overlay
  // command buffer submitted and MarkFrameEnd, and only AFTER that is the
  // outer swapchain's command buffer created and submitted.
  tracer->MarkFrameStart();
  tracer->MarkFrameStart();

  auto overlay_cmd_buffer = context->CreateCommandBuffer();
  auto overlay_blit_pass = overlay_cmd_buffer->CreateBlitPass();
  overlay_blit_pass->EncodeCommands();
  auto overlay_latch = std::make_shared<fml::CountDownLatch>(1u);
  if (!context->GetCommandQueue()
           ->Submit({overlay_cmd_buffer},
                    [overlay_latch](CommandBuffer::Status status) {
                      overlay_latch->CountDown();
                    })
           .ok()) {
    GTEST_FAIL() << "Failed to submit overlay cmd buffer";
  }
  tracer->MarkFrameEnd();

  auto cmd_buffer = context->CreateCommandBuffer();
  auto blit_pass = cmd_buffer->CreateBlitPass();
  blit_pass->EncodeCommands();

  tracer->MarkFrameEnd();

  auto latch = std::make_shared<fml::CountDownLatch>(1u);
  if (!context->GetCommandQueue()
           ->Submit(
               {cmd_buffer},
               [latch](CommandBuffer::Status status) { latch->CountDown(); })
           .ok()) {
    GTEST_FAIL() << "Failed to submit cmd buffer";
  }

  overlay_latch->Wait();
  latch->Wait();

  auto called = GetMockVulkanFunctions(context->GetDevice());
  ASSERT_NE(called, nullptr);
  ASSERT_TRUE(std::find(called->begin(), called->end(),
                        "vkGetQueryPoolResults") != called->end());
  // Mock query results are [1000, 2000, 3000, 4000]; summing per-cmd-buffer
  // intervals [1000, 2000] and [3000, 4000] excludes the [2000, 3000] gap.
  EXPECT_EQ(context->GetLastFrameGpuTimeNs(), 2000);
}

TEST(GPUTracerVK, RecordsFenceWaitTimeNs) {
  auto const context = MockVulkanContextBuilder().Build();
  auto tracer = context->GetGPUTracer();
  EXPECT_EQ(context->GetLastFrameFenceWaitTimeNs(), 0);

  // Frame 1: primary swapchain and overlay swapchain fence waits accumulate.
  context->RecordFenceWaitTimeNs(1000000);
  tracer->MarkFrameStart();
  context->RecordFenceWaitTimeNs(500000);
  tracer->MarkFrameStart();
  EXPECT_EQ(context->GetLastFrameFenceWaitTimeNs(), 1500000);
  tracer->MarkFrameEnd();
  tracer->MarkFrameEnd();
  EXPECT_EQ(context->GetLastFrameFenceWaitTimeNs(), 1500000);

  // Frame 2: next frame's first fence wait replaces the previous frame's total.
  context->RecordFenceWaitTimeNs(250000);
  EXPECT_EQ(context->GetLastFrameFenceWaitTimeNs(), 250000);
}

TEST(GPUTracerVK, SurvivesRetainedCmdBufferAcrossRingWrapAround) {
  auto const context =
      MockVulkanContextBuilder()
          .SetSettingsCallback([](ContextVK::Settings& settings) {
            settings.enable_gpu_tracing = true;
          })
          .Build();
  auto tracer = context->GetGPUTracer();
  ASSERT_TRUE(tracer->IsEnabled());
  const uint64_t initial_gen = context->GetLastFrameGpuTimeGeneration();

  // Frame 0: create a command buffer whose GPUProbe is retained (simulating an
  // idle overlay swapchain's final_cmd_buffer) without completing its fence.
  tracer->MarkFrameStart();
  auto retained_cmd_buffer = context->CreateCommandBuffer();
  tracer->MarkFrameEnd();

  // Advance through 20 frames (> kTraceStatesSize = 16) so the ring buffer
  // wraps past slot 0.
  for (int i = 0; i < 20; ++i) {
    tracer->MarkFrameStart();
    auto cmd_buffer = context->CreateCommandBuffer();
    auto blit_pass = cmd_buffer->CreateBlitPass();
    blit_pass->EncodeCommands();
    auto latch = std::make_shared<fml::CountDownLatch>(1u);
    ASSERT_TRUE(context->GetCommandQueue()
                    ->Submit({cmd_buffer},
                             [latch](CommandBuffer::Status status) {
                               latch->CountDown();
                             })
                    .ok());
    tracer->MarkFrameEnd();
    latch->Wait();
  }

  // Releasing the stale command buffer after the ring has wrapped must not
  // underflow pending_buffers or assert.
  retained_cmd_buffer.reset();

  EXPECT_GT(context->GetLastFrameGpuTimeNs(), 0);
  EXPECT_GT(context->GetLastFrameGpuTimeGeneration(), initial_gen);
}

}  // namespace testing
}  // namespace impeller
