// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_SURFACE_ANDROID_OUTPUT_PRODUCER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_SURFACE_ANDROID_OUTPUT_PRODUCER_H_

#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

#include "flutter/fml/macros.h"

// Opaque NDK handle types. Forward declared so that this header can be used on
// host builds of the Android surface library (unit tests) that do not have the
// NDK headers available.
struct ANativeWindow;
struct ASurfaceControl;

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      A retained reference to one buffer-bearing output that Flutter
///             presents to: either an `ANativeWindow` or an `ASurfaceControl`.
///
///             Instances are created by the `AndroidSurface` that owns the
///             producer, once per producer lifetime, and are shared with the
///             ADPF integration through `std::shared_ptr`. Holding a producer
///             keeps the underlying NDK handle alive, so a hint session can be
///             closed before the handle it was associated with is released.
///
///             Every instance has a process-unique `id()`. Identity is the id,
///             never the handle address: a handle address can be reused by the
///             platform after release, but an id never is.
///
class AndroidOutputProducer {
 public:
  enum class Kind {
    /// The producer is the `ANativeWindow` itself (EGL and KHR swapchains).
    kNativeWindow,
    /// The producer is a child `ASurfaceControl` that receives buffers through
    /// transactions (the AHB swapchain). The parent window is a container and
    /// is not the producer.
    kSurfaceControl,
  };

  //----------------------------------------------------------------------------
  /// @brief      Retains `window` and describes it as the output producer.
  ///             Returns nullptr for a null window.
  ///
  static std::shared_ptr<const AndroidOutputProducer> MakeForNativeWindow(
      ANativeWindow* window);

  //----------------------------------------------------------------------------
  /// @brief      Retains `surface_control` and describes it as the output
  ///             producer. Returns nullptr for a null control, or when the
  ///             platform cannot retain surface controls (API < 31).
  ///
  static std::shared_ptr<const AndroidOutputProducer> MakeForSurfaceControl(
      ASurfaceControl* surface_control);

  //----------------------------------------------------------------------------
  /// @brief      Creates a producer over a fake handle without touching the
  ///             NDK. Tests only.
  ///
  static std::shared_ptr<const AndroidOutputProducer> MakeForTesting(
      Kind kind,
      void* handle);

  ~AndroidOutputProducer();

  Kind kind() const { return kind_; }

  /// A process-unique registration id for this producer instance.
  uint64_t id() const { return id_; }

  /// Valid only when `kind()` is `kNativeWindow`.
  ANativeWindow* window() const;

  /// Valid only when `kind()` is `kSurfaceControl`.
  ASurfaceControl* surface_control() const;

 private:
  const Kind kind_;
  const uint64_t id_;
  void* const handle_;
  const bool retained_;

  AndroidOutputProducer(Kind kind, void* handle, bool retained);

  FML_DISALLOW_COPY_AND_ASSIGN(AndroidOutputProducer);
};

using AndroidOutputProducerList =
    std::vector<std::shared_ptr<const AndroidOutputProducer>>;

//------------------------------------------------------------------------------
/// @brief      Invoked with the complete, current set of live overlay output
///             producers whenever that set changes. Invoked on the platform
///             thread, from within the overlay surface pool.
///
using AndroidOverlayOutputsCallback =
    std::function<void(AndroidOutputProducerList outputs)>;

//------------------------------------------------------------------------------
/// @brief      Callbacks through which the compositor reports workload changes
///             that happen outside the platform view: overlay producer churn
///             and raster thread migration. Every member is optional.
///
struct AndroidWorkloadCallbacks {
  AndroidOverlayOutputsCallback on_overlay_outputs_changed;

  /// Invoked right after the raster task runner was merged into, or unmerged
  /// from, the platform thread. Must not block.
  std::function<void()> on_raster_thread_configuration_changed;
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_SURFACE_ANDROID_OUTPUT_PRODUCER_H_
