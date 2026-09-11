// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/surface/android_output_producer.h"

#include <atomic>

#include "flutter/fml/build_config.h"
#include "flutter/fml/logging.h"

#if FML_OS_ANDROID
#include <android/native_window.h>

#include "flutter/fml/native_library.h"
#endif  // FML_OS_ANDROID

namespace flutter {

namespace {

uint64_t NextProducerId() {
  static std::atomic<uint64_t> next_id{1};
  return next_id.fetch_add(1, std::memory_order_relaxed);
}

#if FML_OS_ANDROID
// ASurfaceControl_acquire was introduced in API 31 and ASurfaceControl_release
// in API 29. Both are resolved at runtime so that this library keeps no
// load-time dependency on symbols newer than the minimum supported API level.
struct SurfaceControlRefProcs {
  using Proc = void (*)(ASurfaceControl*);
  // Keeps the library, and therefore the resolved procs, alive.
  fml::RefPtr<fml::NativeLibrary> library;
  Proc acquire = nullptr;
  Proc release = nullptr;

  bool IsValid() const { return acquire != nullptr && release != nullptr; }
};

const SurfaceControlRefProcs& GetSurfaceControlRefProcs() {
  static const SurfaceControlRefProcs procs = []() {
    SurfaceControlRefProcs result;
    auto library = fml::NativeLibrary::Create("libandroid.so");
    if (!library) {
      return result;
    }
    result.library = library;
    result.acquire =
        library->ResolveFunction<SurfaceControlRefProcs::Proc>(
                   "ASurfaceControl_acquire")
            .value_or(nullptr);
    result.release =
        library->ResolveFunction<SurfaceControlRefProcs::Proc>(
                   "ASurfaceControl_release")
            .value_or(nullptr);
    return result;
  }();
  return procs;
}
#endif  // FML_OS_ANDROID

}  // namespace

std::shared_ptr<const AndroidOutputProducer>
AndroidOutputProducer::MakeForNativeWindow(ANativeWindow* window) {
  if (window == nullptr) {
    return nullptr;
  }
#if FML_OS_ANDROID
  ANativeWindow_acquire(window);
  return std::shared_ptr<const AndroidOutputProducer>(
      new AndroidOutputProducer(Kind::kNativeWindow, window,
                                /*retained=*/true));
#else   // FML_OS_ANDROID
  return nullptr;
#endif  // FML_OS_ANDROID
}

std::shared_ptr<const AndroidOutputProducer>
AndroidOutputProducer::MakeForSurfaceControl(ASurfaceControl* surface_control) {
  if (surface_control == nullptr) {
    return nullptr;
  }
#if FML_OS_ANDROID
  const auto& procs = GetSurfaceControlRefProcs();
  if (!procs.IsValid()) {
    // Without a way to retain the control, a hint session could outlive the
    // handle it was associated with. Report no producer instead.
    return nullptr;
  }
  procs.acquire(surface_control);
  return std::shared_ptr<const AndroidOutputProducer>(
      new AndroidOutputProducer(Kind::kSurfaceControl, surface_control,
                                /*retained=*/true));
#else   // FML_OS_ANDROID
  return nullptr;
#endif  // FML_OS_ANDROID
}

std::shared_ptr<const AndroidOutputProducer>
AndroidOutputProducer::MakeForTesting(Kind kind, void* handle) {
  return std::shared_ptr<const AndroidOutputProducer>(
      new AndroidOutputProducer(kind, handle, /*retained=*/false));
}

AndroidOutputProducer::AndroidOutputProducer(Kind kind,
                                             void* handle,
                                             bool retained)
    : kind_(kind),
      id_(NextProducerId()),
      handle_(handle),
      retained_(retained) {}

AndroidOutputProducer::~AndroidOutputProducer() {
#if FML_OS_ANDROID
  if (!retained_ || handle_ == nullptr) {
    return;
  }
  switch (kind_) {
    case Kind::kNativeWindow:
      ANativeWindow_release(static_cast<ANativeWindow*>(handle_));
      break;
    case Kind::kSurfaceControl: {
      const auto& procs = GetSurfaceControlRefProcs();
      FML_DCHECK(procs.IsValid());
      if (procs.IsValid()) {
        procs.release(static_cast<ASurfaceControl*>(handle_));
      }
      break;
    }
  }
#endif  // FML_OS_ANDROID
}

ANativeWindow* AndroidOutputProducer::window() const {
  return kind_ == Kind::kNativeWindow ? static_cast<ANativeWindow*>(handle_)
                                      : nullptr;
}

ASurfaceControl* AndroidOutputProducer::surface_control() const {
  return kind_ == Kind::kSurfaceControl
             ? static_cast<ASurfaceControl*>(handle_)
             : nullptr;
}

}  // namespace flutter
