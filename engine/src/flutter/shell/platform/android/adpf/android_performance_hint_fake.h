// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_FAKE_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_FAKE_H_

#include <cerrno>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <set>
#include <vector>

#include "flutter/shell/platform/android/adpf/android_performance_hint_api.h"

namespace flutter {
namespace testing {

//------------------------------------------------------------------------------
/// @brief      A deterministic, in-memory `AndroidPerformanceHintApi`. Records
///             every call so tests can assert on exactly what the platform
///             would have seen, and lets tests script failures.
///
///             Thread-safe: the controller calls it on the management
///             sequence while tests inspect it from the test thread.
///
class FakePerformanceHintApi final : public AndroidPerformanceHintApi {
 public:
  struct FakeSession {
    uint64_t id = 0;
    SessionConfig config;
    bool closed = false;
    /// Every `SetNativeSurfaces` replacement applied to this session.
    std::vector<std::pair<std::vector<ANativeWindow*>,
                          std::vector<ASurfaceControl*>>>
        surface_updates;
  };

  struct FrameRateVote {
    void* handle = nullptr;
    bool is_surface_control = false;
    float frame_rate = 0;
  };

  FakePerformanceHintApi() {
    for (auto feature :
         {Feature::kSessions, Feature::kPowerEfficiency,
          Feature::kSurfaceBinding, Feature::kGraphicsPipeline,
          Feature::kAutoCpu, Feature::kAutoGpu}) {
      supported_features_.insert(feature);
    }
  }

  ~FakePerformanceHintApi() override = default;

  // Scripting ---------------------------------------------------------------

  void set_available(bool available) {
    std::scoped_lock lock(mutex_);
    available_ = available;
  }

  void set_feature_supported(Feature feature, bool supported) {
    std::scoped_lock lock(mutex_);
    if (supported) {
      supported_features_.insert(feature);
    } else {
      supported_features_.erase(feature);
    }
  }

  void set_manager_available(bool available) {
    std::scoped_lock lock(mutex_);
    manager_available_ = available;
  }

  void set_max_graphics_pipeline_threads(int count) {
    std::scoped_lock lock(mutex_);
    max_graphics_pipeline_threads_ = count;
  }

  /// The errno the next `CreateSession` calls return. A queue: each entry is
  /// consumed by one call; an empty queue means success. On EBUSY a session
  /// is still handed out, as the platform does.
  void QueueCreationError(int error) {
    std::scoped_lock lock(mutex_);
    creation_errors_.push_back(error);
  }

  /// The errno every `SetNativeSurfaces` call returns.
  void set_surface_update_error(int error) {
    std::scoped_lock lock(mutex_);
    surface_update_error_ = error;
  }

  // Inspection --------------------------------------------------------------

  std::vector<FakeSession> sessions() const {
    std::scoped_lock lock(mutex_);
    std::vector<FakeSession> result;
    for (const auto& [id, session] : sessions_) {
      result.push_back(session);
    }
    return result;
  }

  size_t open_session_count() const {
    std::scoped_lock lock(mutex_);
    size_t count = 0;
    for (const auto& [id, session] : sessions_) {
      if (!session.closed) {
        count++;
      }
    }
    return count;
  }

  size_t created_session_count() const {
    std::scoped_lock lock(mutex_);
    return sessions_.size();
  }

  std::vector<FrameRateVote> frame_rate_votes() const {
    std::scoped_lock lock(mutex_);
    return frame_rate_votes_;
  }

  int feature_queries() const {
    std::scoped_lock lock(mutex_);
    return feature_queries_;
  }

  // |AndroidPerformanceHintApi| ---------------------------------------------

  bool IsAvailable() const override {
    std::scoped_lock lock(mutex_);
    return available_;
  }

  bool IsFeatureSupported(Feature feature) const override {
    std::scoped_lock lock(mutex_);
    feature_queries_++;
    return supported_features_.count(feature) > 0;
  }

  APerformanceHintManager* GetManager() override {
    std::scoped_lock lock(mutex_);
    return manager_available_ ? reinterpret_cast<APerformanceHintManager*>(
                                    &manager_available_)
                              : nullptr;
  }

  int GetMaxGraphicsPipelineThreadsCount(
      APerformanceHintManager* manager) override {
    std::scoped_lock lock(mutex_);
    return max_graphics_pipeline_threads_;
  }

  SessionCreationResult CreateSession(APerformanceHintManager* manager,
                                      const SessionConfig& config) override {
    std::scoped_lock lock(mutex_);
    SessionCreationResult result;
    if (!creation_errors_.empty()) {
      result.error = creation_errors_.front();
      creation_errors_.erase(creation_errors_.begin());
    }
    if (result.error == 0 || result.error == EBUSY) {
      FakeSession session;
      session.id = next_session_id_++;
      session.config = config;
      sessions_[session.id] = session;
      result.session = reinterpret_cast<APerformanceHintSession*>(
          static_cast<uintptr_t>(session.id));
    }
    return result;
  }

  int SetNativeSurfaces(
      APerformanceHintSession* session,
      const std::vector<ANativeWindow*>& windows,
      const std::vector<ASurfaceControl*>& surface_controls) override {
    std::scoped_lock lock(mutex_);
    if (surface_update_error_ != 0) {
      return surface_update_error_;
    }
    auto& fake = sessions_.at(reinterpret_cast<uintptr_t>(session));
    fake.surface_updates.emplace_back(windows, surface_controls);
    fake.config.windows = windows;
    fake.config.surface_controls = surface_controls;
    return 0;
  }

  void CloseSession(APerformanceHintSession* session) override {
    std::scoped_lock lock(mutex_);
    sessions_.at(reinterpret_cast<uintptr_t>(session)).closed = true;
  }

  bool SetNativeWindowFrameRate(ANativeWindow* window,
                                float frame_rate) override {
    std::scoped_lock lock(mutex_);
    frame_rate_votes_.push_back({window, false, frame_rate});
    return true;
  }

  bool SetSurfaceControlFrameRate(ASurfaceControl* surface_control,
                                  float frame_rate) override {
    std::scoped_lock lock(mutex_);
    frame_rate_votes_.push_back({surface_control, true, frame_rate});
    return true;
  }

 private:
  mutable std::mutex mutex_;
  bool available_ = true;
  bool manager_available_ = true;
  int max_graphics_pipeline_threads_ = 5;
  std::set<Feature> supported_features_;
  std::vector<int> creation_errors_;
  int surface_update_error_ = 0;
  uint64_t next_session_id_ = 1;
  std::map<uint64_t, FakeSession> sessions_;
  std::vector<FrameRateVote> frame_rate_votes_;
  mutable int feature_queries_ = 0;
};

}  // namespace testing
}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_FAKE_H_
