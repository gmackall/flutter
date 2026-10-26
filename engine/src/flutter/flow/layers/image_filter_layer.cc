// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/flow/layers/image_filter_layer.h"

#include <cmath>

#include "flutter/display_list/effects/dl_image_filters.h"
#include "flutter/display_list/utils/dl_comparable.h"
#include "flutter/flow/layers/layer.h"
#include "flutter/flow/raster_cache_util.h"

namespace flutter {

ImageFilterLayer::ImageFilterLayer(const std::shared_ptr<DlImageFilter>& filter,
                                   const DlPoint& offset)
    : CacheableContainerLayer(
          RasterCacheUtil::kMinimumRendersBeforeCachingFilterLayer),
      offset_(offset),
      filter_(filter),
      transformed_filter_(nullptr) {}

void ImageFilterLayer::Diff(DiffContext* context, const Layer* old_layer) {
  DiffContext::AutoSubtreeRestore subtree(context);
  auto* prev = static_cast<const ImageFilterLayer*>(old_layer);
  if (!context->IsSubtreeDirty()) {
    FML_DCHECK(prev);
    if (NotEquals(filter_, prev->filter_) || offset_ != prev->offset_) {
      context->MarkSubtreeDirty(context->GetOldLayerPaintRegion(old_layer));
    }
  }

  context->PushTransform(DlMatrix::MakeTranslation(offset_));
  if (context->has_raster_cache()) {
    context->WillPaintWithIntegralTransform();
  }

  if (filter_) {
    auto filter = filter_->makeWithLocalMatrix(context->GetMatrix());
    if (filter) {
      // This transform will be applied to every child rect in the subtree
      context->PushFilterBoundsAdjustment([filter](DlRect rect) {
        DlIRect filter_out_bounds;
        filter->map_device_bounds(DlIRect::RoundOut(rect), DlMatrix(),
                                  filter_out_bounds);
        return DlRect::Make(filter_out_bounds);
      });
    }
  }
  DiffChildren(context, prev);
  context->SetLayerPaintRegion(this, context->CurrentSubtreeRegion());
}

namespace {

// Mirrors |impeller::SizeDifferenceUnderThreshold| in
// impeller/entity/save_layer_utils.cc.
bool SizeDifferenceUnderThreshold(DlSize a, DlSize b, DlScalar threshold) {
  return (std::abs(a.width - b.width) / b.width) < threshold &&
         (std::abs(a.height - b.height) / b.height) < threshold;
}

// Mirrors |kDefaultSizeThreshold| in impeller/entity/save_layer_utils.cc.
constexpr DlScalar kSaveLayerSizeThreshold = 0.3f;

bool ContainsPlatformView(const ContainerLayer& container) {
  for (const std::shared_ptr<Layer>& layer : container.layers()) {
    if (layer->as_platform_view_layer() != nullptr) {
      return true;
    }
    const ContainerLayer* child_container = layer->as_container_layer();
    if (child_container != nullptr && ContainsPlatformView(*child_container)) {
      return true;
    }
  }
  return false;
}

}  // namespace

std::optional<DlIRect> ImageFilterLayer::ComputeRuntimeEffectInputFrame(
    const DlRect& content_device_bounds,
    const DlRect& device_cull_rect) {
  if (content_device_bounds.IsEmpty() || !content_device_bounds.IsFinite()) {
    return std::nullopt;
  }
  // For runtime effect filters, |GetSourceCoverage| of the coverage limit is
  // the coverage limit itself, so the "worst case" source coverage is the
  // device clip. See |impeller::ComputeSaveLayerCoverage|.
  std::optional<DlRect> intersection =
      content_device_bounds.Intersection(device_cull_rect);
  if (!intersection.has_value() || intersection->IsEmpty()) {
    return std::nullopt;
  }
  DlRect coverage = intersection.value();
  if (SizeDifferenceUnderThreshold(content_device_bounds.GetSize(),
                                   intersection->GetSize(),
                                   kSaveLayerSizeThreshold)) {
    coverage = content_device_bounds;
  }
  // |impeller::Canvas::SaveLayer| sizes the subpass texture with
  // ISize(coverage.GetSize()) (i.e. truncation) when the layer has an image
  // filter, and positions it at the (possibly fractional) coverage origin.
  // When the texture is composited back, each device pixel picks up the texel
  // nearest to its center, which corresponds to rounding the origin.
  const int64_t width = static_cast<int64_t>(coverage.GetWidth());
  const int64_t height = static_cast<int64_t>(coverage.GetHeight());
  if (width <= 0 || height <= 0) {
    return std::nullopt;
  }
  return DlIRect::MakeXYWH(std::round(coverage.GetX()),
                           std::round(coverage.GetY()), width, height);
}

void ImageFilterLayer::Preroll(PrerollContext* context) {
  auto mutator = context->state_stack.save();
  mutator.translate(offset_);

  Layer::AutoPrerollSaveLayerState save =
      Layer::AutoPrerollSaveLayerState::Create(context);

#if !SLIMPELLER
  AutoCache cache = AutoCache(layer_raster_cache_item_.get(), context,
                              context->state_stack.matrix());
#endif  //  !SLIMPELLER

  // The transform under which this layer's saveLayer (and therefore its image
  // filter) is applied.
  const DlMatrix filter_matrix = context->state_stack.matrix();

  DlRect child_bounds;
  {
    // State that only applies to the children (and in particular to any
    // platform views among them) is pushed in a nested scope so that it is
    // gone again before |AutoCache| finalizes and before |transformed_filter_|
    // is computed below.
    auto child_mutator = context->state_stack.save();

    std::shared_ptr<ContentImageFilterFrame> platform_view_frame;
    DlRect device_cull_rect;
#if !SLIMPELLER
    std::vector<RasterCacheItem*>* raster_cached_entries =
        context->raster_cached_entries;
#endif  //  !SLIMPELLER

    // Filters are only reported to platform views (as a transform for matrix
    // filters, and as a kContentImageFilter mutator for runtime effect
    // filters) if there are platform views that could observe them. For
    // other subtrees, prerolling is unaffected by the filter.
    //
    // For matrix filters this matters for raster caching: the children are
    // painted under |filter_matrix| (the matrix is applied by the saveLayer's
    // filter) but would be prerolled under |filter_matrix| * |matrix|, so any
    // raster cache entries that they created would be keyed on a matrix that
    // is never used while painting. Rather than regressing raster caching for
    // every matrix filter (e.g. the Skia overscroll stretch), the transform is
    // only pushed when a platform view can observe it, and in that case the
    // children's own raster cache entries are suppressed. (The layer can still
    // cache its children as a whole, see |layer_raster_cache_item_|, which is
    // keyed on |filter_matrix|.)
    const DlMatrixImageFilter* matrix_filter =
        filter_ ? filter_->asMatrix() : nullptr;
    const bool is_runtime_effect_filter =
        filter_ && filter_->asRuntimeEffectFilter() != nullptr;
    if ((matrix_filter != nullptr || is_runtime_effect_filter) &&
        context->view_embedder != nullptr && ContainsPlatformView(*this)) {
      if (matrix_filter != nullptr) {
        // A matrix filter renders its children exactly as a TransformLayer
        // with the same matrix would (modulo sampling), so give platform
        // views the same treatment they get under a TransformLayer: a
        // kTransform mutator and a CTM that includes the matrix, which keeps
        // EmbeddedViewParams::transformMatrix() and finalBoundingRect()
        // consistent with the mutators.
        const DlMatrix& matrix = matrix_filter->matrix();
        if (matrix.IsFinite() && matrix.IsInvertible()) {
          child_mutator.transform(matrix);
#if !SLIMPELLER
          context->raster_cached_entries = nullptr;
#endif  //  !SLIMPELLER
        }
      } else {
        // Runtime effect filters are reported to platform views as a
        // kContentImageFilter mutator. Their coordinate frame depends on the
        // bounds of all of the children, which are only known after
        // PrerollChildren, so it is filled in below.
        platform_view_frame = std::make_shared<ContentImageFilterFrame>();
        device_cull_rect = context->state_stack.device_cull_rect();
        child_mutator.applyImageFilter(DlRect(), filter_, platform_view_frame);
      }
    }

    PrerollChildren(context, &child_bounds);

#if !SLIMPELLER
    context->raster_cached_entries = raster_cached_entries;
#endif  //  !SLIMPELLER

    if (platform_view_frame) {
      platform_view_frame->Set(ComputeRuntimeEffectInputFrame(
          child_bounds.TransformBounds(filter_matrix), device_cull_rect));
    }
  }

  if (!filter_) {
    child_bounds = child_bounds.Shift(offset_);
    set_paint_bounds(child_bounds);
    return;
  }

  // Our saveLayer would apply any outstanding opacity or any outstanding
  // color filter after it applies our image filter. So we can apply either
  // of those attributes with our saveLayer.
  context->renderable_state_flags =
      (LayerStateStack::kCallerCanApplyOpacity |
       LayerStateStack::kCallerCanApplyColorFilter);

  const DlIRect filter_in_bounds = DlIRect::RoundOut(child_bounds);
  DlIRect filter_out_bounds;
  filter_->map_device_bounds(filter_in_bounds, DlMatrix(), filter_out_bounds);
  child_bounds = DlRect::Make(filter_out_bounds).Shift(offset_);

  set_paint_bounds(child_bounds);

#if !SLIMPELLER
  // CacheChildren only when the transformed_filter_ doesn't equal null.
  // So in here we reset the LayerRasterCacheItem cache state.
  layer_raster_cache_item_->MarkNotCacheChildren();
#endif  //  !SLIMPELLER

  transformed_filter_ = filter_->makeWithLocalMatrix(filter_matrix);

#if !SLIMPELLER
  if (transformed_filter_) {
    layer_raster_cache_item_->MarkCacheChildren();
  }
#endif  //  !SLIMPELLER
}

void ImageFilterLayer::Paint(PaintContext& context) const {
  FML_DCHECK(needs_painting(context));

  auto mutator = context.state_stack.save();

#if !SLIMPELLER
  if (context.raster_cache) {
    // Try drawing the layer cache item from the cache before applying the
    // image filter if it was cached with the filter applied.
    if (!layer_raster_cache_item_->IsCacheChildren()) {
      DlPaint paint;
      if (layer_raster_cache_item_->Draw(context,
                                         context.state_stack.fill(paint))) {
        return;
      }
    }
  }
#endif  //  !SLIMPELLER

  // Only apply the offset if not being raster-cached to avoid the offset being
  // applied twice.
  mutator.translate(offset_);

#if !SLIMPELLER
  if (context.raster_cache) {
    mutator.integralTransform();
  }

  if (context.raster_cache && layer_raster_cache_item_->IsCacheChildren()) {
    // If we render the children from cache then we need the special
    // transformed version of the filter so we must process it into the
    // cache paint object manually.
    FML_DCHECK(transformed_filter_ != nullptr);
    DlPaint paint;
    context.state_stack.fill(paint);
    paint.setImageFilter(transformed_filter_);
    if (layer_raster_cache_item_->Draw(context, &paint)) {
      return;
    }
  }
#endif  //  !SLIMPELLER

  // Now apply the image filter and then try rendering the children.
  mutator.applyImageFilter(child_paint_bounds(), filter_);

  PaintChildren(context);
}

}  // namespace flutter
