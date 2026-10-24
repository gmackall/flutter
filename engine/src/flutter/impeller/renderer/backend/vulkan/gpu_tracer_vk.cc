// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "impeller/renderer/backend/vulkan/gpu_tracer_vk.h"

#include <algorithm>
#include <memory>
#include <optional>
#include <thread>
#include <utility>
#include <vector>

#include "fml/logging.h"
#include "fml/trace_event.h"
#include "impeller/base/validation.h"
#include "impeller/renderer/backend/vulkan/command_buffer_vk.h"
#include "impeller/renderer/backend/vulkan/context_vk.h"

namespace impeller {

static constexpr uint32_t kPoolSize = 128u;

GPUTracerVK::GPUTracerVK(std::weak_ptr<ContextVK> context,
                         bool enable_gpu_tracing)
    : context_(std::move(context)) {
  if (!enable_gpu_tracing) {
    return;
  }
  timestamp_period_ = context_.lock()
                          ->GetDeviceHolder()
                          ->GetPhysicalDevice()
                          .getProperties()
                          .limits.timestampPeriod;
  if (timestamp_period_ <= 0) {
    // The device does not support timestamp queries.
    return;
  }
  enabled_ = true;
}

void GPUTracerVK::InitializeQueryPool(const ContextVK& context) {
  if (!enabled_) {
    return;
  }
  std::shared_ptr<CommandBuffer> buffer = context.CreateCommandBuffer();
  CommandBufferVK& buffer_vk = CommandBufferVK::Cast(*buffer);

  {
    Lock lock(trace_state_mutex_);
    for (auto i = 0u; i < kTraceStatesSize; i++) {
      vk::QueryPoolCreateInfo info;
      info.queryCount = kPoolSize;
      info.queryType = vk::QueryType::eTimestamp;

      auto [status, pool] = context.GetDevice().createQueryPoolUnique(info);
      if (status != vk::Result::eSuccess) {
        VALIDATION_LOG << "Failed to create query pool.";
        return;
      }
      trace_states_[i].query_pool = std::move(pool);
      buffer_vk.GetCommandBuffer().resetQueryPool(
          trace_states_[i].query_pool.get(), 0, kPoolSize);
    }
  }
  if (!context.GetCommandQueue()->Submit({buffer}).ok()) {
    VALIDATION_LOG << "Failed to reset query pool for trace events.";
    enabled_ = false;
  }
}

bool GPUTracerVK::IsEnabled() const {
  return enabled_;
}

int64_t GPUTracerVK::GetLastFrameGpuTimeNs() const {
  return last_frame_gpu_time_ns_.load(std::memory_order_relaxed);
}

uint64_t GPUTracerVK::GetLastFrameGpuTimeGeneration() const {
  return last_frame_gpu_time_generation_.load(std::memory_order_relaxed);
}

void GPUTracerVK::MarkFrameStart() {
  Lock lock(trace_state_mutex_);
  active_frame_depth_ += 1;
  if (active_frame_depth_ > 1u || !enabled_) {
    return;
  }
  in_frame_ = true;
  raster_thread_id_ = std::this_thread::get_id();
  trace_states_[current_state_].in_progress = true;
}

void GPUTracerVK::MarkFrameEnd() {
  size_t completed_frame_index = 0;
  size_t query_count = 0;
  vk::QueryPool pool;
  std::vector<std::pair<size_t, size_t>> query_pairs;
  bool should_read_completed_state = false;
  {
    Lock lock(trace_state_mutex_);
    if (active_frame_depth_ > 0u) {
      active_frame_depth_ -= 1;
      if (active_frame_depth_ > 0u) {
        return;
      }
    }
    in_frame_ = false;

    if (!enabled_) {
      return;
    }

    auto& completed_state = trace_states_[current_state_];
    completed_state.in_progress = false;
    if (completed_state.pending_buffers == 0u &&
        completed_state.current_index > 0u) {
      completed_frame_index = current_state_;
      query_count = completed_state.current_index;
      pool = completed_state.query_pool.get();
      query_pairs = std::move(completed_state.query_pairs);
      completed_state.query_pairs.clear();
      should_read_completed_state = true;
    }

    current_state_ = (current_state_ + 1) % kTraceStatesSize;

    auto& state = trace_states_[current_state_];
    if (state.pending_buffers > 0u && state.current_index > 0u) {
      // A command buffer from kTraceStatesSize frames ago (such as an idle
      // overlay swapchain's retained final_cmd_buffer) has not released its
      // GPUProbe yet. Schedule the query pool for reset and advance the
      // generation so its late completion is ignored.
      states_to_reset_.push_back(current_state_);
    }
    state.generation += 1;
    state.pending_buffers = 0;
    state.open_buffers = 0;
    state.current_index = 0;
    state.in_progress = false;
    state.query_pairs.clear();
  }

  if (should_read_completed_state) {
    ReadAndResetCompletedState(pool, query_count, completed_frame_index,
                               std::move(query_pairs));
  }
}

std::unique_ptr<GPUProbe> GPUTracerVK::CreateGPUProbe() {
  return std::make_unique<GPUProbe>(weak_from_this());
}

void GPUTracerVK::RecordCmdBufferStart(const vk::CommandBuffer& buffer,
                                       GPUProbe& probe) {
  if (!enabled_) {
    return;
  }
  Lock lock(trace_state_mutex_);
  if (!in_frame_ || std::this_thread::get_id() != raster_thread_id_) {
    return;
  }
  auto& state = trace_states_[current_state_];

  // Reset previously completed queries.
  if (!states_to_reset_.empty()) {
    for (auto i = 0u; i < states_to_reset_.size(); i++) {
      buffer.resetQueryPool(trace_states_[states_to_reset_[i]].query_pool.get(),
                            0, kPoolSize);
    }
    states_to_reset_.clear();
  }

  // We size the query pool to kPoolSize, but Flutter applications can create an
  // unbounded amount of work per frame. Reserve room for all currently open
  // command buffers' end timestamps plus this command buffer's start/end pair.
  if (state.current_index + state.open_buffers + 2u > kPoolSize) {
    return;
  }

  // Use eColorAttachmentOutput rather than eTopOfPipe so that onscreen
  // swapchain command buffers submitted with a wait semaphore at
  // eColorAttachmentOutput (waiting on SurfaceFlinger's buffer release fence)
  // do not latch their start timestamp before the semaphore wait completes,
  // while avoiding eBottomOfPipe pipeline drains across offscreen command
  // buffers on Tile-Based Deferred Renderers.
  buffer.writeTimestamp(vk::PipelineStageFlagBits::eColorAttachmentOutput,
                        state.query_pool.get(), state.current_index);
  probe.index_ = current_state_;
  probe.generation_ = state.generation;
  probe.start_query_index_ = state.current_index;
  state.current_index += 1;
  state.open_buffers += 1;
  state.pending_buffers += 1;
}

void GPUTracerVK::RecordCmdBufferEnd(const vk::CommandBuffer& buffer,
                                     GPUProbe& probe) {
  if (!enabled_ || !probe.index_.has_value()) {
    return;
  }
  Lock lock(trace_state_mutex_);
  if (std::this_thread::get_id() != raster_thread_id_) {
    return;
  }
  GPUTraceState& state = trace_states_[probe.index_.value()];
  if (state.generation != probe.generation_) {
    return;
  }
  if (state.open_buffers > 0u) {
    state.open_buffers -= 1;
  }
  if (state.current_index >= kPoolSize) {
    return;
  }

  buffer.writeTimestamp(vk::PipelineStageFlagBits::eBottomOfPipe,
                        state.query_pool.get(), state.current_index);
  if (probe.start_query_index_.has_value()) {
    state.query_pairs.emplace_back(probe.start_query_index_.value(),
                                   state.current_index);
  }

  state.current_index += 1;
}

void GPUTracerVK::OnFenceComplete(size_t frame_index, uint64_t generation) {
  if (!enabled_) {
    return;
  }

  size_t pending = 0;
  size_t query_count = 0;
  bool in_progress = false;
  vk::QueryPool pool;
  std::vector<std::pair<size_t, size_t>> query_pairs;
  {
    Lock lock(trace_state_mutex_);
    GPUTraceState& state = trace_states_[frame_index];
    if (state.generation != generation || state.pending_buffers == 0u) {
      return;
    }

    state.pending_buffers -= 1;
    pending = state.pending_buffers;
    query_count = state.current_index;
    in_progress = state.in_progress;
    pool = state.query_pool.get();
    if (pending == 0 && !in_progress && query_count > 0) {
      query_pairs = std::move(state.query_pairs);
      state.query_pairs.clear();
    }
  }

  if (pending == 0 && !in_progress && query_count > 0) {
    ReadAndResetCompletedState(pool, query_count, frame_index,
                               std::move(query_pairs));
  }
}

void GPUTracerVK::ReadAndResetCompletedState(
    vk::QueryPool pool,
    size_t query_count,
    size_t frame_index,
    std::vector<std::pair<size_t, size_t>> query_pairs) {
  std::vector<uint64_t> bits(query_count);
  std::shared_ptr<ContextVK> context = context_.lock();
  if (!context) {
    return;
  }

  auto result = context->GetDevice().getQueryPoolResults(
      pool, 0, query_count, query_count * sizeof(uint64_t), bits.data(),
      sizeof(uint64_t), vk::QueryResultFlagBits::e64);
  // This may return VK_NOT_READY if the query couldn't be completed, or if
  // there are queries still pending. From local testing, this happens
  // occassionally on very expensive frames. Its unclear if we can do anything
  // about this, because by design this should only signal after all cmd
  // buffers have signaled. Adding VK_QUERY_RESULT_WAIT_BIT to the flags
  // passed to getQueryPoolResults seems like it would fix this, but actually
  // seems to result in more stuck query errors. Better to just drop them and
  // move on.
  if (result == vk::Result::eSuccess) {
    uint64_t total_ticks = 0;
    std::vector<std::pair<uint64_t, uint64_t>> intervals;
    intervals.reserve(query_pairs.size());
    for (const auto& [start_idx, end_idx] : query_pairs) {
      if (start_idx < bits.size() && end_idx < bits.size()) {
        uint64_t start_ts = std::min(bits[start_idx], bits[end_idx]);
        uint64_t end_ts = std::max(bits[start_idx], bits[end_idx]);
        intervals.emplace_back(start_ts, end_ts);
      }
    }
    if (!intervals.empty()) {
      std::sort(intervals.begin(), intervals.end());
      uint64_t cur_start = intervals[0].first;
      uint64_t cur_end = intervals[0].second;
      for (size_t i = 1; i < intervals.size(); ++i) {
        if (intervals[i].first <= cur_end) {
          cur_end = std::max(cur_end, intervals[i].second);
        } else {
          total_ticks += (cur_end - cur_start);
          cur_start = intervals[i].first;
          cur_end = intervals[i].second;
        }
      }
      total_ticks += (cur_end - cur_start);
    } else if (!bits.empty()) {
      uint64_t smallest_timestamp = std::numeric_limits<uint64_t>::max();
      uint64_t largest_timestamp = 0;
      for (auto i = 0u; i < bits.size(); i++) {
        smallest_timestamp = std::min(smallest_timestamp, bits[i]);
        largest_timestamp = std::max(largest_timestamp, bits[i]);
      }
      if (largest_timestamp >= smallest_timestamp) {
        total_ticks = largest_timestamp - smallest_timestamp;
      }
    }
    if (total_ticks > 0) {
      int64_t gpu_ns = static_cast<int64_t>(total_ticks * timestamp_period_);
      if (gpu_ns > 0) {
        last_frame_gpu_time_ns_.store(gpu_ns, std::memory_order_relaxed);
        last_frame_gpu_time_generation_.fetch_add(1, std::memory_order_relaxed);
      }
      auto gpu_ms = gpu_ns / 1000000;
      FML_TRACE_COUNTER("flutter", "GPUTracer",
                        reinterpret_cast<int64_t>(this),  // Trace Counter ID
                        "FrameTimeMS", gpu_ms);
    }
  }

  // Record this query to be reset the next time a command is recorded.
  Lock lock(trace_state_mutex_);
  states_to_reset_.push_back(frame_index);
}

GPUProbe::GPUProbe(const std::weak_ptr<GPUTracerVK>& tracer)
    : tracer_(tracer) {}

GPUProbe::~GPUProbe() {
  if (!index_.has_value()) {
    return;
  }
  auto tracer = tracer_.lock();
  if (!tracer) {
    return;
  }
  tracer->OnFenceComplete(index_.value(), generation_);
}

void GPUProbe::RecordCmdBufferStart(const vk::CommandBuffer& buffer) {
  auto tracer = tracer_.lock();
  if (!tracer) {
    return;
  }
  tracer->RecordCmdBufferStart(buffer, *this);
}

void GPUProbe::RecordCmdBufferEnd(const vk::CommandBuffer& buffer) {
  auto tracer = tracer_.lock();
  if (!tracer) {
    return;
  }
  tracer->RecordCmdBufferEnd(buffer, *this);
}

}  // namespace impeller
