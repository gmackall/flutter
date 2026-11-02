// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_COMMON_GRAPHICS_TEXTURE_H_
#define FLUTTER_COMMON_GRAPHICS_TEXTURE_H_

#include <cstdint>
#include <map>

#include "flutter/display_list/dl_canvas.h"
#include "flutter/fml/closure.h"
#include "flutter/fml/macros.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/time/time_delta.h"
#include "third_party/skia/include/core/SkSize.h"

class GrDirectContext;

namespace impeller {
class AiksContext;
};

namespace flutter {

struct TextureSyncRequirement {
  static constexpr fml::TimeDelta kDefaultMaxWait =
      fml::TimeDelta::FromMicroseconds(16667);
  static constexpr fml::TimeDelta kMaxAllowedWait =
      fml::TimeDelta::FromMicroseconds(33334);

  int64_t texture_id = -1;
  uint64_t min_frame_generation = 0;
  SkISize expected_physical_size = SkISize::MakeEmpty();
  fml::TimeDelta max_wait = kDefaultMaxWait;

  fml::TimeDelta ClampedMaxWait() const {
    if (max_wait <= fml::TimeDelta::Zero()) {
      return fml::TimeDelta::Zero();
    }
    return max_wait < kMaxAllowedWait ? max_wait : kMaxAllowedWait;
  }

  bool IsActive() const {
    return texture_id >= 0 &&
           (min_frame_generation > 0 || !expected_physical_size.isEmpty());
  }

  bool operator==(const TextureSyncRequirement& other) const {
    return texture_id == other.texture_id &&
           min_frame_generation == other.min_frame_generation &&
           expected_physical_size == other.expected_physical_size &&
           max_wait == other.max_wait;
  }

  bool operator!=(const TextureSyncRequirement& other) const {
    return !(*this == other);
  }
};

class ContextListener {
 public:
  ContextListener();
  ~ContextListener();

  // Called from raster thread.
  virtual void OnGrContextCreated() = 0;

  // Called from raster thread.
  virtual void OnGrContextDestroyed() = 0;

 private:
  FML_DISALLOW_COPY_AND_ASSIGN(ContextListener);
};

class Texture : public ContextListener {
 public:
  struct PaintContext {
    DlCanvas* canvas = nullptr;
    GrDirectContext* gr_context = nullptr;
    impeller::AiksContext* aiks_context = nullptr;
    const DlPaint* paint = nullptr;
  };

  explicit Texture(int64_t id);  // Called from UI or raster thread.
  virtual ~Texture();            // Called from raster thread.

  // Called from raster thread.
  virtual void Paint(PaintContext& context,
                     const DlRect& bounds,
                     bool freeze,
                     const DlImageSampling sampling) = 0;

  // Called on raster thread.
  virtual void MarkNewFrameAvailable() = 0;

  // Whether MarkNewFrameAvailable() performs work on the raster thread.
  // Textures whose MarkNewFrameAvailable() is a no-op (e.g. Android
  // ImageExternalTexture, which pulls the latest buffer during Paint()) can
  // return false so Shell::OnPlatformViewMarkTextureFrameAvailable avoids
  // posting a redundant wakeup task to the raster thread on every frame.
  virtual bool NeedsMarkNewFrameAvailable() const { return true; }

  // Called on raster thread.
  virtual void OnTextureUnregistered() = 0;

  virtual uint64_t GetCurrentFrameGeneration() const { return 0; }

  virtual SkISize GetCurrentFrameSize() const { return SkISize::MakeEmpty(); }

  virtual bool SatisfiesSyncRequirement(uint64_t min_generation,
                                        SkISize expected_size) const {
    return true;
  }

  virtual uint64_t RegisterSyncReadyCallback(uint64_t min_generation,
                                             SkISize expected_size,
                                             fml::closure on_ready) {
    return 0;
  }

  virtual void CancelSyncReadyCallback(uint64_t token) {}

  int64_t Id() { return id_; }

 private:
  int64_t id_;
  FML_DISALLOW_COPY_AND_ASSIGN(Texture);
};

class TextureRegistry {
 public:
  TextureRegistry();

  // Called from raster thread.
  void RegisterTexture(const std::shared_ptr<Texture>& texture);

  // Called from raster thread.
  void RegisterContextListener(uintptr_t id,
                               std::weak_ptr<ContextListener> image);

  // Called from raster thread.
  void UnregisterTexture(int64_t id);

  // Called from the raster thread.
  void UnregisterContextListener(uintptr_t id);

  // Called from raster thread.
  std::shared_ptr<Texture> GetTexture(int64_t id);

  // Called from raster thread.
  void OnGrContextCreated();

  // Called from raster thread.
  void OnGrContextDestroyed();

 private:
  std::map<int64_t, std::shared_ptr<Texture>> mapping_;
  size_t image_counter_ = 0;
  // This map keeps track of registered context listeners by their own
  // externally provided id. It indexes into ordered_images_.
  std::map<uintptr_t, size_t> image_indices_;
  // This map makes sure that iteration of images happens in insertion order
  // (managed by image_counter_) so that images which depend on other images get
  // re-created in the right order.
  using InsertionOrderMap =
      std::map<size_t, std::pair<uintptr_t, std::weak_ptr<ContextListener>>>;
  InsertionOrderMap ordered_images_;

  FML_DISALLOW_COPY_AND_ASSIGN(TextureRegistry);
};

}  // namespace flutter

#endif  // FLUTTER_COMMON_GRAPHICS_TEXTURE_H_
