// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_performance_hint_manager.h"

#include <android/api-level.h>
#include <dlfcn.h>

#include "flutter/fml/logging.h"
#include "flutter/fml/native_library.h"

namespace flutter {

// Opaque handles matching NDK <android/performance_hint.h>
struct APerformanceHintManager;
struct APerformanceHintSession;

using APerformanceHint_getManager_fn = APerformanceHintManager* (*)();
using APerformanceHint_createSession_fn =
    APerformanceHintSession* (*)(APerformanceHintManager*,
                                 const int32_t*,
                                 size_t,
                                 int64_t);
using APerformanceHint_reportActualWorkDuration_fn =
    int (*)(APerformanceHintSession*, int64_t);
using APerformanceHint_updateTargetWorkDuration_fn =
    int (*)(APerformanceHintSession*, int64_t);
using APerformanceHint_closeSession_fn = void (*)(APerformanceHintSession*);

struct AndroidPerformanceHintManager::Impl {
  fml::RefPtr<fml::NativeLibrary> lib_android;
  APerformanceHintSession* session = nullptr;
  int64_t target_duration_ns = 0;

  APerformanceHint_reportActualWorkDuration_fn report_actual_work_duration =
      nullptr;
  APerformanceHint_updateTargetWorkDuration_fn update_target_work_duration =
      nullptr;
  APerformanceHint_closeSession_fn close_session = nullptr;

  ~Impl() {
    if (session && close_session) {
      close_session(session);
      session = nullptr;
    }
  }
};

std::unique_ptr<AndroidPerformanceHintManager>
AndroidPerformanceHintManager::Create(const std::vector<int32_t>& tids,
                                      int64_t target_duration_ns) {
  if (tids.empty() || target_duration_ns <= 0) {
    return nullptr;
  }

  // ADPF PerformanceHintManager was introduced in API level 31 (Android 12).
  if (android_get_device_api_level() < 31) {
    return nullptr;
  }

  auto lib_android = fml::NativeLibrary::Create("libandroid.so");
  if (!lib_android) {
    return nullptr;
  }

  auto get_manager =
      lib_android->ResolveFunction<APerformanceHint_getManager_fn>(
          "APerformanceHint_getManager");
  auto create_session =
      lib_android->ResolveFunction<APerformanceHint_createSession_fn>(
          "APerformanceHint_createSession");
  auto report_actual =
      lib_android
          ->ResolveFunction<APerformanceHint_reportActualWorkDuration_fn>(
              "APerformanceHint_reportActualWorkDuration");
  auto update_target =
      lib_android
          ->ResolveFunction<APerformanceHint_updateTargetWorkDuration_fn>(
              "APerformanceHint_updateTargetWorkDuration");
  auto close_session =
      lib_android->ResolveFunction<APerformanceHint_closeSession_fn>(
          "APerformanceHint_closeSession");

  if (!get_manager.has_value() || !create_session.has_value() ||
      !report_actual.has_value() || !update_target.has_value() ||
      !close_session.has_value()) {
    FML_LOG(WARNING)
        << "ADPF PerformanceHint APIs not available in libandroid.so";
    return nullptr;
  }

  APerformanceHintManager* manager = get_manager.value()();
  if (!manager) {
    FML_LOG(WARNING) << "APerformanceHint_getManager returned nullptr";
    return nullptr;
  }

  APerformanceHintSession* session = create_session.value()(
      manager, tids.data(), tids.size(), target_duration_ns);
  if (!session) {
    FML_LOG(WARNING) << "Failed to create APerformanceHintSession for "
                     << tids.size() << " threads";
    return nullptr;
  }

  auto impl = std::make_unique<Impl>();
  impl->lib_android = std::move(lib_android);
  impl->session = session;
  impl->target_duration_ns = target_duration_ns;
  impl->report_actual_work_duration = report_actual.value();
  impl->update_target_work_duration = update_target.value();
  impl->close_session = close_session.value();

  FML_LOG(INFO) << "Created ADPF PerformanceHintSession with target "
                << target_duration_ns << " ns (" << (1e9 / target_duration_ns)
                << " Hz) for " << tids.size() << " threads";

  return std::unique_ptr<AndroidPerformanceHintManager>(
      new AndroidPerformanceHintManager(std::move(impl)));
}

AndroidPerformanceHintManager::AndroidPerformanceHintManager(
    std::unique_ptr<Impl> impl)
    : impl_(std::move(impl)) {}

AndroidPerformanceHintManager::~AndroidPerformanceHintManager() = default;

void AndroidPerformanceHintManager::ReportActualWorkDuration(
    int64_t actual_duration_ns) {
  if (impl_ && impl_->session && impl_->report_actual_work_duration) {
    impl_->report_actual_work_duration(impl_->session, actual_duration_ns);
  }
}

void AndroidPerformanceHintManager::UpdateTargetWorkDuration(
    int64_t target_duration_ns) {
  if (impl_ && impl_->session && impl_->update_target_work_duration) {
    impl_->target_duration_ns = target_duration_ns;
    impl_->update_target_work_duration(impl_->session, target_duration_ns);
  }
}

int64_t AndroidPerformanceHintManager::GetTargetWorkDuration() const {
  return impl_ ? impl_->target_duration_ns : 0;
}

}  // namespace flutter
