// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "gtest/gtest.h"

#include <unistd.h>
#include <utility>
#include <vector>

#include "impeller/renderer/backend/vulkan/android/android_test_utils.h"
#include "impeller/renderer/backend/vulkan/swapchain/ahb/ahb_swapchain_vk.h"
#include "impeller/renderer/backend/vulkan/test/mock_vulkan.h"
#include "impeller/renderer/backend/vulkan/texture_vk.h"
#include "impeller/renderer/render_pass.h"
#include "impeller/toolkit/android/proc_table.h"
#include "impeller/toolkit/android/surface_control.h"

namespace impeller::android::testing {

using impeller::testing::GetMockVulkanFunctions;
using impeller::testing::MockVulkanContextBuilder;

namespace {

struct ScopedMockSurfaceTransactionProcs {
  static inline std::vector<std::pair<void*, ASurfaceTransaction_OnComplete>>*
      pending_callbacks = nullptr;

  decltype(ASurfaceTransaction_create)* real_create = nullptr;
  decltype(ASurfaceTransaction_delete)* real_delete = nullptr;
  decltype(ASurfaceTransaction_apply)* real_apply = nullptr;
  decltype(ASurfaceTransaction_setBuffer)* real_set_buffer = nullptr;
  decltype(ASurfaceTransaction_setOnComplete)* real_set_on_complete = nullptr;
  decltype(ASurfaceTransactionStats_getPreviousReleaseFenceFd)*
      real_get_prev_fence = nullptr;

  std::vector<std::pair<void*, ASurfaceTransaction_OnComplete>> callbacks;

  ScopedMockSurfaceTransactionProcs() {
    auto& table = GetMutableProcTable();
    real_create = table.ASurfaceTransaction_create.proc;
    real_delete = table.ASurfaceTransaction_delete.proc;
    real_apply = table.ASurfaceTransaction_apply.proc;
    real_set_buffer = table.ASurfaceTransaction_setBuffer.proc;
    real_set_on_complete = table.ASurfaceTransaction_setOnComplete.proc;
    real_get_prev_fence =
        table.ASurfaceTransactionStats_getPreviousReleaseFenceFd.proc;

    pending_callbacks = &callbacks;

    table.ASurfaceTransaction_create.proc = []() -> ASurfaceTransaction* {
      return reinterpret_cast<ASurfaceTransaction*>(0x1);
    };
    table.ASurfaceTransaction_delete.proc = [](ASurfaceTransaction*) {};
    table.ASurfaceTransaction_apply.proc = [](ASurfaceTransaction*) {};
    table.ASurfaceTransaction_setBuffer.proc =
        [](ASurfaceTransaction*, ASurfaceControl*, AHardwareBuffer*,
           int acquire_fence_fd) {
          if (acquire_fence_fd >= 0) {
            ::close(acquire_fence_fd);
          }
        };
    table.ASurfaceTransaction_setOnComplete.proc =
        [](ASurfaceTransaction*, void* context,
           ASurfaceTransaction_OnComplete func) {
          pending_callbacks->emplace_back(context, func);
        };
    table.ASurfaceTransactionStats_getPreviousReleaseFenceFd.proc =
        [](ASurfaceTransactionStats*, ASurfaceControl*) -> int { return -1; };
  }

  ~ScopedMockSurfaceTransactionProcs() {
    pending_callbacks = nullptr;
    auto& table = GetMutableProcTable();
    table.ASurfaceTransaction_create.proc = real_create;
    table.ASurfaceTransaction_delete.proc = real_delete;
    table.ASurfaceTransaction_apply.proc = real_apply;
    table.ASurfaceTransaction_setBuffer.proc = real_set_buffer;
    table.ASurfaceTransaction_setOnComplete.proc = real_set_on_complete;
    table.ASurfaceTransactionStats_getPreviousReleaseFenceFd.proc =
        real_get_prev_fence;
  }
};

}  // namespace

class FakeSurfaceControl : public SurfaceControl {
 public:
  bool IsValid() const override { return true; }

  ASurfaceControl* GetHandle() const override { return nullptr; }

