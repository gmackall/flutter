// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_EXTERNAL_VIEW_EMBEDDER_2_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_EXTERNAL_VIEW_EMBEDDER_2_H_

#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "flutter/common/task_runners.h"
#include "flutter/flow/embedded_views.h"
#include "flutter/shell/platform/android/context/android_context.h"
#include "flutter/shell/platform/android/external_view_embedder/surface_pool.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"
#include "flutter/shell/platform/android/surface/android_surface.h"
#include "third_party/abseil-cpp/absl/container/flat_hash_set.h"

namespace flutter {

//------------------------------------------------------------------------------
/// A source of offscreen captures of the content of one HCPP platform view.
class PlatformViewSnapshotSource {
 public:
  virtual ~PlatformViewSnapshotSource() = default;

  /// Returns the most recent capture of the platform view, or null if there
  /// is none yet. Called on the raster thread.
  virtual sk_sp<DlImage> AcquireLatestImage() = 0;
};

//------------------------------------------------------------------------------
/// Asks the platform to capture HCPP platform views that lie under a backdrop
/// filter, so the filter can be applied to them. All methods are called on
/// the platform thread.
class PlatformViewCaptureDelegate {
 public:
  virtual ~PlatformViewCaptureDelegate() = default;

  /// Starts (or continues) capturing the platform view |view_id|: the
  /// platform renders the view offscreen now if it has changed since its last
  /// capture, and again whenever its content changes. Returns the source of
  /// the captured images (reusing |previous| when the underlying consumer has
  /// not changed), or null if the view cannot be captured (for example
  /// because it renders through a SurfaceView).
  ///
  /// If |substituted|, this frame draws the capture in place of the platform
  /// view, so the platform must not draw the live view (it stays in place for
  /// input and accessibility).
  virtual std::shared_ptr<PlatformViewSnapshotSource> RequestCapture(
      int64_t view_id,
      bool substituted,
      const std::shared_ptr<PlatformViewSnapshotSource>& previous) = 0;
};

//------------------------------------------------------------------------------
/// Allows to embed Android views into a Flutter application.
///
/// This class calls Java methods via |PlatformViewAndroidJNI| to manage the
/// lifecycle of the Android view corresponding to |flutter::PlatformViewLayer|.
///
/// It also orchestrates overlay surfaces. These are Android views
/// that render above (by Z order) the Android view corresponding to
/// |flutter::PlatformViewLayer|.
///
/// This implementation of the external view embedder is designed only to use
/// HC++ mode. Mixing old HC modes is not supported, but either of the texture
/// composition based platform views can be used with either mode.
class AndroidExternalViewEmbedder2 final : public ExternalViewEmbedder {
 public:
  AndroidExternalViewEmbedder2(
      const AndroidContext& android_context,
      std::shared_ptr<PlatformViewAndroidJNI> jni_facade,
      std::shared_ptr<AndroidSurfaceFactory> surface_factory,
      const TaskRunners& task_runners,
      std::shared_ptr<PlatformViewCaptureDelegate> capture_delegate = nullptr);

  // |ExternalViewEmbedder|
  void PrerollCompositeEmbeddedView(
      int64_t view_id,
      std::unique_ptr<flutter::EmbeddedViewParams> params) override;

  // |ExternalViewEmbedder|
  DlCanvas* CompositeEmbeddedView(int64_t view_id) override;

  // |ExternalViewEmbedder|
  void SubmitFlutterView(
      int64_t flutter_view_id,
      GrDirectContext* context,
      const std::shared_ptr<impeller::AiksContext>& aiks_context,
      std::unique_ptr<SurfaceFrame> frame) override;

  // |ExternalViewEmbedder|
  PostPrerollResult PostPrerollAction(
      const fml::RefPtr<fml::RasterThreadMerger>& raster_thread_merger)
      override;

  // |ExternalViewEmbedder|
  DlCanvas* GetRootCanvas() override;

  // |ExternalViewEmbedder|
  void BeginFrame(GrDirectContext* context,
                  const fml::RefPtr<fml::RasterThreadMerger>&
                      raster_thread_merger) override;

  // |ExternalViewEmbedder|
  void PrepareFlutterView(DlISize frame_size,
                          double device_pixel_ratio) override;

  // |ExternalViewEmbedder|
  void CancelFrame() override;

  // |ExternalViewEmbedder|
  void EndFrame(bool should_resubmit_frame,
                const fml::RefPtr<fml::RasterThreadMerger>&
                    raster_thread_merger) override;

  // |ExternalViewEmbedder|
  bool SupportsDynamicThreadMerging() override;

  // |ExternalViewEmbedder|
  void Teardown() override;

  // |ExternalViewEmbedder|
  void PushFilterToVisitedPlatformViews(
      const std::shared_ptr<DlImageFilter>& filter,
      const DlRect& filter_rect) override;

  // The platform views that are under a backdrop filter this frame, and so
  // need to be captured.
  std::vector<int64_t> GetViewsToCaptureForTesting() const;

  // Installs a capture source as if the capture delegate had returned it.
  void SetSnapshotSourceForTesting(
      int64_t view_id,
      std::shared_ptr<PlatformViewSnapshotSource> source);

  // Whether |view_id| is drawn from its capture this frame.
  bool IsSubstitutedForTesting(int64_t view_id) const;

  // Gets the rect based on the device pixel ratio of a platform view displayed
  // on the screen.
  static DlRect GetViewRect(
      int64_t view_id,
      const std::unordered_map<int64_t, EmbeddedViewParams>& view_params);

