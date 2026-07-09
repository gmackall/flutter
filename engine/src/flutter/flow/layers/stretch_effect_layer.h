// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_FLOW_LAYERS_STRETCH_EFFECT_LAYER_H_
#define FLUTTER_FLOW_LAYERS_STRETCH_EFFECT_LAYER_H_

#include <memory>
#include "flutter/flow/layers/cacheable_layer.h"

namespace flutter {

// A layer that applies the Android overscroll stretch effect to its children.
//
// Flutter-rendered children are filtered with the supplied image filter (the
// framework's port of the AOSP stretch fragment shader), exactly like an
// ImageFilterLayer. In addition, the stretch parameters are recorded onto the
// mutators stack of any embedded platform view in the subtree, so that the
// platform-side embedding can apply an equivalent stretch to the native view
// (e.g. via a RuntimeShader RenderEffect on Android).
//
// `bounds` is the rect of the stretched container (typically the scrollable's
// viewport) in this layer's coordinate space after `offset` is applied. The
// stretch curve is normalized over these bounds, so an embedded view needs
// them to evaluate its own span of the curve.
class StretchEffectLayer : public CacheableContainerLayer {
 public:
  explicit StretchEffectLayer(const std::shared_ptr<DlImageFilter>& filter,
                              const DlPoint& offset,
                              const DlRect& bounds,
                              DlScalar stretch_x,
                              DlScalar stretch_y,
                              DlScalar interpolation_strength);

  void Diff(DiffContext* context, const Layer* old_layer) override;

  void Preroll(PrerollContext* context) override;

  void Paint(PaintContext& context) const override;

 private:
  DlPoint offset_;
  DlRect bounds_;
  DlScalar stretch_x_;
  DlScalar stretch_y_;
  DlScalar interpolation_strength_;
  const std::shared_ptr<DlImageFilter> filter_;
  std::shared_ptr<DlImageFilter> transformed_filter_;

  FML_DISALLOW_COPY_AND_ASSIGN(StretchEffectLayer);
};

}  // namespace flutter

#endif  // FLUTTER_FLOW_LAYERS_STRETCH_EFFECT_LAYER_H_
