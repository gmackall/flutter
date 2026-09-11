// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/adpf/android_performance_hint_registry.h"

#include <set>

#include "flutter/fml/build_config.h"
#include "flutter/fml/logging.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/trace_event.h"

#if FML_OS_ANDROID
#include <android/api-level.h>
#endif  // FML_OS_ANDROID

namespace flutter {

namespace {

int QueryDeviceApiLevel() {
#if FML_OS_ANDROID
  return android_get_device_api_level();
#else   // FML_OS_ANDROID
  return 0;
#endif  // FML_OS_ANDROID
}

void AppendMissing(std::string& out, const char* name) {
  if (!out.empty()) {
    out += ", ";
  }
  out += name;
}

}  // namespace

bool AndroidPerformanceHintRegistry::PlatformCapabilities::IsComplete() const {
  return api_available && manager_available && sessions && surface_binding &&
         graphics_pipeline && auto_cpu_timing && auto_gpu_timing &&
         max_graphics_pipeline_threads > 0;
}

std::string
AndroidPerformanceHintRegistry::PlatformCapabilities::DescribeMissing() const {
  std::string missing;
  if (!api_available) {
    AppendMissing(missing, "api");
  }
  if (!manager_available) {
    AppendMissing(missing, "manager");
  }
  if (!sessions) {
    AppendMissing(missing, "sessions");
  }
  if (!surface_binding) {
    AppendMissing(missing, "surface-binding");
  }
  if (!graphics_pipeline) {
    AppendMissing(missing, "graphics-pipeline");
  }
  if (!auto_cpu_timing) {
    AppendMissing(missing, "auto-cpu-timing");
  }
  if (!auto_gpu_timing) {
    AppendMissing(missing, "auto-gpu-timing");
  }
  if (max_graphics_pipeline_threads <= 0) {
    AppendMissing(missing, "graphics-pipeline-thread-limit");
  }
  return missing;
}

AndroidPerformanceHintRegistry&
AndroidPerformanceHintRegistry::GetProcessInstance() {
  // Intentionally leaked: the registry owns the management thread, which
  // outlives every engine in the process.
  static AndroidPerformanceHintRegistry* instance =
      new AndroidPerformanceHintRegistry(
          []() { return AndroidPerformanceHintApi::CreateForPlatform(); },
          AndroidDeviceIdentity::FromSystemProperties(), QueryDeviceApiLevel(),
          nullptr);
  return *instance;
}

AndroidPerformanceHintRegistry::AndroidPerformanceHintRegistry(
    ApiFactory api_factory,
    AndroidDeviceIdentity device_identity,
    int device_api_level,
    fml::RefPtr<fml::TaskRunner> management_runner)
    : api_factory_(std::move(api_factory)),
      device_identity_(std::move(device_identity)),
      device_api_level_(device_api_level),
      management_runner_(std::move(management_runner)) {
  FML_DCHECK(api_factory_);
}

AndroidPerformanceHintRegistry::~AndroidPerformanceHintRegistry() = default;

const AndroidDeviceIdentity& AndroidPerformanceHintRegistry::GetDeviceIdentity()
    const {
  return device_identity_;
}

int AndroidPerformanceHintRegistry::GetDeviceApiLevel() const {
  return device_api_level_;
}

fml::RefPtr<fml::TaskRunner>
AndroidPerformanceHintRegistry::GetManagementRunner() {
  std::scoped_lock lock(mutex_);
  if (!management_runner_) {
    management_thread_ = std::make_unique<fml::Thread>("io.flutter.adpf");
    management_runner_ = management_thread_->GetTaskRunner();
  }
  return management_runner_;
}

const AndroidPerformanceHintRegistry::PlatformCapabilities&
AndroidPerformanceHintRegistry::EvaluateCapabilities() {
  if (capabilities_evaluated_) {
    return capabilities_;
  }
  TRACE_EVENT0("flutter", "AndroidAdpfEvaluateCapabilities");
  capabilities_evaluated_ = true;

  api_ = api_factory_();
  FML_DCHECK(api_);
  PlatformCapabilities& caps = capabilities_;
  caps.api_available = api_ && api_->IsAvailable();
  if (!caps.api_available) {
    FML_LOG(INFO) << "Android ADPF: the automatic graphics pipeline API is "
                     "not available on this device.";
    return caps;
  }

  using Feature = AndroidPerformanceHintApi::Feature;
  caps.sessions = api_->IsFeatureSupported(Feature::kSessions);
  caps.surface_binding = api_->IsFeatureSupported(Feature::kSurfaceBinding);
  caps.graphics_pipeline =
      api_->IsFeatureSupported(Feature::kGraphicsPipeline);
  caps.auto_cpu_timing = api_->IsFeatureSupported(Feature::kAutoCpu);
  caps.auto_gpu_timing = api_->IsFeatureSupported(Feature::kAutoGpu);

  manager_ = api_->GetManager();
  caps.manager_available = manager_ != nullptr;
  if (caps.manager_available) {
    caps.max_graphics_pipeline_threads =
        api_->GetMaxGraphicsPipelineThreadsCount(manager_);
  }

  if (caps.IsComplete()) {
    FML_LOG(INFO) << "Android ADPF: automatic graphics pipeline sessions are "
                     "supported (thread limit "
                  << caps.max_graphics_pipeline_threads << ").";
  } else {
    FML_LOG(INFO) << "Android ADPF: automatic graphics pipeline sessions are "
                     "unsupported; missing: "
                  << caps.DescribeMissing() << ".";
  }
  return caps;
}

const std::shared_ptr<AndroidPerformanceHintApi>&
AndroidPerformanceHintRegistry::GetApi() const {
  FML_DCHECK(capabilities_evaluated_);
  return api_;
}

APerformanceHintManager* AndroidPerformanceHintRegistry::GetManager() const {
  FML_DCHECK(capabilities_evaluated_);
  return manager_;
}

void AndroidPerformanceHintRegistry::MarkAutomaticTimingUnsupported() {
  FML_DCHECK(capabilities_evaluated_);
  if (capabilities_.auto_cpu_timing || capabilities_.auto_gpu_timing) {
    FML_LOG(INFO) << "Android ADPF: session creation reported that automatic "
                     "timing is unsupported; disabling for this process.";
  }
  capabilities_.auto_cpu_timing = false;
  capabilities_.auto_gpu_timing = false;
}

size_t AndroidPerformanceHintRegistry::CountUniqueThreadsLocked(
    const void* excluded_owner,
    const std::vector<pid_t>& extra) const {
  std::set<pid_t> unique(extra.begin(), extra.end());
  for (const auto& [owner, tids] : thread_reservations_) {
    if (owner == excluded_owner) {
      continue;
    }
    unique.insert(tids.begin(), tids.end());
  }
  return unique.size();
}

bool AndroidPerformanceHintRegistry::ReserveGraphicsPipelineThreads(
    const void* owner,
    const std::vector<pid_t>& tids) {
  // The limit is immutable once evaluated, and reservations only happen on
  // the management sequence after evaluation.
  const int limit = capabilities_.max_graphics_pipeline_threads;
  std::scoped_lock lock(mutex_);
  thread_reservations_.erase(owner);
  if (limit <= 0 ||
      CountUniqueThreadsLocked(owner, tids) > static_cast<size_t>(limit)) {
    return false;
  }
  thread_reservations_[owner] = tids;
  return true;
}

void AndroidPerformanceHintRegistry::ReleaseGraphicsPipelineThreads(
    const void* owner) {
  std::scoped_lock lock(mutex_);
  thread_reservations_.erase(owner);
}

size_t AndroidPerformanceHintRegistry::GetReservedGraphicsPipelineThreadCount()
    const {
  std::scoped_lock lock(mutex_);
  return CountUniqueThreadsLocked(nullptr, {});
}

void AndroidPerformanceHintRegistry::SetDisplayRefreshRate(double frame_rate) {
  std::vector<RefreshRateObserver> observers;
  {
    std::scoped_lock lock(mutex_);
    if (frame_rate <= 0 || display_refresh_rate_ == frame_rate) {
      return;
    }
    display_refresh_rate_ = frame_rate;
    for (const auto& [token, observer] : refresh_rate_observers_) {
      observers.push_back(observer);
    }
  }
  for (const auto& observer : observers) {
    observer(frame_rate);
  }
}

double AndroidPerformanceHintRegistry::GetDisplayRefreshRate() const {
  std::scoped_lock lock(mutex_);
  return display_refresh_rate_;
}

uint64_t AndroidPerformanceHintRegistry::AddRefreshRateObserver(
    RefreshRateObserver observer) {
  std::scoped_lock lock(mutex_);
  const uint64_t token = next_observer_token_++;
  refresh_rate_observers_[token] = std::move(observer);
  return token;
}

void AndroidPerformanceHintRegistry::RemoveRefreshRateObserver(uint64_t token) {
  std::scoped_lock lock(mutex_);
  refresh_rate_observers_.erase(token);
}

void AndroidPerformanceHintRegistry::FlushManagementRunnerForTesting() {
  fml::RefPtr<fml::TaskRunner> runner;
  {
    std::scoped_lock lock(mutex_);
    runner = management_runner_;
  }
  if (!runner) {
    return;
  }
  fml::AutoResetWaitableEvent latch;
  runner->PostTask([&latch]() { latch.Signal(); });
  latch.Wait();
}

}  // namespace flutter
