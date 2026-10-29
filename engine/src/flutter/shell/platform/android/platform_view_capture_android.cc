// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/platform_view_capture_android.h"

#include "flutter/fml/platform/android/jni_util.h"
#include "flutter/fml/platform/android/scoped_java_ref.h"
#include "flutter/shell/platform/android/image_external_texture_vk_impeller.h"

namespace flutter {

namespace {

class Source final : public PlatformViewSnapshotSource {
 public:
  Source(const std::shared_ptr<impeller::ContextVK>& context,
         int64_t view_id,
         const fml::jni::ScopedJavaGlobalRef<jobject>& consumer,
         const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade)
      : consumer_(consumer),
        texture_(std::make_shared<ImageExternalTextureVKImpeller>(
            context,
            view_id,
            consumer,
            jni_facade,
            ImageExternalTexture::ImageLifecycle::kKeepAlive)) {}

  // |PlatformViewSnapshotSource|
  sk_sp<DlImage> AcquireLatestImage() override {
    return texture_->AcquireLatestDlImage();
  }

  const fml::jni::ScopedJavaGlobalRef<jobject>& consumer() const {
    return consumer_;
  }

 private:
  const fml::jni::ScopedJavaGlobalRef<jobject> consumer_;
  const std::shared_ptr<ImageExternalTextureVKImpeller> texture_;
};

}  // namespace

PlatformViewCaptureAndroid::PlatformViewCaptureAndroid(
    std::shared_ptr<impeller::ContextVK> impeller_context,
    std::shared_ptr<PlatformViewAndroidJNI> jni_facade)
    : impeller_context_(std::move(impeller_context)),
      jni_facade_(std::move(jni_facade)) {}

std::shared_ptr<PlatformViewSnapshotSource>
PlatformViewCaptureAndroid::RequestCapture(
    int64_t view_id,
    bool substituted,
    const std::shared_ptr<PlatformViewSnapshotSource>& previous) {
  if (!impeller_context_) {
    return nullptr;
  }
  JavaLocalRef consumer =
      jni_facade_->capturePlatformView2(view_id, substituted);
  if (consumer.is_null()) {
    return nullptr;
  }

  JNIEnv* env = fml::jni::AttachCurrentThread();
  if (previous &&
      env->IsSameObject(static_cast<const Source&>(*previous).consumer().obj(),
                        consumer.obj())) {
    return previous;
  }

  return std::make_shared<Source>(
      impeller_context_, view_id,
      fml::jni::ScopedJavaGlobalRef<jobject>(env, consumer.obj()), jni_facade_);
}

}  // namespace flutter