 private:
  // The number of frames the rasterizer task runner will continue
  // to run on the platform thread after no platform view is rendered.
  //
  // Note: this is an arbitrary number that attempts to account for cases
  // where the platform view might be momentarily off the screen.
  static const int kDefaultMergedLeaseDuration = 10;

  // Provides metadata to the Android surfaces.
  const AndroidContext& android_context_;

  // Allows to call methods in Java.
  const std::shared_ptr<PlatformViewAndroidJNI> jni_facade_;

  // Allows to create surfaces.
  const std::shared_ptr<AndroidSurfaceFactory> surface_factory_;

  // Holds surfaces. Allows to recycle surfaces or allocate new ones.
  const std::unique_ptr<SurfacePool> surface_pool_;

  // The task runners.
  const TaskRunners task_runners_;

  // If the overlay layer is currently shown.
  std::atomic_bool overlay_layer_is_shown_{false};

  // The size of the root canvas.
  DlISize frame_size_;

  // The pixel ratio used to determinate the size of a platform view layer
  // relative to the device layout system.
  double device_pixel_ratio_ = 0.0;

  // The order of composition. Each entry contains a unique id for the platform
  // view.
  std::vector<int64_t> composition_order_;

  // The |EmbedderViewSlice| implementation keyed off the platform view id,
  // which contains any subsequent operations until the next platform view or
  // the end of the last leaf node in the layer tree.
  std::unordered_map<int64_t, std::unique_ptr<EmbedderViewSlice>> slices_;

  // The params for a platform view, which contains the size, position and
  // mutation stack.
  std::unordered_map<int64_t, EmbeddedViewParams> view_params_;

  // The set of platform views that were visible in the last frame.
  absl::flat_hash_set<int64_t> views_visible_last_frame_;

  // ---------------------------------------------------------------------------
  // Backdrop filters over platform views.
  //
  // A backdrop filter that is painted after a platform view only sees the
  // Flutter content of its own slice, because the platform view itself lives
  // in the Android view hierarchy. When that happens, the platform is asked to
  // render the platform view offscreen (a capture). While a capture is
  // available, the platform view is "substituted": its capture is drawn at the
  // start of its slice, exactly where a texture layer platform view would be
  // drawn, so the backdrop filters painted after it filter it like any other
  // Flutter content. The live platform view stays in the view hierarchy (for
  // input and accessibility) but does not draw, and is left out of slicing.
  // If a capture is unavailable the frame is rendered exactly as without this
  // feature.
  // ---------------------------------------------------------------------------

  // Tuning constant: how far around a backdrop filter (as a fraction of the
  // frame height) platform views are captured ahead of time, giving lead time
  // for views scrolling under a filter. Capped to frames where some platform
  // view is already covered by a filter.
  static constexpr DlScalar kCapturePrefetchFraction = 0.25f;

  // State shared with tasks posted to the platform thread.
  struct CaptureState {
    std::mutex mutex;
    // Guarded by mutex (written only on the platform thread). The capture
    // source of each captured platform view.
    std::unordered_map<int64_t, std::shared_ptr<PlatformViewSnapshotSource>>
        sources;
  };

  // Asks the platform to capture exactly |views|. Platform thread.
  static void UpdateCaptures(
      const std::shared_ptr<PlatformViewCaptureDelegate>& delegate,
      const std::shared_ptr<CaptureState>& state,
      const std::vector<int64_t>& views,
      const std::vector<int64_t>& substituted_views);

  // The platform views to capture this frame.
  std::vector<int64_t> ViewsToCapture() const;

  // Acquires the latest capture of each platform view in |filtered_views_|.
  void AcquireSnapshots();

  // Whether |view_id| is under a backdrop filter and has a capture this frame.
  bool ShouldSubstitute(int64_t view_id) const;

  // Draws |image|, a capture of a platform view, as the platform view with
  // |params| would be composited.
  static void DrawPlatformViewSnapshot(DlCanvas& canvas,
                                       const EmbeddedViewParams& params,
                                       const sk_sp<DlImage>& image);

  // Null if capturing platform views is not supported.
  const std::shared_ptr<PlatformViewCaptureDelegate> capture_delegate_;

  const std::shared_ptr<CaptureState> capture_state_ =
      std::make_shared<CaptureState>();

  // On-screen platform views that intersect a backdrop filter painted after
  // them this frame. Substituted when they have a capture.
  absl::flat_hash_set<int64_t> filtered_views_;

  // On-screen platform views in the prefetch neighborhood of a backdrop filter
  // painted after them this frame (a superset of filtered_views_).
  absl::flat_hash_set<int64_t> nearby_views_;

  // This frame's captures, keyed by platform view id.
  std::unordered_map<int64_t, sk_sp<DlImage>> snapshots_;

  // Whether snapshots_ has been populated this frame.
  bool snapshots_acquired_ = false;

  // The platform views drawn from their capture this frame.
  absl::flat_hash_set<int64_t> substituted_views_;

  // Destroys the surfaces created from the surface factory.
  // This method schedules a task on the platform thread, and waits for
  // the task until it completes.
  void DestroySurfaces();

  // Resets the state.
  void Reset();

  // Whether the layer tree in the current frame has platform layers.
  bool FrameHasPlatformLayers();

  // Shows the overlay layer if it has content and the previous frame did not.
  void ShowOverlayLayerIfNeeded();

  // Hides the overlay layer if it does not have content and the previous
  // frame did have content.
  void HideOverlayLayerIfNeeded();
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_EXTERNAL_VIEW_EMBEDDER_2_H_
