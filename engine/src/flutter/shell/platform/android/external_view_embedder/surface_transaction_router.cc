// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/external_view_embedder/surface_transaction_router.h"

namespace flutter {

SurfaceTransactionRouter::SurfaceTransactionRouter() = default;

SurfaceTransactionRouter::~SurfaceTransactionRouter() = default;

void SurfaceTransactionRouter::SetFrameRoute(Route route) {
  frame_route_ = route;
}

SurfaceTransactionRouter::Route SurfaceTransactionRouter::GetFrameRoute()
    const {
  return frame_route_;
}

void SurfaceTransactionRouter::SetVsyncTimeline(int64_t direct_vsync_id,
                                                int64_t platform_vsync_id) {
  pending_direct_vsync_id_.store(direct_vsync_id, std::memory_order_release);
  pending_platform_vsync_id_.store(platform_vsync_id,
                                   std::memory_order_release);
}

void SurfaceTransactionRouter::LatchVsyncTimeline() {
  latched_direct_vsync_id_ =
      pending_direct_vsync_id_.load(std::memory_order_acquire);
  latched_platform_vsync_id_ =
      pending_platform_vsync_id_.load(std::memory_order_acquire);
  vsync_timeline_latched_ = true;
}

int64_t SurfaceTransactionRouter::GetFrameTimelineVsyncId() const {
  const int64_t direct_id =
      vsync_timeline_latched_
          ? latched_direct_vsync_id_
          : pending_direct_vsync_id_.load(std::memory_order_acquire);
  const int64_t platform_id =
      vsync_timeline_latched_
          ? latched_platform_vsync_id_
          : pending_platform_vsync_id_.load(std::memory_order_acquire);
  return frame_route_ == Route::kDirect ? direct_id : platform_id;
}

void SurfaceTransactionRouter::OnPlatformFrameSubmitted() {
  uncommitted_platform_frames_.fetch_add(1, std::memory_order_acq_rel);
}

void SurfaceTransactionRouter::OnPlatformFrameCommitted() {
  // Saturate at zero rather than trusting every caller to be paired: a stray
  // commit must not let the count go negative and mask a later submission.
  int32_t current =
      uncommitted_platform_frames_.load(std::memory_order_acquire);
  while (current > 0 && !uncommitted_platform_frames_.compare_exchange_weak(
                            current, current - 1, std::memory_order_acq_rel,
                            std::memory_order_acquire)) {
  }
}

bool SurfaceTransactionRouter::HasUncommittedPlatformFrames() const {
  return uncommitted_platform_frames_.load(std::memory_order_acquire) > 0;
}

}  // namespace flutter
