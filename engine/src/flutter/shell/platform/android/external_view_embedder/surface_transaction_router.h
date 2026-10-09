// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_SURFACE_TRANSACTION_ROUTER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_SURFACE_TRANSACTION_ROUTER_H_

#include <atomic>
#include <cstdint>

#include "flutter/fml/macros.h"

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      Decides, per frame, how the SurfaceControl transactions of the
///             HC++ swapchain reach SurfaceFlinger, and remembers which
///             platform-routed frames SurfaceFlinger has not committed yet.
///
///             A swapchain buffer is presented by applying a transaction.
///             There are two ways to do that:
///
///             - |Route::kDirect|: the raster thread owns the transaction and
///               applies it itself as soon as the frame is submitted.
///             - |Route::kPlatform|: the transaction is created by Java
///               (PlatformViewsController2.createTransaction) and merged with
///               the platform view mutations of the same frame on the platform
///               thread, which hands the merged transaction to ViewRootImpl
///               (AttachedSurfaceControl.applyTransactionOnDraw) so that it is
///               applied together with the next View hierarchy draw.
///
///             The two routes apply with different apply tokens, so
///             SurfaceFlinger does not order them with respect to each other.
///             A direct frame could therefore overtake a platform frame that
///             is still waiting for its View draw, and the older buffer would
///             be shown after the newer one. To prevent that, the view
///             embedder keeps routing frames through the platform thread for
///             as long as |HasUncommittedPlatformFrames| is true.
///
///             Only the raster thread selects the route. The uncommitted frame
///             count is modified from the raster thread (submission) and the
///             platform thread (commit), and is therefore atomic.
///
class SurfaceTransactionRouter {
 public:
  static constexpr int64_t kInvalidVsyncId = -1;

  enum class Route {
    kDirect,
    kPlatform,
  };

  SurfaceTransactionRouter();

  ~SurfaceTransactionRouter();

  //----------------------------------------------------------------------------
  /// @brief      Selects the route of the frame that is being submitted. The
  ///             route stays in effect until it is set again, so the caller
  ///             must restore |Route::kDirect| when the submission is over.
  ///
  /// @note       Raster thread only.
  ///
  void SetFrameRoute(Route route);

  //----------------------------------------------------------------------------
  /// @brief      The route of the frame that is being submitted. Read by the
  ///             swapchain when it creates the transaction that presents a
  ///             buffer.
  ///
  /// @note       Raster thread only.
  ///
  Route GetFrameRoute() const;

  //----------------------------------------------------------------------------
  /// @brief      Records the Choreographer frame timeline vsync IDs for the
  ///             vsync that is starting on the UI thread:
  ///
  ///             - |direct_vsync_id|: the platform-preferred timeline vsync ID
  ///               (`preferredFrameTimelineIndex`, 1-vsync deadline), used when
  ///               the swapchain applies its transaction directly on the raster
  ///               thread (|Route::kDirect|).
  ///             - |platform_vsync_id|: the next timeline vsync ID
  ///               (`preferredFrameTimelineIndex + 1`, 2-vsync deadline), used
  ///               when the transaction is routed through the platform thread
  ///               (|Route::kPlatform|) and handed to ViewRootImpl via
  ///               `AttachedSurfaceControl.applyTransactionOnDraw` to be
  ///               applied on the next View hierarchy traversal.
  ///
  ///               NOTE: When ViewRootImpl draws a buffer on that traversal,
  ///               `BLASTBufferQueue` merges the `applyTransactionOnDraw`
  ///               transaction into its buffer transaction. Due to an AOSP bug
  ///               in
  ///               `SurfaceComposerClient::Transaction::mergeFrameTimelineInfo`
  ///               (`SurfaceComposerClient.cpp`, which checks
  ///               `other.vsyncId > t.vsyncId` instead of `<` despite its
  ///               comment stating "When merging vsync Ids we take the oldest
  ///               valid one"), ViewRootImpl's newer vsync ID overwrites
  ///               |platform_vsync_id| until that AOSP bug is fixed.
  ///
  /// @note       Thread safe.
  ///
  void SetVsyncTimeline(int64_t direct_vsync_id, int64_t platform_vsync_id);

  //----------------------------------------------------------------------------
  /// @brief      Snapshots the latest vsync timeline recorded by
  ///             |SetVsyncTimeline| for the raster frame that is starting, so
  ///             that a subsequent UI-thread vsync arriving while the raster
  ///             thread is still rendering does not overwrite this frame's
  ///             vsync IDs before submission.
  ///
  /// @note       Raster thread only.
  ///
  void LatchVsyncTimeline();

  //----------------------------------------------------------------------------
  /// @brief      Returns the frame timeline vsync ID matching the current
  ///             |GetFrameRoute|: the direct vsync ID (`preferredIndex`) for
  ///             |Route::kDirect|, or the platform vsync ID
  ///             (`preferredIndex + 1`) for |Route::kPlatform|. Returns
  ///             |kInvalidVsyncId| if no timeline has been recorded.
  ///
  /// @note       Raster thread only.
  ///
  int64_t GetFrameTimelineVsyncId() const;

  //----------------------------------------------------------------------------
  /// @brief      Records that a frame was handed to the platform thread.
  ///
  /// @note       Thread safe.
  ///
  void OnPlatformFrameSubmitted();

  //----------------------------------------------------------------------------
  /// @brief      Records that SurfaceFlinger committed a platform-routed
  ///             frame, or that the frame was dropped without ever being
  ///             applied. Calls that are not matched by an earlier
  ///             |OnPlatformFrameSubmitted| are ignored.
  ///
  /// @note       Thread safe.
  ///
  void OnPlatformFrameCommitted();

  //----------------------------------------------------------------------------
  /// @brief      Whether any platform-routed frame is still uncommitted.
  ///
  /// @note       Thread safe.
  ///
  bool HasUncommittedPlatformFrames() const;

 private:
  // Raster thread only.
  Route frame_route_ = Route::kDirect;
  bool vsync_timeline_latched_ = false;
  int64_t latched_direct_vsync_id_ = kInvalidVsyncId;
  int64_t latched_platform_vsync_id_ = kInvalidVsyncId;

  std::atomic<int64_t> pending_direct_vsync_id_{kInvalidVsyncId};
  std::atomic<int64_t> pending_platform_vsync_id_{kInvalidVsyncId};
  std::atomic<int32_t> uncommitted_platform_frames_{0};

  FML_DISALLOW_COPY_AND_ASSIGN(SurfaceTransactionRouter);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_SURFACE_TRANSACTION_ROUTER_H_
