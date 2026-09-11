// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/adpf/android_performance_hint_api.h"

#include <android/native_window.h>
#include <android/performance_hint.h>
#include <android/surface_control.h>

#include <cerrno>

#include "flutter/fml/logging.h"
#include "flutter/fml/macros.h"
#include "flutter/fml/native_library.h"
#include "flutter/fml/trace_event.h"

namespace flutter {

namespace {

//------------------------------------------------------------------------------
// Entry points introduced in API 36.
//
// The NDK the engine builds against (r28) does not declare these. The
// signatures below are pinned to the released `android16-release` branch of
// `frameworks/native/include/android/performance_hint.h`, which is also what
// NDK r29 ships. They are ABI facts, not guesses: do not edit them from a
// rolling AOSP header or generated documentation. Once the engine NDK declares
// them, these aliases can be replaced with `decltype` of the declarations.
//
struct ASessionCreationConfig;

using PFN_APerformanceHint_isFeatureSupported = bool (*)(int32_t feature);
using PFN_APerformanceHint_getMaxGraphicsPipelineThreadsCount =
    int (*)(APerformanceHintManager* manager);
using PFN_ASessionCreationConfig_create = ASessionCreationConfig* (*)();
using PFN_ASessionCreationConfig_release =
    void (*)(ASessionCreationConfig* config);
using PFN_ASessionCreationConfig_setTids =
    void (*)(ASessionCreationConfig* config, const pid_t* tids, size_t size);
using PFN_ASessionCreationConfig_setGraphicsPipeline =
    void (*)(ASessionCreationConfig* config, bool enabled);
using PFN_ASessionCreationConfig_setNativeSurfaces =
    void (*)(ASessionCreationConfig* config,
             ANativeWindow** native_windows,
             size_t native_windows_size,
             ASurfaceControl** surface_controls,
             size_t surface_controls_size);
using PFN_ASessionCreationConfig_setUseAutoTiming =
    void (*)(ASessionCreationConfig* config, bool cpu, bool gpu);
using PFN_APerformanceHint_createSessionUsingConfig =
    int (*)(APerformanceHintManager* manager,
            ASessionCreationConfig* config,
            APerformanceHintSession** session_out);
using PFN_APerformanceHint_setNativeSurfaces =
    int (*)(APerformanceHintSession* session,
            ANativeWindow** native_windows,
            size_t native_windows_size,
            ASurfaceControl** surface_controls,
            size_t surface_controls_size);

//------------------------------------------------------------------------------
// Entry points the engine NDK already declares (API 29 to 33). Their types come
// straight from the headers, so a signature drift fails to compile.
//
using PFN_APerformanceHint_getManager = decltype(&APerformanceHint_getManager);
using PFN_APerformanceHint_closeSession =
    decltype(&APerformanceHint_closeSession);
using PFN_ANativeWindow_setFrameRateWithChangeStrategy =
    decltype(&ANativeWindow_setFrameRateWithChangeStrategy);
using PFN_ASurfaceTransaction_create = decltype(&ASurfaceTransaction_create);
using PFN_ASurfaceTransaction_delete = decltype(&ASurfaceTransaction_delete);
using PFN_ASurfaceTransaction_apply = decltype(&ASurfaceTransaction_apply);
using PFN_ASurfaceTransaction_setFrameRateWithChangeStrategy =
    decltype(&ASurfaceTransaction_setFrameRateWithChangeStrategy);

template <class T>
T Resolve(const fml::RefPtr<fml::NativeLibrary>& library, const char* name) {
  auto proc = library->ResolveFunction<T>(name);
  if (!proc.has_value()) {
    FML_DLOG(INFO) << "Android ADPF: " << name << " is not available.";
    return nullptr;
  }
  return proc.value();
}

class NdkPerformanceHintApi final : public AndroidPerformanceHintApi {
 public:
  NdkPerformanceHintApi() {
    auto library = fml::NativeLibrary::Create("libandroid.so");
    if (!library) {
      FML_LOG(WARNING) << "Android ADPF: could not open libandroid.so.";
      return;
    }
#define RESOLVE(name) name##_ = Resolve<PFN_##name>(library, #name)
    RESOLVE(APerformanceHint_isFeatureSupported);
    RESOLVE(APerformanceHint_getMaxGraphicsPipelineThreadsCount);
    RESOLVE(ASessionCreationConfig_create);
    RESOLVE(ASessionCreationConfig_release);
    RESOLVE(ASessionCreationConfig_setTids);
    RESOLVE(ASessionCreationConfig_setGraphicsPipeline);
    RESOLVE(ASessionCreationConfig_setNativeSurfaces);
    RESOLVE(ASessionCreationConfig_setUseAutoTiming);
    RESOLVE(APerformanceHint_createSessionUsingConfig);
    RESOLVE(APerformanceHint_setNativeSurfaces);
    RESOLVE(APerformanceHint_getManager);
    RESOLVE(APerformanceHint_closeSession);
    RESOLVE(ANativeWindow_setFrameRateWithChangeStrategy);
    RESOLVE(ASurfaceTransaction_create);
    RESOLVE(ASurfaceTransaction_delete);
    RESOLVE(ASurfaceTransaction_apply);
    RESOLVE(ASurfaceTransaction_setFrameRateWithChangeStrategy);
#undef RESOLVE
    is_available_ =
        APerformanceHint_isFeatureSupported_ &&
        APerformanceHint_getMaxGraphicsPipelineThreadsCount_ &&
        ASessionCreationConfig_create_ && ASessionCreationConfig_release_ &&
        ASessionCreationConfig_setTids_ &&
        ASessionCreationConfig_setGraphicsPipeline_ &&
        ASessionCreationConfig_setNativeSurfaces_ &&
        ASessionCreationConfig_setUseAutoTiming_ &&
        APerformanceHint_createSessionUsingConfig_ &&
        APerformanceHint_setNativeSurfaces_ && APerformanceHint_getManager_ &&
        APerformanceHint_closeSession_ &&
        ANativeWindow_setFrameRateWithChangeStrategy_ &&
        ASurfaceTransaction_create_ && ASurfaceTransaction_delete_ &&
        ASurfaceTransaction_apply_ &&
        ASurfaceTransaction_setFrameRateWithChangeStrategy_;
    if (is_available_) {
      // Hold the library open for the lifetime of the resolved procs.
      library_ = std::move(library);
    }
  }

