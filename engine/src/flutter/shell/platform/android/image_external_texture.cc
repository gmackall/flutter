// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture.h"

#include <android/hardware_buffer_jni.h>

#include <unordered_set>
#include <utility>
#include <vector>

#include "flutter/fml/platform/android/jni_util.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"

namespace flutter {

ImageExternalTexture::ImageExternalTexture(
    int64_t id,
    const fml::jni::ScopedJavaGlobalRef<jobject>& image_texture_entry,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    ImageLifecycle lifecycle,
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge)
    : Texture(id),
      jni_facade_(jni_facade),
      ndk_bridge_(std::move(ndk_bridge)),
      texture_lifecycle_(lifecycle) {
  if (!image_texture_entry.is_null()) {
    image_texture_entry_.Reset(image_texture_entry);
  }
}

ImageExternalTexture::~ImageExternalTexture() = default;

void ImageExternalTexture::Paint(PaintContext& context,
                                 const DlRect& bounds,
                                 bool freeze,
                                 const DlImageSampling sampling) {
  if (state_ == AttachmentState::kDetached) {
    return;
  }
  Attach(context);
  DrainPendingEvictions();
  if (!freeze) {
    ProcessFrame(context, ToSkRect(bounds));
  }
  if (dl_image_) {
    context.canvas->DrawImageRect(
        dl_image_,                             // image
        DlRect::Make(dl_image_->GetBounds()),  // source rect
        bounds,                                // destination rect
        sampling,                              // sampling
        context.paint,                         // paint
        flutter::DlSrcRectConstraint::kStrict  // enforce edges
    );
  } else {
    FML_LOG(INFO) << "No DlImage available for ImageExternalTexture to paint.";
  }
}

void ImageExternalTexture::MarkNewFrameAvailable() {}

void ImageExternalTexture::OnTextureUnregistered() {
  dl_image_.reset();
  image_cache_.clear();
}

void ImageExternalTexture::OnGrContextCreated() {
  state_ = AttachmentState::kUninitialized;
}

void ImageExternalTexture::OnGrContextDestroyed() {
  switch (texture_lifecycle_) {
    case ImageLifecycle::kReset:
      dl_image_.reset();
      image_cache_.clear();
      break;
    case ImageLifecycle::kKeepAlive:
      image_cache_.clear();
      break;
  }
  if (state_ == AttachmentState::kAttached) {
    Detach();
  }
  state_ = AttachmentState::kDetached;
}

void ImageExternalTexture::DrainPendingEvictions() {
  if (!ndk_bridge_) {
    return;
  }
  std::vector<NdkImageTextureBridge::BufferEviction> buffers;
  std::vector<uint64_t> closed_generations;
  ndk_bridge_->TakePendingEvictions(&buffers, &closed_generations);
  for (const auto& item : buffers) {
    image_cache_.erase(ImageCacheKey{
        .reader_generation = item.reader_generation,
        .hardware_buffer_id = item.buffer_id,
    });
  }
  if (!closed_generations.empty()) {
    std::unordered_set<uint64_t> closed_set(closed_generations.begin(),
                                            closed_generations.end());
    for (auto it = image_cache_.begin(); it != image_cache_.end();) {
      if (closed_set.find(it->first.reader_generation) != closed_set.end()) {
        it = image_cache_.erase(it);
      } else {
        ++it;
      }
    }
  }
}

sk_sp<flutter::DlImage> ImageExternalTexture::FindCachedImage(
    uint64_t reader_generation,
    std::optional<HardwareBufferKey> key) {
  if (!key.has_value()) {
    return nullptr;
  }
  auto found = image_cache_.find(ImageCacheKey{
      .reader_generation = reader_generation,
      .hardware_buffer_id = key.value(),
  });
  if (found == image_cache_.end()) {
    return nullptr;
  }
  found->second.last_used_seq = ++use_sequence_;
  return found->second.dl_image;
}

void ImageExternalTexture::CacheImage(uint64_t reader_generation,
                                      std::optional<HardwareBufferKey> key,
                                      sk_sp<flutter::DlImage> image) {
  if (!key.has_value() || !image) {
    return;
  }
  const ImageCacheKey cache_key{
      .reader_generation = reader_generation,
      .hardware_buffer_id = key.value(),
  };
  image_cache_[cache_key] = CacheEntry{
      .dl_image = std::move(image),
      .last_used_seq = ++use_sequence_,
  };
  if (reader_generation == 0) {
    EnforceLegacyGenerationZeroCap();
  }
}

void ImageExternalTexture::EnforceLegacyGenerationZeroCap() {
  size_t gen_zero_count = 0;
  for (const auto& [k, _] : image_cache_) {
    if (k.reader_generation == 0) {
      ++gen_zero_count;
    }
  }
  while (gen_zero_count > kLegacyJavaCacheMaxEntries) {
    auto oldest_it = image_cache_.end();
    for (auto it = image_cache_.begin(); it != image_cache_.end(); ++it) {
      if (it->first.reader_generation != 0) {
        continue;
      }
      if (oldest_it == image_cache_.end() ||
          it->second.last_used_seq < oldest_it->second.last_used_seq) {
        oldest_it = it;
      }
    }
    if (oldest_it == image_cache_.end()) {
      break;
    }
    image_cache_.erase(oldest_it);
    --gen_zero_count;
  }
}

ImageExternalTexture::AcquiredFrame ImageExternalTexture::AcquireLatestFrame() {
  if (ndk_bridge_ && ndk_bridge_->HasReaders()) {
    auto native_frame = ndk_bridge_->AcquireLatestFromReaders();
    if (native_frame.hardware_buffer == nullptr) {
      return {};
    }
    return AcquiredFrame{
        .reader_generation = native_frame.reader_generation,
        .ahardware_buffer = native_frame.hardware_buffer,
        .owned_buffer_ref = std::move(native_frame.owned_buffer_ref),
        .acquire_fence_fd = std::move(native_frame.acquire_fence_fd),
        .java_hardware_buffer = JavaLocalRef(),
    };
  }

  if (image_texture_entry_.is_null() || !jni_facade_) {
    return {};
  }
  JavaLocalRef image = AcquireLatestImage();
  if (image.is_null()) {
    return {};
  }
  JavaLocalRef hardware_buffer = HardwareBufferFor(image);
  if (hardware_buffer.is_null()) {
    return {};
  }
  AHardwareBuffer* ahb = AHardwareBufferFor(hardware_buffer);
  if (ahb == nullptr) {
    CloseHardwareBuffer(hardware_buffer);
    return {};
  }
  return AcquiredFrame{
      .reader_generation = 0,
      .ahardware_buffer = ahb,
      .acquire_fence_fd = fml::UniqueFD(),
      .java_hardware_buffer = std::move(hardware_buffer),
  };
}

JavaLocalRef ImageExternalTexture::AcquireLatestImage() {
  JNIEnv* env = fml::jni::AttachCurrentThread();
  FML_CHECK(env != nullptr);
  return jni_facade_->ImageProducerTextureEntryAcquireLatestImage(
      JavaLocalRef(image_texture_entry_));
}

void ImageExternalTexture::CloseImage(const fml::jni::JavaRef<jobject>& image) {
  if (image.obj() == nullptr) {
    return;
  }
  jni_facade_->ImageClose(JavaLocalRef(image));
}

void ImageExternalTexture::CloseHardwareBuffer(
    const fml::jni::JavaRef<jobject>& hardware_buffer) {
  if (hardware_buffer.obj() == nullptr) {
    return;
  }
  jni_facade_->HardwareBufferClose(JavaLocalRef(hardware_buffer));
}

JavaLocalRef ImageExternalTexture::HardwareBufferFor(
    const fml::jni::JavaRef<jobject>& image) {
  if (image.obj() == nullptr) {
    return JavaLocalRef();
  }
  return jni_facade_->ImageGetHardwareBuffer(JavaLocalRef(image));
}

AHardwareBuffer* ImageExternalTexture::AHardwareBufferFor(
    const fml::jni::JavaRef<jobject>& hardware_buffer) {
  JNIEnv* env = fml::jni::AttachCurrentThread();
  FML_CHECK(env != nullptr);
  const auto& proc =
      impeller::android::GetProcTable().AHardwareBuffer_fromHardwareBuffer;
  return proc ? proc(env, hardware_buffer.obj()) : nullptr;
}

}  // namespace flutter
