// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_API_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_API_H_

#include <sys/types.h>

#include <cstdint>
#include <memory>
#include <vector>

// Opaque NDK handle types. Forward declared so that fakes and tests never need
// the NDK declarations.
struct ANativeWindow;
struct APerformanceHintManager;
struct APerformanceHintSession;
struct ASurfaceControl;

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      The subset of the Android Performance Hint (ADPF) NDK API that
///             the engine uses, behind an injectable interface.
///
///             The interface is deliberately narrow. It exposes graphics
///             pipeline sessions with automatic CPU and GPU timing and the
///             frame-rate plumbing those sessions require, and nothing else.
///             In particular there are no manual reporting entry points
///             (`APerformanceHint_reportActualWorkDuration`) and no target
///             entry points (`APerformanceHint_updateTargetWorkDuration`,
///             `ASessionCreationConfig_setTargetWorkDurationNanos`): the
///             engine never supplies its own timing to a session.
///
///             The platform implementation resolves every entry point at
///             runtime from `libandroid.so`. Nothing newer than the engine's
///             minimum API level is referenced at load time.
///
class AndroidPerformanceHintApi {
 public:
  /// Mirrors `APerformanceHintFeature`, API 36.
  enum class Feature : int32_t {
    kSessions = 0,
    kPowerEfficiency = 1,
    kSurfaceBinding = 2,
    kGraphicsPipeline = 3,
    kAutoCpu = 4,
    kAutoGpu = 5,
  };

  /// The complete description of a session to create. Corresponds to an
  /// `ASessionCreationConfig` whose target work duration is left unset (zero):
  /// a fully automatic session has no manually derived budget.
  struct SessionConfig {
    std::vector<pid_t> tids;
    std::vector<ANativeWindow*> windows;
    std::vector<ASurfaceControl*> surface_controls;
    bool graphics_pipeline = false;
    bool auto_cpu_timing = false;
    bool auto_gpu_timing = false;
  };

  struct SessionCreationResult {
    /// 0 on success, otherwise an errno value as documented for
    /// `APerformanceHint_createSessionUsingConfig`: EINVAL, EPIPE, ENOTSUP or
    /// EBUSY. On EBUSY the platform still returns a session, which the caller
    /// owns and must close.
    int error = 0;
    APerformanceHintSession* session = nullptr;
  };

  virtual ~AndroidPerformanceHintApi();

  //----------------------------------------------------------------------------
  /// @brief      Creates the wrapper backed by the platform's `libandroid.so`.
  ///             Never returns nullptr; `IsAvailable()` reports whether every
  ///             required entry point was resolved.
  ///
  static std::shared_ptr<AndroidPerformanceHintApi> CreateForPlatform();

  /// Whether every entry point this interface needs is present.
  virtual bool IsAvailable() const = 0;

  /// `APerformanceHint_isFeatureSupported`, API 36.
  virtual bool IsFeatureSupported(Feature feature) const = 0;

  /// `APerformanceHint_getManager`, API 33. May return nullptr.
  virtual APerformanceHintManager* GetManager() = 0;

  /// `APerformanceHint_getMaxGraphicsPipelineThreadsCount`, API 36.
  virtual int GetMaxGraphicsPipelineThreadsCount(
      APerformanceHintManager* manager) = 0;

  /// Creates an `ASessionCreationConfig` from `config`, creates the session
  /// with `APerformanceHint_createSessionUsingConfig`, and releases the
  /// configuration object on every path.
  virtual SessionCreationResult CreateSession(APerformanceHintManager* manager,
                                              const SessionConfig& config) = 0;

  /// `APerformanceHint_setNativeSurfaces`, API 36. Replaces the complete set
  /// of outputs associated with the session. Returns 0 on success, otherwise
  /// EPIPE or ENOTSUP.
  virtual int SetNativeSurfaces(
      APerformanceHintSession* session,
      const std::vector<ANativeWindow*>& windows,
      const std::vector<ASurfaceControl*>& surface_controls) = 0;

  /// `APerformanceHint_closeSession`, API 33.
  virtual void CloseSession(APerformanceHintSession* session) = 0;

  //----------------------------------------------------------------------------
  /// @brief      Publishes the intended frame rate of a window producer with
  ///             `ANativeWindow_setFrameRateWithChangeStrategy` (API 31),
  ///             default compatibility and seamless-only changes. A rate of 0
  ///             withdraws the vote.
  ///
  /// @return     `true` if the platform accepted the call.
  ///
  virtual bool SetNativeWindowFrameRate(ANativeWindow* window,
                                        float frame_rate) = 0;

  //----------------------------------------------------------------------------
  /// @brief      Publishes the intended frame rate of a surface control
  ///             producer through a standalone transaction with
  ///             `ASurfaceTransaction_setFrameRateWithChangeStrategy`
  ///             (API 31), default compatibility and seamless-only changes. A
  ///             rate of 0 withdraws the vote.
  ///
  /// @return     `true` if the transaction was applied.
  ///
  virtual bool SetSurfaceControlFrameRate(ASurfaceControl* surface_control,
                                          float frame_rate) = 0;
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_PERFORMANCE_HINT_API_H_
