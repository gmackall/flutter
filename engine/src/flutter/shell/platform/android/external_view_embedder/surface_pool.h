// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_SURFACE_POOL_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_SURFACE_POOL_H_

#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "flutter/flow/surface.h"
#include "flutter/fml/task_runner.h"
#include "flutter/shell/platform/android/context/android_context.h"
#include "flutter/shell/platform/android/surface/android_surface.h"

namespace flutter {

//------------------------------------------------------------------------------
/// An Overlay layer represents an `android.view.View` in the C side.
///
/// The `id` is used to uniquely identify the layer and recycle it between
/// frames.
///
struct OverlayLayer {
  OverlayLayer(int id,
               std::unique_ptr<AndroidSurface> android_surface,
               std::unique_ptr<Surface> surface);

  ~OverlayLayer();

  // A unique id to identify the overlay when it gets recycled.
  const int id;

  // A GPU surface.
  const std::unique_ptr<AndroidSurface> android_surface;

  // A GPU surface. This may change when the overlay is recycled.
  std::unique_ptr<Surface> surface;

  // The `GrContext` that is currently used by the overlay surfaces.
  // We track this to know when the GrContext for the Flutter app has changed
  // so we can update the overlay with the new context.
  //
  // This may change when the overlay is recycled.
  intptr_t gr_context_key = 0;
};

class SurfacePool {
 public:
  explicit SurfacePool(bool use_new_surface_methods);

  ~SurfacePool();

  // Gets a layer from the pool if available, or allocates a new one.
  // Finally, it marks the layer as used. That is, it increments
  // `available_layer_index_`.
  //
  // If `platform_task_runner` is non-null and a new overlay surface must be
  // allocated, the Java overlay creation is dispatched to
  // `platform_task_runner` using a preemptible wait. If that wait is preempted
  // because the platform thread is in (or enters) a blocking synchronization
  // wait, or if the pool's layers are destroyed concurrently while allocating
  // the surface, this method returns `nullptr`. If `out_preempted` is non-null,
  // `*out_preempted` is set to true if and only if the wait was preempted.
  //
  // CONTRACT: Every caller MUST handle a `nullptr` return value and safely
  // skip overlay rendering for the current frame.
  [[nodiscard]] std::shared_ptr<OverlayLayer> GetLayer(
      GrDirectContext* gr_context,
      const AndroidContext& android_context,
      const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
      const std::shared_ptr<AndroidSurfaceFactory>& surface_factory,
      const fml::RefPtr<fml::TaskRunner>& platform_task_runner = nullptr,
      bool* out_preempted = nullptr);

  // Gets the layers in the pool that aren't currently used.
  // This method doesn't mark the layers as unused.
  std::vector<std::shared_ptr<OverlayLayer>> GetUnusedLayers();

  // Marks the layers in the pool as available for reuse.
  void RecycleLayers();

  // Destroys all the layers in the pool.
  //
  // If `platform_task_runner` is non-null, the Java overlay destruction is
  // dispatched to `platform_task_runner` using
  // `fml::PreemptionAction::kRunAsynchronously`, so `DestroyLayers` does not
  // deadlock if the platform thread is blocked waiting on the raster thread,
  // while still ensuring the Java overlay views are destroyed once the
  // platform thread unblocks.
  void DestroyLayers(
      const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
      const fml::RefPtr<fml::TaskRunner>& platform_task_runner = nullptr);

  // Sets the frame size used by the layers in the pool.
  // If the current layers in the pool have a different frame size,
  // then they are deallocated as soon as |GetLayer| is called.
  void SetFrameSize(DlISize frame_size);

  // Returns true if the current pool has layers in use.
  bool HasLayers();

  // Reset the layer index but do not release any layers.
  void ResetLayers();

  void TrimLayers();

 private:
  struct SharedPoolState {
    std::mutex mutex;
    uint64_t generation = 0;
    size_t pending_creations = 0;
    bool has_live_java_overlay = false;
  };

  // The index of the entry in the layers_ vector that determines the beginning
  // of the unused layers. For example, consider the following vector:
  //  _____
  //  | 0 |
  //  |---|
  //  | 1 | <-- `available_layer_index_`
  //  |---|
  //  | 2 |
  //  |---|
  //
  //  This indicates that entries starting from 1 can be reused meanwhile the
  //  entry at position 0 cannot be reused.
  size_t available_layer_index_ = 0;

  // The layers in the pool.
  std::vector<std::shared_ptr<OverlayLayer>> layers_;

  // The frame size of the layers in the pool.
  DlISize current_frame_size_;

  // The frame size to be used by future layers.
  DlISize requested_frame_size_;

  // Used to guard public methods. Never held across cross-thread waits on
  // |platform_task_runner|.
  std::mutex mutex_;
  const bool use_new_surface_methods_ = false;
  const std::shared_ptr<SharedPoolState> state_ =
      std::make_shared<SharedPoolState>();
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_SURFACE_POOL_H_
