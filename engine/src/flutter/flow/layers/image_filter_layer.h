// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_FLOW_LAYERS_IMAGE_FILTER_LAYER_H_
#define FLUTTER_FLOW_LAYERS_IMAGE_FILTER_LAYER_H_

#include <cstdint>
#include <limits>
#include <memory>
#include <optional>

#include "flutter/flow/layers/cacheable_layer.h"

namespace flutter {

class ImageFilterLayer : public CacheableContainerLayer {
 public:
  explicit ImageFilterLayer(const std::shared_ptr<DlImageFilter>& filter,
                            const DlPoint& offset = DlPoint());

  void Diff(DiffContext* context, const Layer* old_layer) override;

  void Preroll(PrerollContext* context) override;

  void Paint(PaintContext& context) const override;

  /// Computes the device-space rect of the input texture that Impeller
  /// allocates for a saveLayer with a runtime effect (|ui.ImageFilter.shader|)
  /// image filter, i.e. the coordinate frame in which the filter's
  /// |FlutterFragCoord| and first vec2 (size) uniform are expressed.
  ///
  /// This mirrors |impeller::ComputeSaveLayerCoverage| (which for runtime
  /// effect filters preserves the full transformed content coverage unless it
  /// exceeds |max_subpass_size|, in which case it falls back to intersecting
  /// with the clip) followed by the subpass sizing in
  /// |impeller::Canvas::SaveLayer| (the texture size is the truncated coverage
  /// size clamped to |max_subpass_size|, and the texture is placed at the
  /// coverage origin, which this rounds to the nearest device pixel).
  ///
  /// @param content_device_bounds The bounds of the layer's content,
  ///        transformed into device space.
  /// @param device_cull_rect The device-space clip at the layer.
  /// @param max_subpass_size The maximum render pass attachment size.
  ///
  /// @return The frame, or std::nullopt if the layer is entirely clipped.
  static std::optional<DlIRect> ComputeRuntimeEffectInputFrame(
      const DlRect& content_device_bounds,
      const DlRect& device_cull_rect,
      DlISize max_subpass_size = DlISize(std::numeric_limits<int32_t>::max(),
                                         std::numeric_limits<int32_t>::max()));

 private:
  DlPoint offset_;
  const std::shared_ptr<DlImageFilter> filter_;
  std::shared_ptr<DlImageFilter> transformed_filter_;

  FML_DISALLOW_COPY_AND_ASSIGN(ImageFilterLayer);
};

}  // namespace flutter

#endif  // FLUTTER_FLOW_LAYERS_IMAGE_FILTER_LAYER_H_
