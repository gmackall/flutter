// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/external_view_embedder/surface_pool.h"

#include <utility>

namespace flutter {

OverlayLayer::OverlayLayer(int id,
                           std::unique_ptr<AndroidSurface> android_surface,
                           std::unique_ptr<Surface> surface)
    : id(id),
      android_surface(std::move(android_surface)),
      surface(std::move(surface)) {};

OverlayLayer::~OverlayLayer() = default;

SurfacePool::SurfacePool(bool use_new_surface_methods)
    : use_new_surface_methods_(use_new_surface_methods) {}

SurfacePool::~SurfacePool() = default;

std::shared_ptr<OverlayLayer> SurfacePool::GetLayer(
    GrDirectContext* gr_context,
    const AndroidContext& android_context,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    const std::shared_ptr<AndroidSurfaceFactory>& surface_factory,
    const fml::RefPtr<fml::TaskRunner>& platform_task_runner,
    bool* out_preempted) {
  if (out_preempted) {
    *out_preempted = false;
  }
  const intptr_t gr_context_key = reinterpret_cast<intptr_t>(gr_context);

  bool destroy_for_frame_size_change = false;
  {
    std::lock_guard lock(mutex_);
    if (requested_frame_size_ != current_frame_size_) {
      if (use_new_surface_methods_) {
        // The overlay surface is persistent, so resize it in place. Nothing
        // else resizes the swapchain: |Surface::AcquireFrame| ignores the size
        // it is handed.
        for (const std::shared_ptr<OverlayLayer>& layer : layers_) {
          layer->android_surface->OnScreenSurfaceResize(requested_frame_size_);
        }
        if (!layers_.empty()) {
          current_frame_size_ = requested_frame_size_;
        }
      } else if (!layers_.empty()) {
        destroy_for_frame_size_change = true;
      }
    }
  }

  if (destroy_for_frame_size_change) {
    // Destroy current layers in the pool if the frame size has changed.
    DestroyLayers(jni_facade, platform_task_runner);
  }

  uint64_t start_generation = 0;
  bool need_new_layer = false;
  {
    std::lock_guard lock(mutex_);
    if (available_layer_index_ >= layers_.size()) {
      need_new_layer = true;
      std::lock_guard state_lock(state_->mutex);
      start_generation = state_->generation;
      ++state_->pending_creations;
    }
  }

  if (need_new_layer) {
    std::unique_ptr<PlatformViewAndroidJNI::OverlayMetadata> java_metadata;
    auto state = state_;
    const bool use_new = use_new_surface_methods_;
    auto create_java_overlay = [&java_metadata, &jni_facade, state,
                                start_generation, use_new]() {
      {
        std::lock_guard state_lock(state->mutex);
        if (state->generation != start_generation) {
          return;
        }
      }
      java_metadata = use_new ? jni_facade->createOverlaySurface2()
                              : jni_facade->FlutterViewCreateOverlaySurface();
      if (java_metadata && java_metadata->window) {
        std::lock_guard state_lock(state->mutex);
        state->has_live_java_overlay = true;
      }
    };

    if (platform_task_runner) {
      const fml::PreemptibleWaitResult wait_result =
          fml::TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
              platform_task_runner, create_java_overlay,
              fml::PreemptionAction::kCancelTask);
      if (wait_result == fml::PreemptibleWaitResult::kPreempted) {
        std::lock_guard state_lock(state_->mutex);
        --state_->pending_creations;
        if (out_preempted) {
          *out_preempted = true;
        }
        return nullptr;
      }
    } else {
      create_java_overlay();
    }

    std::lock_guard lock(mutex_);
    {
      std::lock_guard state_lock(state_->mutex);
      --state_->pending_creations;
      if (state_->generation != start_generation) {
        return nullptr;
      }
      if (!java_metadata || !java_metadata->window) {
        FML_LOG(ERROR)
            << "Overlay surface creation returned a null native window.";
        return nullptr;
      }
    }

    std::unique_ptr<AndroidSurface> android_surface =
        surface_factory->CreateSurface();

    FML_CHECK(android_surface && android_surface->IsValid())
        << "Could not create an OpenGL, Vulkan or Software surface to set up "
           "rendering.";

    android_surface->SetNativeWindow(java_metadata->window, jni_facade);
    android_surface->SetupImpellerSurface();

    std::unique_ptr<Surface> surface =
        android_surface->CreateGPUSurface(gr_context);

    std::shared_ptr<OverlayLayer> layer =
        std::make_shared<OverlayLayer>(java_metadata->id,           //
                                       std::move(android_surface),  //
                                       std::move(surface)           //
        );
    layer->gr_context_key = gr_context_key;
    layers_.push_back(layer);

    available_layer_index_++;
    current_frame_size_ = requested_frame_size_;
    return layer;
  }

