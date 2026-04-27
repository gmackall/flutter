// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/flow/layers/overscroll_stretch_layer.h"
#include "flutter/flow/layers/layer.h"

namespace flutter {

OverscrollStretchLayer::OverscrollStretchLayer(
    const std::shared_ptr<DlImageFilter>& filter,
    double x_stretch,
    double y_stretch,
    const DlPoint& offset)
    : ImageFilterLayer(filter, offset),
      x_stretch_(x_stretch),
      y_stretch_(y_stretch) {}

void OverscrollStretchLayer::Preroll(PrerollContext* context) {
  auto mutator = context->state_stack.save();
  mutator.applyOverscrollStretch(x_stretch_, y_stretch_);

  if (context->view_embedder != nullptr) {
    context->view_embedder->PushOverscrollStretchToVisitedPlatformViews(
        x_stretch_, y_stretch_);
  }

  ImageFilterLayer::Preroll(context);
}

}  // namespace flutter