  ~NdkPerformanceHintApi() override = default;

  // |AndroidPerformanceHintApi|
  bool IsAvailable() const override { return is_available_; }

  // |AndroidPerformanceHintApi|
  bool IsFeatureSupported(Feature feature) const override {
    if (!is_available_) {
      return false;
    }
    return APerformanceHint_isFeatureSupported_(static_cast<int32_t>(feature));
  }

  // |AndroidPerformanceHintApi|
  APerformanceHintManager* GetManager() override {
    if (!is_available_) {
      return nullptr;
    }
    return APerformanceHint_getManager_();
  }

  // |AndroidPerformanceHintApi|
  int GetMaxGraphicsPipelineThreadsCount(
      APerformanceHintManager* manager) override {
    if (!is_available_ || manager == nullptr) {
      return 0;
    }
    return APerformanceHint_getMaxGraphicsPipelineThreadsCount_(manager);
  }

  // |AndroidPerformanceHintApi|
  SessionCreationResult CreateSession(APerformanceHintManager* manager,
                                      const SessionConfig& config) override {
    SessionCreationResult result;
    if (!is_available_ || manager == nullptr) {
      result.error = ENOTSUP;
      return result;
    }
    TRACE_EVENT0("flutter", "AndroidAdpfCreateSession");
    ASessionCreationConfig* creation_config = ASessionCreationConfig_create_();
    if (creation_config == nullptr) {
      result.error = ENOMEM;
      return result;
    }
    ASessionCreationConfig_setTids_(creation_config, config.tids.data(),
                                    config.tids.size());
    if (config.graphics_pipeline) {
      ASessionCreationConfig_setGraphicsPipeline_(creation_config, true);
    }
    if (!config.windows.empty() || !config.surface_controls.empty()) {
      // The NDK takes non-const arrays of handles but does not modify them.
      auto windows = config.windows;
      auto surface_controls = config.surface_controls;
      ASessionCreationConfig_setNativeSurfaces_(
          creation_config,                                              //
          windows.empty() ? nullptr : windows.data(),                   //
          windows.size(),                                               //
          surface_controls.empty() ? nullptr : surface_controls.data(),  //
          surface_controls.size());
    }
    if (config.auto_cpu_timing || config.auto_gpu_timing) {
      ASessionCreationConfig_setUseAutoTiming_(
          creation_config, config.auto_cpu_timing, config.auto_gpu_timing);
    }
    // The target work duration is intentionally left at its default (zero).
    // With automatic timing the platform measures the pipeline itself.
    APerformanceHintSession* session = nullptr;
    result.error = APerformanceHint_createSessionUsingConfig_(
        manager, creation_config, &session);
    result.session = session;
    ASessionCreationConfig_release_(creation_config);
    return result;
  }

