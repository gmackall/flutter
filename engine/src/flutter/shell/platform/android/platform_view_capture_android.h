// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_PLATFORM_VIEW_CAPTURE_ANDROID_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_PLATFORM_VIEW_CAPTURE_ANDROID_H_

#include <memory>

#include "flutter/fml/macros.h"
#include "flutter/impeller/renderer/backend/vulkan/context_vk.h"
#include "flutter/shell/platform/android/external_view_embedder/external_view_embedder_2.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"

namespace flutter {

//------------------------------------------------------------------------------
/// Captures HCPP platform views through
/// `PlatformViewsController2#capturePlatformView`, which renders the view into
/// an `ImageReader`-backed `SurfaceProducer`. The captured `HardwareBuffer`s
/// are imported into Impeller (Vulkan) through the same path that is used for
/// `SurfaceProducer` external textures.
class PlatformViewCaptureAndroid final : public PlatformViewCaptureDelegate {
 public:
  PlatformViewCaptureAndroid(
      std::shared_ptr<impeller::ContextVK> impeller_context,
      std::shared_ptr<PlatformViewAndroidJNI> jni_facade);

  // |PlatformViewCaptureDelegate|
  std::shared_ptr<PlatformViewSnapshotSource> RequestCapture(
      int64_t view_id,
      bool substituted,
      const std::shared_ptr<PlatformViewSnapshotSource>& previous) override;

 private:
  const std::shared_ptr<impeller::ContextVK> impeller_context_;
  const std::shared_ptr<PlatformViewAndroidJNI> jni_facade_;

  FML_DISALLOW_COPY_AND_ASSIGN(PlatformViewCaptureAndroid);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_PLATFORM_VIEW_CAPTURE_ANDROID_H_