  std::lock_guard lock(mutex_);
  if (available_layer_index_ >= layers_.size()) {
    return nullptr;
  }
  std::shared_ptr<OverlayLayer> layer = layers_[available_layer_index_];
  // Since the surfaces are recycled, it's possible that the GrContext is
  // different.
  if (gr_context_key != layer->gr_context_key) {
    layer->gr_context_key = gr_context_key;
    // The overlay already exists, but the GrContext was changed so we need to
    // recreate the rendering surface with the new GrContext.
    std::unique_ptr<Surface> surface =
        layer->android_surface->CreateGPUSurface(gr_context);
    layer->surface = std::move(surface);
  }
  available_layer_index_++;
  current_frame_size_ = requested_frame_size_;
  return layer;
}

void SurfacePool::RecycleLayers() {
  std::lock_guard lock(mutex_);
  available_layer_index_ = 0;
}

bool SurfacePool::HasLayers() {
  std::lock_guard lock(mutex_);
  return !layers_.empty();
}

void SurfacePool::DestroyLayers(
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    const fml::RefPtr<fml::TaskRunner>& platform_task_runner) {
  std::vector<std::shared_ptr<OverlayLayer>> layers_to_destroy;
  uint64_t destroy_gen = 0;
  bool should_destroy_java_overlays = false;
  {
    std::lock_guard lock(mutex_);
    layers_to_destroy = std::move(layers_);
    layers_.clear();
    available_layer_index_ = 0;
    std::lock_guard state_lock(state_->mutex);
    destroy_gen = ++state_->generation;
    should_destroy_java_overlays = !layers_to_destroy.empty() ||
                                   state_->pending_creations > 0 ||
                                   state_->has_live_java_overlay;
  }
  layers_to_destroy.clear();

  if (!should_destroy_java_overlays) {
    return;
  }

  auto destroy_java_overlays = [state = state_, jni_facade,
                                use_new = use_new_surface_methods_,
                                destroy_gen]() {
    {
      std::lock_guard state_lock(state->mutex);
      if (!state->has_live_java_overlay || state->generation > destroy_gen) {
        return;
      }
      state->has_live_java_overlay = false;
    }
    if (use_new) {
      jni_facade->destroyOverlaySurface2();
    } else {
      jni_facade->FlutterViewDestroyOverlaySurfaces();
    }
  };

  if (platform_task_runner) {
    // DestroyLayers uses kRunAsynchronously so that if the platform thread is
    // currently blocked waiting on the raster thread, the raster thread does
    // not deadlock waiting for the platform thread, while still ensuring the
    // Java overlay views are destroyed once the platform thread unblocks.
    (void)fml::TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        platform_task_runner, destroy_java_overlays,
        fml::PreemptionAction::kRunAsynchronously);
  } else {
    destroy_java_overlays();
  }
}

std::vector<std::shared_ptr<OverlayLayer>> SurfacePool::GetUnusedLayers() {
  std::lock_guard lock(mutex_);
  std::vector<std::shared_ptr<OverlayLayer>> results;
  for (size_t i = available_layer_index_; i < layers_.size(); i++) {
    results.push_back(layers_[i]);
  }
  return results;
}

void SurfacePool::SetFrameSize(DlISize frame_size) {
  std::lock_guard lock(mutex_);
  requested_frame_size_ = frame_size;
}

void SurfacePool::ResetLayers() {
  std::lock_guard lock(mutex_);
  available_layer_index_ = 0;
}

void SurfacePool::TrimLayers() {
  std::lock_guard lock(mutex_);
  layers_.erase(layers_.begin() + available_layer_index_, layers_.end());
  available_layer_index_ = 0;
}

}  // namespace flutter
