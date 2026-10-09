// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/external_view_embedder/surface_transaction_router.h"

#include "gtest/gtest.h"

namespace flutter {
namespace testing {

using Route = SurfaceTransactionRouter::Route;

TEST(SurfaceTransactionRouter, DefaultsToDirectRouteWithNothingUncommitted) {
  SurfaceTransactionRouter router;

  EXPECT_EQ(router.GetFrameRoute(), Route::kDirect);
  EXPECT_EQ(router.GetFrameTimelineVsyncId(),
            SurfaceTransactionRouter::kInvalidVsyncId);
  EXPECT_FALSE(router.HasUncommittedPlatformFrames());
}

TEST(SurfaceTransactionRouter, FrameRouteIsLatchedUntilSetAgain) {
  SurfaceTransactionRouter router;

  router.SetFrameRoute(Route::kPlatform);
  EXPECT_EQ(router.GetFrameRoute(), Route::kPlatform);
  // The route is read once per surface submission; it must not be consumed
  // by the first read because one frame can submit an overlay and the root.
  EXPECT_EQ(router.GetFrameRoute(), Route::kPlatform);

  router.SetFrameRoute(Route::kDirect);
  EXPECT_EQ(router.GetFrameRoute(), Route::kDirect);
}

TEST(SurfaceTransactionRouter, FrameTimelineVsyncIdFollowsFrameRoute) {
  SurfaceTransactionRouter router;
  router.SetVsyncTimeline(/*direct_vsync_id=*/100, /*platform_vsync_id=*/101);

  EXPECT_EQ(router.GetFrameRoute(), Route::kDirect);
  EXPECT_EQ(router.GetFrameTimelineVsyncId(), 100);

  router.SetFrameRoute(Route::kPlatform);
  EXPECT_EQ(router.GetFrameTimelineVsyncId(), 101);

  router.SetFrameRoute(Route::kDirect);
  EXPECT_EQ(router.GetFrameTimelineVsyncId(), 100);
}

TEST(SurfaceTransactionRouter,
     LatchedVsyncTimelineIsIsolatedFromSubsequentUiVsync) {
  SurfaceTransactionRouter router;
  router.SetVsyncTimeline(/*direct_vsync_id=*/100, /*platform_vsync_id=*/101);
  router.LatchVsyncTimeline();

  // Simulate the UI thread receiving the next vsync while the raster thread is
  // still rendering the current frame.
  router.SetVsyncTimeline(/*direct_vsync_id=*/200, /*platform_vsync_id=*/201);

  EXPECT_EQ(router.GetFrameTimelineVsyncId(), 100);
  router.SetFrameRoute(Route::kPlatform);
  EXPECT_EQ(router.GetFrameTimelineVsyncId(), 101);

  // When the next raster frame starts and latches the timeline, it picks up
  // the new vsync IDs.
  router.LatchVsyncTimeline();
  EXPECT_EQ(router.GetFrameTimelineVsyncId(), 201);
  router.SetFrameRoute(Route::kDirect);
  EXPECT_EQ(router.GetFrameTimelineVsyncId(), 200);
}

TEST(SurfaceTransactionRouter, PlatformFramesStayUncommittedUntilEachCommits) {
  SurfaceTransactionRouter router;

  router.OnPlatformFrameSubmitted();
  router.OnPlatformFrameSubmitted();
  EXPECT_TRUE(router.HasUncommittedPlatformFrames());

  router.OnPlatformFrameCommitted();
  EXPECT_TRUE(router.HasUncommittedPlatformFrames());

  router.OnPlatformFrameCommitted();
  EXPECT_FALSE(router.HasUncommittedPlatformFrames());
}

TEST(SurfaceTransactionRouter, UnmatchedCommitDoesNotMaskLaterSubmission) {
  SurfaceTransactionRouter router;

  router.OnPlatformFrameCommitted();
  EXPECT_FALSE(router.HasUncommittedPlatformFrames());

  // Had the stray commit driven the count negative, this submission would be
  // invisible and a direct frame could overtake it.
  router.OnPlatformFrameSubmitted();
  EXPECT_TRUE(router.HasUncommittedPlatformFrames());

  router.OnPlatformFrameCommitted();
  EXPECT_FALSE(router.HasUncommittedPlatformFrames());
}

}  // namespace testing
}  // namespace flutter