  bool RemoveFromParent() const override { return true; }
};

TEST(AndroidAHBSwapchainTest,
     AHBSwapchainNoFenceWaitAfterAcquireNextImageFailure) {
  bool wait_for_fences_called = false;
  auto const context =
      MockVulkanContextBuilder()
          .SetDeviceExtensions(kAndroidDeviceExtensions)
          .SetAcquireNextImageCallback(
              [](VkDevice, VkSwapchainKHR, uint64_t, VkSemaphore, VkFence,
                 uint32_t*) -> VkResult { return VK_ERROR_SURFACE_LOST_KHR; })
          .SetWaitForFencesCallback([&](VkDevice, uint32_t, const VkFence*,
                                        VkBool32, uint64_t) -> VkResult {
            wait_for_fences_called = true;
            return VK_SUCCESS;
          })
          .Build();

  auto ahb_swapchain = std::shared_ptr<AHBSwapchainVK>(new AHBSwapchainVK(
      context, std::make_shared<FakeSurfaceControl>(), {}, {100, 100}, false));

  // Acquire a drawable but do not present it.
  auto image = ahb_swapchain->AcquireNextDrawable();
  EXPECT_TRUE(image);

  // Call AcquireNextDrawable again.  vkWaitForFences should not be called
  // because no command was submitted and no fences are pending.
  ahb_swapchain->AcquireNextDrawable();
  EXPECT_FALSE(wait_for_fences_called);
}

TEST(AndroidAHBSwapchainTest, AHBSwapchainDtorCallsWaitIdle) {
  const auto context = MockVulkanContextBuilder()
                           .SetDeviceExtensions(kAndroidDeviceExtensions)
                           .Build();

  auto ahb_swapchain = std::shared_ptr<AHBSwapchainVK>(new AHBSwapchainVK(
      context, std::make_shared<FakeSurfaceControl>(), {}, {100, 100}, false));

  ahb_swapchain.reset();

  auto called_functions = GetMockVulkanFunctions(context->GetDevice());
  EXPECT_NE(std::find(called_functions->begin(), called_functions->end(),
                      "vkDeviceWaitIdle"),
            called_functions->end());
}

TEST(AndroidAHBSwapchainTest,
     LateTransactionCompletionAfterSwapchainAndContextTeardown) {
  ScopedMockSurfaceTransactionProcs mock_tx;

  auto context = MockVulkanContextBuilder()
                     .SetDeviceExtensions(kAndroidDeviceExtensions)
                     .Build();
  ASSERT_TRUE(context);

  auto ahb_swapchain = std::shared_ptr<AHBSwapchainVK>(new AHBSwapchainVK(
      context, std::make_shared<FakeSurfaceControl>(), {}, {64, 64},
      /*enable_msaa=*/false));
  ASSERT_TRUE(ahb_swapchain->IsValid());

  auto drawable = ahb_swapchain->AcquireNextDrawable();
  ASSERT_TRUE(drawable);

  auto command_buffer = context->CreateCommandBuffer();
  ASSERT_TRUE(command_buffer);
  auto render_pass =
      command_buffer->CreateRenderPass(drawable->GetRenderTarget());
  ASSERT_TRUE(render_pass);
  ASSERT_TRUE(render_pass->EncodeCommands());
  render_pass.reset();
  ahb_swapchain->AddFinalCommandBuffer(std::move(command_buffer));

  ASSERT_TRUE(drawable->Present());
  drawable.reset();
  ASSERT_EQ(mock_tx.callbacks.size(), 1u);

  // Tearing down the swapchain while the transaction is still in flight must
  // wait for idle and destroy the swapchain's Vulkan image, view, memory,
  // framebuffer, and render pass while the VkDevice is still alive.
  ahb_swapchain.reset();
  auto called = GetMockVulkanFunctions(context->GetDevice());
  for (const char* expected :
       {"vkDestroyRenderPass", "vkDestroyFramebuffer", "vkDestroyImageView",
        "vkDestroyImage", "vkFreeMemory"}) {
    EXPECT_NE(std::find(called->begin(), called->end(), expected),
              called->end())
        << "Expected " << expected << " before vkDestroyDevice";
  }

  // Destroy the Vulkan context and VkDevice before SurfaceFlinger delivers the
  // transaction completion callback on a binder thread.
  context->Shutdown();
  context.reset();

  // Delivering the late completion callback must not touch the destroyed
  // VkDevice.
  auto [cb_ctx, cb_fn] = mock_tx.callbacks[0];
  cb_fn(cb_ctx, nullptr);
}

TEST(AndroidAHBSwapchainTest,
     OutOfOrderTransactionCompletionsRecycleBothTextures) {
  ScopedMockSurfaceTransactionProcs mock_tx;

  auto context = MockVulkanContextBuilder()
                     .SetDeviceExtensions(kAndroidDeviceExtensions)
                     .Build();
  ASSERT_TRUE(context);

  auto ahb_swapchain = std::shared_ptr<AHBSwapchainVK>(new AHBSwapchainVK(
      context, std::make_shared<FakeSurfaceControl>(), {}, {64, 64},
      /*enable_msaa=*/false));
  ASSERT_TRUE(ahb_swapchain->IsValid());

  auto present_one = [&](vk::ImageView* out_view) {
    auto drawable = ahb_swapchain->AcquireNextDrawable();
    ASSERT_TRUE(drawable);
    auto tex = drawable->GetRenderTarget().GetRenderTargetTexture();
    *out_view = TextureVK::Cast(*tex).GetImageView();
    auto command_buffer = context->CreateCommandBuffer();
    ASSERT_TRUE(command_buffer);
    auto render_pass =
        command_buffer->CreateRenderPass(drawable->GetRenderTarget());
    ASSERT_TRUE(render_pass);
    ASSERT_TRUE(render_pass->EncodeCommands());
    render_pass.reset();
    ahb_swapchain->AddFinalCommandBuffer(std::move(command_buffer));
    ASSERT_TRUE(drawable->Present());
  };

  vk::ImageView view1 = {};
  vk::ImageView view2 = {};
  present_one(&view1);
  present_one(&view2);
  EXPECT_NE(view1, view2);
  ASSERT_EQ(mock_tx.callbacks.size(), 2u);

  // Fire the newer transaction's completion callback before the older,
  // superseded one (matching SurfaceComposerClient's unordered callback set).
  mock_tx.callbacks[1].second(mock_tx.callbacks[1].first, nullptr);
  mock_tx.callbacks[0].second(mock_tx.callbacks[0].first, nullptr);
  mock_tx.callbacks.clear();

  // `view1` (superseded) should have been recycled into the pool while `view2`
  // remains `currently_displayed_texture_`.
  auto recycled = ahb_swapchain->AcquireNextDrawable();
  ASSERT_TRUE(recycled);
  EXPECT_EQ(
      TextureVK::Cast(*recycled->GetRenderTarget().GetRenderTargetTexture())
          .GetImageView(),
      view1);
}

}  // namespace impeller::android::testing
