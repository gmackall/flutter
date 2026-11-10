// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/image_external_texture.h"

#include <android/hardware_buffer_jni.h>

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
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge,
    std::shared_ptr<IncrementalRetireQueue> retire_queue)
    : Texture(id),
      jni_facade_(jni_facade),
      ndk_bridge_(std::move(ndk_bridge)),
      retire_queue_(retire_queue
                        ? std::move(retire_queue)
                        : std::make_shared<IncrementalRetireQueue>(nullptr)),
      texture_lifecycle_(lifecycle) {
  if (!image_texture_entry.is_null()) {
    image_texture_entry_.Reset(image_texture_entry);
  }
}

ImageExternalTexture::~ImageExternalTexture() = default;

void ImageExternalTexture::SetNdkBridge(
    std::shared_ptr<NdkImageTextureBridge> ndk_bridge) {
  ndk_bridge_ = std::move(ndk_bridge);
}

void ImageExternalTexture::SetRetireQueue(
    std::shared_ptr<IncrementalRetireQueue> retire_queue) {
  retire_queue_ = retire_queue
                      ? std::move(retire_queue)
                      : std::make_shared<IncrementalRetireQueue>(nullptr);
}

// Implementing flutter::Texture.
void ImageExternalTexture::Paint(PaintContext& context,
                                 const DlRect& bounds,
                                 bool freeze,
                                 const DlImageSampling sampling) {
  if (state_ == AttachmentState::kDetached) {
    return;
  }
  Attach(context);
  DrainPendingEvictions();
  const bool should_process_frame = !freeze;
  if (should_process_frame) {
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

// Implementing flutter::Texture.
void ImageExternalTexture::MarkNewFrameAvailable() {
  // NOOP.
}

// Implementing flutter::Texture.
void ImageExternalTexture::OnTextureUnregistered() {
  std::vector<IncrementalRetireQueue::RetiredImageEntry> to_retire;
  to_retire.reserve(image_cache_.size() + 1u);
  if (dl_image_ || current_dl_image_ahb_ref_) {
    to_retire.push_back(IncrementalRetireQueue::RetiredImageEntry{
        .image = std::move(dl_image_),
        .ahb_ref = std::move(current_dl_image_ahb_ref_),
    });
  }
  for (auto& [key, entry] : image_cache_) {
    to_retire.push_back(IncrementalRetireQueue::RetiredImageEntry{
        .image = std::move(entry.dl_image),
        .ahb_ref = std::move(entry.ahb_ref),
    });
  }
  image_cache_.clear();
  failed_import_keys_.clear();
  if (retire_queue_ && !to_retire.empty()) {
    retire_queue_->Retire(std::move(to_retire));
  }
}

void ImageExternalTexture::OnLowMemoryWarning() {
  PurgeCacheExceptCurrent(/*synchronous=*/true);
}

// Implementing flutter::ContextListener.
void ImageExternalTexture::OnGrContextCreated() {
  failed_import_keys_.clear();
  state_ = AttachmentState::kUninitialized;
}

// Implementing flutter::ContextListener.
void ImageExternalTexture::OnGrContextDestroyed() {
  switch (texture_lifecycle_) {
    case ImageLifecycle::kReset:
      dl_image_.reset();
      current_dl_image_ahb_ref_.reset();
      ClearCacheSynchronously();
      break;
    case ImageLifecycle::kKeepAlive:
      // Retain only dl_image_ (1 buffer) so the texture remains visible if the
      // producer does not redraw on resume, and flush all other cached imports
      // synchronously.
      ClearCacheSynchronously();
      break;
  }
  if (state_ == AttachmentState::kAttached) {
    Detach();
  }
  if (retire_queue_) {
    retire_queue_->FlushSynchronously();
  }
  state_ = AttachmentState::kDetached;
}

void ImageExternalTexture::DrainPendingEvictions() {
  if (!ndk_bridge_) {
    return;
  }
  std::vector<NdkImageTextureBridge::BufferEviction> buffers;
  std::vector<uint64_t> closed_generations;
  bool purge_except_current = false;
  ndk_bridge_->TakePendingEvictions(&buffers, &closed_generations,
                                    &purge_except_current);
  if (buffers.empty() && closed_generations.empty() && !purge_except_current) {
    return;
  }

  std::vector<IncrementalRetireQueue::RetiredImageEntry> to_retire;

  for (const auto& item : buffers) {
    const ImageCacheKey key{
        .reader_generation = item.reader_generation,
        .buffer_key = item.buffer_key,
    };
    failed_import_keys_.erase(key);
    auto found = image_cache_.find(key);
    if (found != image_cache_.end()) {
      to_retire.push_back(IncrementalRetireQueue::RetiredImageEntry{
          .image = std::move(found->second.dl_image),
          .ahb_ref = std::move(found->second.ahb_ref),
      });
      image_cache_.erase(found);
    }
  }

  if (!closed_generations.empty()) {
    std::unordered_set<uint64_t> closed_set(closed_generations.begin(),
                                            closed_generations.end());
    for (auto it = failed_import_keys_.begin();
         it != failed_import_keys_.end();) {
      if (closed_set.find(it->reader_generation) != closed_set.end()) {
        it = failed_import_keys_.erase(it);
      } else {
        ++it;
      }
    }
    for (auto it = image_cache_.begin(); it != image_cache_.end();) {
      if (closed_set.find(it->first.reader_generation) != closed_set.end()) {
        to_retire.push_back(IncrementalRetireQueue::RetiredImageEntry{
            .image = std::move(it->second.dl_image),
            .ahb_ref = std::move(it->second.ahb_ref),
        });
        it = image_cache_.erase(it);
      } else {
        ++it;
      }
    }
  }

  if (retire_queue_ && !to_retire.empty()) {
    retire_queue_->Retire(std::move(to_retire));
  }

  if (purge_except_current) {
    PurgeCacheExceptCurrent(/*synchronous=*/true);
  }
}

void ImageExternalTexture::PurgeCacheExceptCurrent(bool synchronous) {
  failed_import_keys_.clear();
  std::vector<IncrementalRetireQueue::RetiredImageEntry> evicted;
  for (auto it = image_cache_.begin(); it != image_cache_.end();) {
    if (dl_image_ != nullptr && it->second.dl_image == dl_image_) {
      ++it;
    } else {
      evicted.push_back(IncrementalRetireQueue::RetiredImageEntry{
          .image = std::move(it->second.dl_image),
          .ahb_ref = std::move(it->second.ahb_ref),
      });
      it = image_cache_.erase(it);
    }
  }
  if (synchronous) {
    evicted.clear();
  } else if (retire_queue_ && !evicted.empty()) {
    retire_queue_->Retire(std::move(evicted));
  }
}

void ImageExternalTexture::ClearCacheSynchronously() {
  failed_import_keys_.clear();
  image_cache_.clear();
}

bool ImageExternalTexture::IsProtectedContent(
    const AHardwareBuffer_Desc& desc) {
  return (desc.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT) != 0;
}

bool ImageExternalTexture::DescriptorsMatch(const AHardwareBuffer_Desc& a,
                                            const AHardwareBuffer_Desc& b) {
  return a.width == b.width && a.height == b.height && a.format == b.format &&
         a.usage == b.usage && a.layers == b.layers;
}

ImageExternalTexture::ImageCacheKey ImageExternalTexture::MakeCacheKey(
    uint64_t reader_generation,
    AHardwareBuffer* buffer,
    bool* out_needs_ahb_retain) const {
  bool used_pointer_key = false;
  const uint64_t buffer_key =
      ComputeHardwareBufferKey(buffer, &used_pointer_key);
  if (out_needs_ahb_retain != nullptr) {
    *out_needs_ahb_retain = used_pointer_key;
  }
  return ImageCacheKey{
      .reader_generation = reader_generation,
      .buffer_key = buffer_key,
  };
}

ImageExternalTexture::CacheEntry* ImageExternalTexture::FindCachedEntry(
    const ImageCacheKey& key,
    const AHardwareBuffer_Desc& desc) {
  if (key.buffer_key == 0) {
    return nullptr;
  }
  auto found = image_cache_.find(key);
  if (found == image_cache_.end()) {
    return nullptr;
  }
  if (!DescriptorsMatch(found->second.desc, desc)) {
    if (retire_queue_) {
      std::vector<IncrementalRetireQueue::RetiredImageEntry> mismatch;
      mismatch.push_back(IncrementalRetireQueue::RetiredImageEntry{
          .image = std::move(found->second.dl_image),
          .ahb_ref = std::move(found->second.ahb_ref),
      });
      image_cache_.erase(found);
      retire_queue_->Retire(std::move(mismatch));
    } else {
      image_cache_.erase(found);
    }
    return nullptr;
  }
  found->second.last_used_seq = ++use_sequence_;
  return &found->second;
}

bool ImageExternalTexture::IsFailedImportKey(const ImageCacheKey& key) const {
  return key.buffer_key != 0 &&
         failed_import_keys_.find(key) != failed_import_keys_.end();
}

void ImageExternalTexture::RecordFailedImport(const ImageCacheKey& key) {
  if (key.buffer_key != 0) {
    failed_import_keys_.insert(key);
  }
}

void ImageExternalTexture::InsertCacheEntry(
    const ImageCacheKey& key,
    const AHardwareBuffer_Desc& desc,
    sk_sp<flutter::DlImage> dl_image,
    std::shared_ptr<ScopedAHardwareBufferRef> ahb_ref,
    std::function<bool(fml::UniqueFD&)> on_new_frame_hit) {
  if (key.buffer_key == 0 || !dl_image || IsProtectedContent(desc)) {
    return;
  }
  failed_import_keys_.erase(key);
  image_cache_[key] = CacheEntry{
      .dl_image = std::move(dl_image),
      .ahb_ref = std::move(ahb_ref),
      .desc = desc,
      .on_new_frame_hit = std::move(on_new_frame_hit),
      .last_used_seq = ++use_sequence_,
  };
  if (key.reader_generation == 0) {
    EnforceLegacyGenerationZeroCap();
  }
}

void ImageExternalTexture::EnforceLegacyGenerationZeroCap() {
  size_t gen_zero_count = 0;
  for (const auto& [k, v] : image_cache_) {
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

void ImageExternalTexture::SetCurrentDlImage(
    sk_sp<flutter::DlImage> dl_image,
    std::shared_ptr<ScopedAHardwareBufferRef> ahb_ref) {
  if (!dl_image) {
    return;
  }
  dl_image_ = std::move(dl_image);
  current_dl_image_ahb_ref_ = std::move(ahb_ref);
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
        .caller_ahb_ref = std::move(native_frame.caller_ahb_ref),
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
      .caller_ahb_ref = nullptr,
      .acquire_fence_fd = fml::UniqueFD(),
      .java_hardware_buffer = std::move(hardware_buffer),
  };
}

JavaLocalRef ImageExternalTexture::AcquireLatestImage() {
  JNIEnv* env = fml::jni::AttachCurrentThread();
  FML_CHECK(env != nullptr);

  // ImageTextureEntry.acquireLatestImage.
  JavaLocalRef image_java =
      jni_facade_->ImageProducerTextureEntryAcquireLatestImage(
          JavaLocalRef(image_texture_entry_));
  return image_java;
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
  // Image.getHardwareBuffer.
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
