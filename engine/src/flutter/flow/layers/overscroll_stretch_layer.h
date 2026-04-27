// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_FLOW_LAYERS_OVERSCROLL_STRETCH_LAYER_H_
#define FLUTTER_FLOW_LAYERS_OVERSCROLL_STRETCH_LAYER_H_

#include "flutter/flow/layers/image_filter_layer.h"

namespace flutter {

class OverscrollStretchLayer : public ImageFilterLayer {
 public:
  explicit OverscrollStretchLayer(const std::shared_ptr<DlImageFilter>& filter,
                                  double x_stretch,
                                  double y_stretch,
                                  const DlPoint& offset = DlPoint());

  void Preroll(PrerollContext* context) override;

 private:
  double x_stretch_;
  double y_stretch_;

  FML_DISALLOW_COPY_AND_ASSIGN(OverscrollStretchLayer);
};

}  // namespace flutter

#endif  // FLUTTER_FLOW_LAYERS_OVERSCROLL_STRETCH_LAYER_H_
