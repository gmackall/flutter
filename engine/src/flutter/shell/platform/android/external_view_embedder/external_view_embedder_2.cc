// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/external_view_embedder/external_view_embedder_2.h"

#include <algorithm>
#include <cmath>

#include "display_list/dl_color.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/trace_event.h"
#include "fml/make_copyable.h"
#include "fml/synchronization/count_down_latch.h"

namespace flutter {

namespace {

// Converts a size in logical pixels to the whole number of physical pixels the
// platform view should be laid out at.
//
// Android view geometry is integral, so this conversion is lossy no matter
// what, but it must *round* rather than truncate. `size_points * dpr` is
// computed in double precision from a float `size_points`, so a platform view
// that exactly covers the screen lands a hair under the physical size (e.g.
// 1079.99996 for a 1080px wide screen at a device pixel ratio of 2.625).
// Truncating that drops a pixel off the right and bottom edges, and the Flutter
// content behind the platform view shows through as a thin line.
//
// See https://github.com/flutter/flutter/issues/189834.
int32_t ToPhysicalPixels(DlScalar size_points, double device_pixel_ratio) {
  return static_cast<int32_t>(std::round(size_points * device_pixel_ratio));
}

}  // namespace

AndroidExternalViewEmbedder2::AndroidExternalViewEmbedder2(
    const AndroidContext& android_context,
    std::shared_ptr<PlatformViewAndroidJNI> jni_facade,
    std::shared_ptr<AndroidSurfaceFactory> surface_factory,
    const TaskRunners& task_runners,
    std::shared_ptr<PlatformViewCaptureDelegate> capture_delegate)
    : ExternalViewEmbedder(),
      android_context_(android_context),
      jni_facade_(std::move(jni_facade)),
      surface_factory_(std::move(surface_factory)),
      surface_pool_(
          std::make_unique<SurfacePool>(/*use_new_surface_methods=*/true)),
      task_runners_(task_runners),
      capture_delegate_(std::move(capture_delegate)) {}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::PrerollCompositeEmbeddedView(
    int64_t view_id,
    std::unique_ptr<EmbeddedViewParams> params) {
  TRACE_EVENT0("flutter",
               "AndroidExternalViewEmbedder2::PrerollCompositeEmbeddedView");

  DlRect view_bounds = DlRect::MakeSize(frame_size_);
  std::unique_ptr<EmbedderViewSlice> view;
  view = std::make_unique<DisplayListEmbedderViewSlice>(view_bounds);
  slices_.insert_or_assign(view_id, std::move(view));

  composition_order_.push_back(view_id);
  // Update params only if they changed.
  if (view_params_.count(view_id) == 1 &&
      view_params_.at(view_id) == *params.get()) {
    return;
  }
  view_params_.insert_or_assign(view_id, EmbeddedViewParams(*params.get()));
}

// |ExternalViewEmbedder|
DlCanvas* AndroidExternalViewEmbedder2::CompositeEmbeddedView(int64_t view_id) {
  if (slices_.count(view_id) == 0) {
    return nullptr;
  }
  DlCanvas* slice_canvas = slices_.at(view_id)->canvas();
  if (filtered_views_.empty()) {
    return slice_canvas;
  }
  // Preroll, which collected the backdrop filters, has finished; acquire the
  // captures once per frame.
  if (!snapshots_acquired_) {
    AcquireSnapshots();
    snapshots_acquired_ = true;
  }
  if (!ShouldSubstitute(view_id)) {
    return slice_canvas;
  }
  // Draw the capture of the platform view, as the platform view would be
  // composited, at the start of its slice. Everything painted after the
  // platform view (including the backdrop filters over it) is recorded into
  // the same slice right after, so the filters see the platform view exactly
  // like they would see a texture layer platform view. The live platform view
  // stops drawing itself (see FlutterMutatorView#setDrawToWindow) and no
  // longer takes part in slicing.
  DrawPlatformViewSnapshot(*slice_canvas, view_params_.at(view_id),
                           snapshots_.at(view_id));
  substituted_views_.insert(view_id);
  return slice_canvas;
}

bool AndroidExternalViewEmbedder2::ShouldSubstitute(int64_t view_id) const {
  // Until the first capture is produced (or if the view cannot be captured),
  // fall back to standard HCPP composition.
  return filtered_views_.contains(view_id) && snapshots_.contains(view_id);
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::PushFilterToVisitedPlatformViews(
    const std::shared_ptr<DlImageFilter>& filter,
    const DlRect& filter_rect) {
  // A filter painted before any platform view is below all of them.
  if (!filter || !capture_delegate_ || composition_order_.empty()) {
    return;
  }
  const DlRect frame = DlRect::MakeSize(frame_size_);
  const std::optional<DlRect> rect = filter_rect.Intersection(frame);
  if (!rect.has_value() || rect->IsEmpty()) {
    return;
  }

  // Platform views that are close to the filter are captured too, so that a
  // platform view that moves under the filter (for example while scrolling)
  // already has a capture when it gets there.
  const DlScalar margin = kCapturePrefetchFraction * frame_size_.height;
  const DlRect capture_rect =
      rect->Expand(margin, margin).IntersectionOrEmpty(frame);

  // Propagate substitution and prefetch backwards to any earlier platform view
  // that overlaps a later filtered or prefetched view. Substituting a later
  // view onto the background canvas (z = -2) while leaving an earlier
  // overlapping view live at z = 0 would invert their painter's order and force
  // SliceViews to hoist the later view's slice (and part of the backdrop
  // filter) onto the overlay canvas.
  for (size_t i = composition_order_.size(); i-- > 0;) {
    const int64_t view_id = composition_order_[i];
    const DlRect view_rect =
        GetViewRect(view_id, view_params_).IntersectionOrEmpty(frame);
    bool filtered = filtered_views_.contains(view_id) ||
                    view_rect.IntersectsWithRect(rect.value());
    bool nearby = nearby_views_.contains(view_id) || filtered ||
                  view_rect.IntersectsWithRect(capture_rect);
    for (size_t k = i + 1;
         k < composition_order_.size() && (!filtered || !nearby); ++k) {
      const int64_t later_id = composition_order_[k];
      if (!filtered_views_.contains(later_id) &&
          !nearby_views_.contains(later_id)) {
        continue;
      }
      const DlRect later_rect =
          GetViewRect(later_id, view_params_).IntersectionOrEmpty(frame);
      if (view_rect.IntersectsWithRect(later_rect)) {
        if (filtered_views_.contains(later_id)) {
          filtered = true;
          nearby = true;
        } else {
          nearby = true;
        }
      }
    }
    if (filtered) {
      filtered_views_.insert(view_id);
    }
    if (nearby) {
      nearby_views_.insert(view_id);
    }
  }
}

std::vector<int64_t> AndroidExternalViewEmbedder2::ViewsToCapture() const {
  if (filtered_views_.empty()) {
    return {};
  }
  std::vector<int64_t> views;
  for (int64_t view_id : composition_order_) {
    if (nearby_views_.contains(view_id)) {
      views.push_back(view_id);
    }
  }
  return views;
}

std::vector<int64_t> AndroidExternalViewEmbedder2::GetViewsToCaptureForTesting()
    const {
  return ViewsToCapture();
}

void AndroidExternalViewEmbedder2::SetSnapshotSourceForTesting(
    int64_t view_id,
    std::shared_ptr<PlatformViewSnapshotSource> source) {
  std::scoped_lock lock(capture_state_->mutex);
  capture_state_->sources[view_id] = std::move(source);
}

bool AndroidExternalViewEmbedder2::IsSubstitutedForTesting(
    int64_t view_id) const {
  return substituted_views_.count(view_id) == 1;
}

void AndroidExternalViewEmbedder2::AcquireSnapshots() {
  std::unordered_map<int64_t, std::shared_ptr<PlatformViewSnapshotSource>>
      sources;
  {
    std::scoped_lock lock(capture_state_->mutex);
    sources = capture_state_->sources;
  }
  const DlRect screen_bounds = DlRect::MakeSize(frame_size_);
  for (size_t i = 0; i < composition_order_.size(); ++i) {
    int64_t view_id = composition_order_[i];
    if (filtered_views_.count(view_id) == 0) {
      continue;
    }
    auto params_it = view_params_.find(view_id);
    if (params_it == view_params_.end()) {
      continue;
    }
    DlRect view_rect = GetViewRect(view_id, view_params_);
    if (!view_rect.IntersectsWithRect(screen_bounds)) {
      continue;
    }
    bool blocked_by_earlier_unsubstituted = false;
    for (size_t j = 0; j < i; ++j) {
      int64_t earlier_id = composition_order_[j];
      if (ShouldSubstitute(earlier_id)) {
        continue;
      }
      DlRect earlier_rect = GetViewRect(earlier_id, view_params_);
      if (earlier_rect.IntersectsWithRect(screen_bounds) &&
          earlier_rect.IntersectsWithRect(view_rect)) {
        blocked_by_earlier_unsubstituted = true;
        break;
      }
    }
    if (blocked_by_earlier_unsubstituted) {
      continue;
    }
    auto it = sources.find(view_id);
    if (it == sources.end() || !it->second) {
      continue;
    }
    sk_sp<DlImage> image = it->second->AcquireLatestImage();
    if (image) {
      snapshots_[view_id] = std::move(image);
    }
  }
}

void AndroidExternalViewEmbedder2::DrawPlatformViewSnapshot(
    DlCanvas& canvas,
    const EmbeddedViewParams& params,
    const sk_sp<DlImage>& image) {
  DlAutoCanvasRestore restore(&canvas, /*do_save=*/true);
  DlScalar opacity = 1.0f;
  const MutatorsStack& mutators = params.mutatorsStack();
  for (auto it = mutators.Begin(); it != mutators.End(); ++it) {
    const Mutator& mutator = **it;
    switch (mutator.GetType()) {
      case MutatorType::kTransform:
        canvas.Transform(mutator.GetMatrix());
        break;
      case MutatorType::kClipRect:
        canvas.ClipRect(mutator.GetRect(), DlClipOp::kIntersect, true);
        break;
      case MutatorType::kClipRRect:
        canvas.ClipRoundRect(mutator.GetRRect(), DlClipOp::kIntersect, true);
        break;
      case MutatorType::kClipRSE:
        canvas.ClipRoundSuperellipse(mutator.GetRSE(), DlClipOp::kIntersect,
                                     true);
        break;
      case MutatorType::kClipPath:
        canvas.ClipPath(mutator.GetPath(), DlClipOp::kIntersect, true);
        break;
      case MutatorType::kOpacity:
        opacity *= mutator.GetAlphaFloat();
        break;
      default:
        // Backdrop filters and their clips do not affect how the platform
        // view itself is drawn.
        break;
    }
  }
  DlPaint paint;
  paint.setOpacity(opacity);
  canvas.DrawImageRect(image, DlRect::MakeSize(params.sizePoints()),
                       DlImageSampling::kLinear, &paint);
}

// static
void AndroidExternalViewEmbedder2::UpdateCaptures(
    const std::shared_ptr<PlatformViewCaptureDelegate>& delegate,
    const std::shared_ptr<CaptureState>& state,
    const std::vector<int64_t>& views,
    const std::vector<int64_t>& substituted_views) {
  if (!delegate) {
    return;
  }
  std::unordered_map<int64_t, std::shared_ptr<PlatformViewSnapshotSource>>
      next_sources;
  for (int64_t view_id : views) {
    std::shared_ptr<PlatformViewSnapshotSource> previous;
    // state->sources is only modified on the platform thread, so reading it
    // here without the lock is safe.
    if (auto it = state->sources.find(view_id); it != state->sources.end()) {
      previous = it->second;
    }
    const bool substituted =
        std::find(substituted_views.begin(), substituted_views.end(),
                  view_id) != substituted_views.end();
    // A view that cannot be captured is logged (once) by the platform.
    if (std::shared_ptr<PlatformViewSnapshotSource> source =
            delegate->RequestCapture(view_id, substituted, previous)) {
      next_sources[view_id] = std::move(source);
    }
  }
  {
    std::scoped_lock lock(state->mutex);
    state->sources.swap(next_sources);
  }
}

DlRect AndroidExternalViewEmbedder2::GetViewRect(
    int64_t view_id,
    const std::unordered_map<int64_t, EmbeddedViewParams>& view_params) {
  const EmbeddedViewParams& params = view_params.at(view_id);
  // https://github.com/flutter/flutter/issues/59821
  return params.finalBoundingRect();
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::SubmitFlutterView(
    int64_t flutter_view_id,
    GrDirectContext* context,
    const std::shared_ptr<impeller::AiksContext>& aiks_context,
    std::unique_ptr<SurfaceFrame> frame) {
  TRACE_EVENT0("flutter", "AndroidExternalViewEmbedder2::SubmitFlutterView");

  if (!FrameHasPlatformLayers()) {
    frame->Submit();
    jni_facade_->sealFrameTransactions();
    task_runners_.GetPlatformTaskRunner()->PostTask(
        fml::MakeCopyable([this, jni_facade = jni_facade_,
                           views_visible_last_frame = views_visible_last_frame_,
                           capture_delegate = capture_delegate_,
                           capture_state = capture_state_]() {
          // This pointer is guaranteed to not be dangling as long as
          // DestroySurfaces is called before the embedder is deleted. See
          // https://github.com/flutter/flutter/pull/176742#discussion_r2415229396.
          this->HideOverlayLayerIfNeeded();
          for (int64_t view_id : views_visible_last_frame) {
            jni_facade->hidePlatformView2(view_id);
          }
          UpdateCaptures(capture_delegate, capture_state, {}, {});

          jni_facade->swapTransaction();
          jni_facade->onEndFrame2();
        }));
    views_visible_last_frame_.clear();
    return;
  }

  // Slice each recorded view against earlier unsubstituted platform views,
  // keeping one overlay rect per intersected view so disjoint views do not
  // union across substituted views or backdrop filters between them.
  std::unordered_map<int64_t, std::vector<DlRect>> overlay_layers;
  {
    DlAutoCanvasRestore save(frame->Canvas(), /*do_save=*/true);
    for (size_t i = 0; i < composition_order_.size(); i++) {
      int64_t view_id = composition_order_[i];
      EmbedderViewSlice* slice = slices_.at(view_id).get();
      if (slice->canvas() == nullptr) {
        continue;
      }
      slice->end_recording();
      std::vector<DlRect> overlay_rects;
      for (int j = static_cast<int>(i); j >= 0; j--) {
        int64_t earlier_id = composition_order_[j];
        if (substituted_views_.contains(earlier_id)) {
          continue;
        }
        DlRect view_rect = GetViewRect(earlier_id, view_params_);
        DlIRect rounded_in = DlIRect::RoundIn(view_rect);
        DlRect joined;
        for (const DlIRect& rect : slice->region(view_rect).getRects()) {
          if (rect.IntersectsWithRect(rounded_in)) {
            joined = joined.Union(DlRect::Make(rect));
          }
        }
        if (!joined.IsEmpty()) {
          frame->Canvas()->ClipRect(joined, DlClipOp::kDifference);
          for (auto it = overlay_rects.begin(); it != overlay_rects.end();) {
            if (it->Expand(0.5f, 0.5f).IntersectsWithRect(joined)) {
              joined = joined.Union(*it);
              overlay_rects.erase(it);
              it = overlay_rects.begin();
            } else {
              ++it;
            }
          }
          overlay_rects.push_back(joined);
        }
      }
      if (!overlay_rects.empty()) {
        overlay_layers.emplace(view_id, std::move(overlay_rects));
      }
      slice->render_into(frame->Canvas());
    }
  }

  // If there is no overlay Surface, initialize one on the platform thread. This
  // will only be done once per application launch, as the singular overlay
  // surface is never released.
  if (!surface_pool_->HasLayers()) {
    std::shared_ptr<fml::CountDownLatch> latch =
        std::make_shared<fml::CountDownLatch>(1u);
    task_runners_.GetPlatformTaskRunner()->PostTask(
        fml::MakeCopyable([&, latch]() {
          surface_pool_->GetLayer(context, android_context_, jni_facade_,
                                  surface_factory_);
          latch->CountDown();
        }));
    latch->Wait();
  }
  surface_pool_->ResetLayers();

  // Create Overlay frame. If overlay surface creation failed,
  // all this work must be skipped.
  std::unique_ptr<SurfaceFrame> overlay_frame;
  if (surface_pool_->HasLayers()) {
    for (size_t i = 0; i < composition_order_.size(); i++) {
      int64_t view_id = composition_order_[i];
      auto overlay = overlay_layers.find(view_id);

      if (overlay == overlay_layers.end()) {
        continue;
      }
      if (overlay_frame == nullptr) {
        std::shared_ptr<OverlayLayer> layer = surface_pool_->GetLayer(
            context, android_context_, jni_facade_, surface_factory_);
        overlay_frame = layer->surface->AcquireFrame(frame_size_);
        overlay_frame->Canvas()->Clear(flutter::DlColor::kTransparent());
      }

      DlCanvas* overlay_canvas = overlay_frame->Canvas();
      for (const DlRect& overlay_rect : overlay->second) {
        int restore_count = overlay_canvas->GetSaveCount();
        overlay_canvas->Save();
        overlay_canvas->ClipRect(overlay_rect);

        // For all following platform views that would cover this overlay,
        // emulate the effect by adding a difference clip. This makes the
        // overlays appear as if they are under the platform view, when in
        // reality there is only a single layer. Substituted views are drawn in
        // place in their slice rather than as live views above the base
        // surface, so they do not cover the overlay.
        for (size_t j = i + 1; j < composition_order_.size(); j++) {
          if (!substituted_views_.contains(composition_order_[j])) {
            DlRect view_rect = GetViewRect(composition_order_[j], view_params_);
            overlay_canvas->ClipRect(view_rect, DlClipOp::kDifference);
          }
        }

        slices_[view_id]->render_into(overlay_canvas);
        overlay_canvas->RestoreToCount(restore_count);
      }
    }
  }
  bool overlay_layer_has_content_this_frame_;
  if (overlay_frame != nullptr) {
    overlay_frame->set_submit_info({.frame_boundary = false});
    overlay_frame->Submit();
    overlay_layer_has_content_this_frame_ = true;
  } else {
    overlay_layer_has_content_this_frame_ = false;
  }

  frame->Submit();
  // Every path that posts a platform task calling swapTransaction() must call
  // sealFrameTransactions() once to keep the per-frame queue paired 1:1.
  jni_facade_->sealFrameTransactions();

  std::vector<int64_t> views_to_capture = ViewsToCapture();
  std::vector<int64_t> substituted_views(substituted_views_.begin(),
                                         substituted_views_.end());

  task_runners_.GetPlatformTaskRunner()->PostTask(fml::MakeCopyable(
      [&, composition_order = composition_order_, view_params = view_params_,
       jni_facade = jni_facade_, device_pixel_ratio = device_pixel_ratio_,
       slices = std::move(slices_),
       views_visible_last_frame = views_visible_last_frame_,
       overlay_layer_has_content_this_frame_,
       views_to_capture = std::move(views_to_capture),
       substituted_views = std::move(substituted_views),
       capture_delegate = capture_delegate_,
       capture_state = capture_state_]() mutable -> void {
        if (overlay_layer_has_content_this_frame_) {
          ShowOverlayLayerIfNeeded();
        } else {
          HideOverlayLayerIfNeeded();
        }

        for (int64_t view_id : composition_order) {
          // Round, rather than truncate, the bounds onto the pixel grid. See
          // `ToPhysicalPixels` for why.
          const DlIRect view_rect =
              DlIRect::Round(GetViewRect(view_id, view_params));
          const EmbeddedViewParams& params = view_params.at(view_id);
          jni_facade->onDisplayPlatformView2(
              view_id,                //
              view_rect.GetX(),       //
              view_rect.GetY(),       //
              view_rect.GetWidth(),   //
              view_rect.GetHeight(),  //
              ToPhysicalPixels(params.sizePoints().width, device_pixel_ratio),
              ToPhysicalPixels(params.sizePoints().height, device_pixel_ratio),
              params.mutatorsStack()  //
          );
          // Remove from views visible last frame, so we can hide the rest.
          views_visible_last_frame.erase(view_id);
        }
        // Hide views that were visible last frame, but not in this frame.
        for (int64_t view_id : views_visible_last_frame) {
          jni_facade->hidePlatformView2(view_id);
        }

        // Capture the platform views under backdrop filters after they have
        // been laid out for this frame. Must run before onEndFrame2 so the
        // drawToWindow flip and the raster transaction land in the same HWUI
        // frame via applyTransactionOnDraw.
        UpdateCaptures(capture_delegate, capture_state, views_to_capture,
                       substituted_views);

        jni_facade->swapTransaction();
        jni_facade_->onEndFrame2();
      }));

  views_visible_last_frame_.clear();
  views_visible_last_frame_.insert(composition_order_.begin(),
                                   composition_order_.end());
}

// |ExternalViewEmbedder|
PostPrerollResult AndroidExternalViewEmbedder2::PostPrerollAction(
    const fml::RefPtr<fml::RasterThreadMerger>& raster_thread_merger) {
  return PostPrerollResult::kSuccess;
}

bool AndroidExternalViewEmbedder2::FrameHasPlatformLayers() {
  return !composition_order_.empty();
}

// |ExternalViewEmbedder|
DlCanvas* AndroidExternalViewEmbedder2::GetRootCanvas() {
  // On Android, the root surface is created from the on-screen render target.
  return nullptr;
}

void AndroidExternalViewEmbedder2::Reset() {
  composition_order_.clear();
  slices_.clear();
  filtered_views_.clear();
  nearby_views_.clear();
  snapshots_.clear();
  snapshots_acquired_ = false;
  substituted_views_.clear();
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::BeginFrame(
    GrDirectContext* context,
    const fml::RefPtr<fml::RasterThreadMerger>& raster_thread_merger) {}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::PrepareFlutterView(
    DlISize frame_size,
    double device_pixel_ratio) {
  Reset();

  // The singular overlay surface is persistent, so it is resized in place by
  // |SurfacePool::GetLayer| rather than destroyed and recreated here.
  if (frame_size_ != frame_size) {
    // This should not block to prevent deadlocks with
    // setViewportMetrics.
    task_runners_.GetPlatformTaskRunner()->PostTask(fml::MakeCopyable(
        [jni_facade = jni_facade_, frame_size = frame_size]() {
          jni_facade->MaybeResizeSurfaceView(frame_size.width,
                                             frame_size.height);
        }));
  }
  surface_pool_->SetFrameSize(frame_size);

  frame_size_ = frame_size;
  device_pixel_ratio_ = device_pixel_ratio;
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::CancelFrame() {
  Reset();
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::EndFrame(
    bool should_resubmit_frame,
    const fml::RefPtr<fml::RasterThreadMerger>& raster_thread_merger) {}

// |ExternalViewEmbedder|
bool AndroidExternalViewEmbedder2::SupportsDynamicThreadMerging() {
  return false;
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::Teardown() {
  DestroySurfaces();
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder2::DestroySurfaces() {
  if (!surface_pool_->HasLayers()) {
    return;
  }
  fml::AutoResetWaitableEvent latch;
  fml::TaskRunner::RunNowOrPostTask(task_runners_.GetPlatformTaskRunner(),
                                    [&]() {
                                      surface_pool_->DestroyLayers(jni_facade_);
                                      latch.Signal();
                                    });
  latch.Wait();
  overlay_layer_is_shown_.store(false);
}

void AndroidExternalViewEmbedder2::ShowOverlayLayerIfNeeded() {
  if (!overlay_layer_is_shown_.load()) {
    jni_facade_->showOverlaySurface2();
    overlay_layer_is_shown_.store(true);
  }
}

void AndroidExternalViewEmbedder2::HideOverlayLayerIfNeeded() {
  if (overlay_layer_is_shown_.load()) {
    jni_facade_->hideOverlaySurface2();
    overlay_layer_is_shown_.store(false);
  }
}

}  // namespace flutter
