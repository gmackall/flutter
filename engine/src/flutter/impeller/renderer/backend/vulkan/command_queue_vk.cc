// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "third_party/abseil-cpp/absl/status/status.h"

#include "impeller/renderer/backend/vulkan/command_queue_vk.h"

#include "impeller/base/validation.h"
#include "impeller/renderer/backend/vulkan/command_buffer_vk.h"
#include "impeller/renderer/backend/vulkan/context_vk.h"
#include "impeller/renderer/backend/vulkan/fence_waiter_vk.h"
#include "impeller/renderer/backend/vulkan/tracked_objects_vk.h"
#include "impeller/renderer/command_buffer.h"

namespace impeller {

CommandQueueVK::CommandQueueVK(const std::weak_ptr<ContextVK>& context)
    : context_(context) {}

CommandQueueVK::~CommandQueueVK() = default;

absl::Status CommandQueueVK::Submit(
    const std::vector<std::shared_ptr<CommandBuffer>>& buffers,
    const CompletionCallback& completion_callback) {
  if (buffers.empty()) {
    return absl::Status(absl::StatusCode::kInvalidArgument,
                        "No command buffers provided.");
  }
  // Success or failure, you only get to submit once.
  fml::ScopedCleanupClosure reset([&]() {
    if (completion_callback) {
      completion_callback(CommandBuffer::Status::kError);
    }
  });

  std::vector<vk::CommandBuffer> vk_buffers;
  std::vector<std::shared_ptr<TrackedObjectsVK>> tracked_objects;
  vk_buffers.reserve(buffers.size());
  tracked_objects.reserve(buffers.size());
  for (const std::shared_ptr<CommandBuffer>& buffer : buffers) {
    CommandBufferVK& command_buffer = CommandBufferVK::Cast(*buffer);
    if (!command_buffer.EndCommandBuffer()) {
      return absl::Status(absl::StatusCode::kCancelled,
                          "Failed to end command buffer.");
    }
    vk_buffers.push_back(command_buffer.GetCommandBuffer());
    tracked_objects.push_back(std::move(command_buffer.tracked_objects_));
  }

  auto context = context_.lock();
  if (!context) {
    VALIDATION_LOG << "Device lost.";
    return absl::Status(absl::StatusCode::kCancelled, "Device lost.");
  }
  auto [fence_result, fence] = context->GetDevice().createFenceUnique({});
  if (fence_result != vk::Result::eSuccess) {
    VALIDATION_LOG << "Failed to create fence: " << vk::to_string(fence_result);
    return absl::Status(absl::StatusCode::kCancelled,
                        "Failed to create fence.");
  }

  auto pending_waits = context->TakePendingWaitSemaphores();
  std::shared_ptr<std::vector<vk::UniqueSemaphore>> external_wait_semaphores;
  std::vector<vk::Semaphore> raw_wait_semaphores;
  std::vector<vk::PipelineStageFlags> wait_stages;
  if (!pending_waits.empty()) {
    external_wait_semaphores =
        std::make_shared<std::vector<vk::UniqueSemaphore>>();
    external_wait_semaphores->reserve(pending_waits.size());
    raw_wait_semaphores.reserve(pending_waits.size());
    wait_stages.reserve(pending_waits.size());
    for (auto& item : pending_waits) {
      raw_wait_semaphores.push_back(*item.semaphore);
      wait_stages.push_back(item.wait_stage);
      external_wait_semaphores->push_back(std::move(item.semaphore));
    }
  }

  // This will be called immediately by FenceWaiterVK::AddFence.
  auto submit_callback = [&context, &vk_buffers, &raw_wait_semaphores,
                          &wait_stages](vk::Fence submit_fence) {
    vk::SubmitInfo submit_info;
    submit_info.setCommandBuffers(vk_buffers);
    if (!raw_wait_semaphores.empty()) {
      submit_info.setWaitSemaphores(raw_wait_semaphores);
      submit_info.setWaitDstStageMask(wait_stages);
    }
    auto status =
        context->GetGraphicsQueue()->Submit(submit_info, submit_fence);
    if (status != vk::Result::eSuccess) {
      std::string submit_error =
          "Failed to submit command: " + vk::to_string(status);
      VALIDATION_LOG << submit_error;
      return absl::Status(absl::StatusCode::kCancelled, submit_error);
    }
    return absl::OkStatus();
  };

  std::shared_ptr<GpuSubmissionTracker> tracker =
      context->GetMutableSubmissionTracker();
  uint64_t submission_id = tracker->RecordSubmission();

  // This will be called later when the command completes.
  std::weak_ptr<Context> weak_context = context;
  auto fence_complete_callback =
      [completion_callback, tracker, submission_id, weak_context,
       tracked_objects = std::move(tracked_objects),
       external_wait_semaphores =
           std::move(external_wait_semaphores)]() mutable {
        // Defer vkDestroySemaphore through ContextVK's bounded retirement FIFO
        // so Mali's `mali-event-hand` thread finishes `signal_internal()`
        // first.
        if (external_wait_semaphores && !external_wait_semaphores->empty()) {
          if (auto strong_ctx = weak_context.lock()) {
            ContextVK::Cast(*strong_ctx)
                .RetireCompletedWaitSemaphores(*external_wait_semaphores);
          } else {
            external_wait_semaphores->clear();
          }
          external_wait_semaphores.reset();
        }
        tracked_objects.clear();
        tracker->RecordCompletion(submission_id);
        if (completion_callback) {
          completion_callback(CommandBuffer::Status::kCompleted);
        }
      };

  // Submit will proceed, call callback with true when it is done and do not
  // call when `reset` is collected.
  auto fence_status = context->GetFenceWaiter()->AddFence(
      std::move(fence), submit_callback, std::move(fence_complete_callback));
  if (!fence_status.ok()) {
    tracker->RecordCompletion(submission_id);
    return fence_status;
  }
  reset.Release();
  return absl::OkStatus();
}

CommandQueue::SubmitResult CommandQueueVK::SubmitWithReceipt(
    const std::shared_ptr<CommandBuffer>& buffer,
    const CompletionCallback& completion_callback) {
  return {
      .status = Submit({buffer}, completion_callback),
  };
}

}  // namespace impeller