  // |AndroidPerformanceHintApi|
  int SetNativeSurfaces(
      APerformanceHintSession* session,
      const std::vector<ANativeWindow*>& windows,
      const std::vector<ASurfaceControl*>& surface_controls) override {
    if (!is_available_ || session == nullptr) {
      return ENOTSUP;
    }
    TRACE_EVENT0("flutter", "AndroidAdpfSetNativeSurfaces");
    auto mutable_windows = windows;
    auto mutable_controls = surface_controls;
    return APerformanceHint_setNativeSurfaces_(
        session,                                                       //
        mutable_windows.empty() ? nullptr : mutable_windows.data(),    //
        mutable_windows.size(),                                        //
        mutable_controls.empty() ? nullptr : mutable_controls.data(),  //
        mutable_controls.size());
  }

  // |AndroidPerformanceHintApi|
  void CloseSession(APerformanceHintSession* session) override {
    if (!is_available_ || session == nullptr) {
      return;
    }
    TRACE_EVENT0("flutter", "AndroidAdpfCloseSession");
    APerformanceHint_closeSession_(session);
  }

  // |AndroidPerformanceHintApi|
  bool SetNativeWindowFrameRate(ANativeWindow* window,
                                float frame_rate) override {
    if (!is_available_ || window == nullptr) {
      return false;
    }
    return ANativeWindow_setFrameRateWithChangeStrategy_(
               window, frame_rate,
               ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT,
               ANATIVEWINDOW_CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS) == 0;
  }

  // |AndroidPerformanceHintApi|
  bool SetSurfaceControlFrameRate(ASurfaceControl* surface_control,
                                  float frame_rate) override {
    if (!is_available_ || surface_control == nullptr) {
      return false;
    }
    ASurfaceTransaction* transaction = ASurfaceTransaction_create_();
    if (transaction == nullptr) {
      return false;
    }
    ASurfaceTransaction_setFrameRateWithChangeStrategy_(
        transaction, surface_control, frame_rate,
        ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT,
        ANATIVEWINDOW_CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
    ASurfaceTransaction_apply_(transaction);
    ASurfaceTransaction_delete_(transaction);
    return true;
  }

 private:
  fml::RefPtr<fml::NativeLibrary> library_;
  bool is_available_ = false;

#define DECLARE_PROC(name) PFN_##name name##_ = nullptr;
  DECLARE_PROC(APerformanceHint_isFeatureSupported)
  DECLARE_PROC(APerformanceHint_getMaxGraphicsPipelineThreadsCount)
  DECLARE_PROC(ASessionCreationConfig_create)
  DECLARE_PROC(ASessionCreationConfig_release)
  DECLARE_PROC(ASessionCreationConfig_setTids)
  DECLARE_PROC(ASessionCreationConfig_setGraphicsPipeline)
  DECLARE_PROC(ASessionCreationConfig_setNativeSurfaces)
  DECLARE_PROC(ASessionCreationConfig_setUseAutoTiming)
  DECLARE_PROC(APerformanceHint_createSessionUsingConfig)
  DECLARE_PROC(APerformanceHint_setNativeSurfaces)
  DECLARE_PROC(APerformanceHint_getManager)
  DECLARE_PROC(APerformanceHint_closeSession)
  DECLARE_PROC(ANativeWindow_setFrameRateWithChangeStrategy)
  DECLARE_PROC(ASurfaceTransaction_create)
  DECLARE_PROC(ASurfaceTransaction_delete)
  DECLARE_PROC(ASurfaceTransaction_apply)
  DECLARE_PROC(ASurfaceTransaction_setFrameRateWithChangeStrategy)
#undef DECLARE_PROC

  FML_DISALLOW_COPY_AND_ASSIGN(NdkPerformanceHintApi);
};

}  // namespace

AndroidPerformanceHintApi::~AndroidPerformanceHintApi() = default;

std::shared_ptr<AndroidPerformanceHintApi>
AndroidPerformanceHintApi::CreateForPlatform() {
  return std::make_shared<NdkPerformanceHintApi>();
}

}  // namespace flutter
