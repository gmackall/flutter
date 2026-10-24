// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#define FML_USED_ON_EMBEDDER

#include <pthread.h>
#include <sys/resource.h>
#include <sys/time.h>
#include <algorithm>
#include <atomic>
#include <memory>
#include <optional>

#include <string>
#include <utility>
#include <vector>

#include "common/settings.h"
#include "flutter/fml/cpu_affinity.h"
#include "flutter/fml/logging.h"
#include "flutter/fml/memory/task_runner_checker.h"
#include "flutter/fml/message_loop.h"
#include "flutter/lib/ui/painting/image_generator_registry.h"
#include "flutter/shell/common/rasterizer.h"
#include "flutter/shell/common/run_configuration.h"
#include "flutter/shell/common/thread_host.h"
#include "flutter/shell/platform/android/android_display.h"
#include "flutter/shell/platform/android/android_image_generator.h"
#include "flutter/shell/platform/android/android_performance_hint_manager.h"
#include "flutter/shell/platform/android/android_rendering_selector.h"
#include "flutter/shell/platform/android/android_shell_holder.h"
#include "flutter/shell/platform/android/context/android_context.h"
#include "flutter/shell/platform/android/platform_view_android.h"

namespace flutter {

/// Inheriting ThreadConfigurer and use Android platform thread API to configure
/// the thread priorities
static void AndroidPlatformThreadConfigSetter(
    const fml::Thread::ThreadConfig& config) {
  // set thread name
  fml::Thread::SetCurrentThreadName(config);
  // set thread priority
  switch (config.priority) {
    case fml::Thread::ThreadPriority::kBackground: {
      fml::RequestAffinity(fml::CpuAffinity::kEfficiency);
      if (::setpriority(PRIO_PROCESS, 0, 10) != 0) {
        FML_LOG(ERROR) << "Failed to set IO task runner priority";
      }
      break;
    }
    case fml::Thread::ThreadPriority::kDisplay: {
      fml::RequestAffinity(fml::CpuAffinity::kNotEfficiency);
      if (::setpriority(PRIO_PROCESS, 0, -1) != 0) {
        FML_LOG(ERROR) << "Failed to set UI task runner priority";
      }
      break;
    }
    case fml::Thread::ThreadPriority::kRaster: {
      fml::RequestAffinity(fml::CpuAffinity::kNotEfficiency);
      // Android describes -8 as "most important display threads, for
      // compositing the screen and retrieving input events". Conservatively
      // set the raster thread to slightly lower priority than it.
      if (::setpriority(PRIO_PROCESS, 0, -5) != 0) {
        // Defensive fallback. Depending on the OEM, it may not be possible
        // to set priority to -5.
        if (::setpriority(PRIO_PROCESS, 0, -2) != 0) {
          FML_LOG(ERROR) << "Failed to set raster task runner priority";
        }
      }
      break;
    }
    default:
      fml::RequestAffinity(fml::CpuAffinity::kNotPerformance);
      if (::setpriority(PRIO_PROCESS, 0, 0) != 0) {
        FML_LOG(ERROR) << "Failed to set priority";
      }
  }
}
static PlatformData GetDefaultPlatformData() {
  PlatformData platform_data;
  platform_data.lifecycle_state = "AppLifecycleState.detached";
  return platform_data;
}

AndroidShellHolder::AndroidShellHolder(
    const flutter::Settings& settings,
    std::shared_ptr<PlatformViewAndroidJNI> jni_facade,
    AndroidRenderingAPI android_rendering_api)
    : settings_(settings),
      jni_facade_(jni_facade),
      android_rendering_api_(android_rendering_api) {
  static size_t thread_host_count = 1;
  auto thread_label = std::to_string(thread_host_count++);

  auto mask = ThreadHost::Type::kRaster | ThreadHost::Type::kIo;
  if (settings.merged_platform_ui_thread !=
      Settings::MergedPlatformUIThread::kEnabled) {
    mask |= ThreadHost::Type::kUi;
  }

  std::atomic<int32_t> ui_tid{0};
  std::atomic<int32_t> raster_tid{0};
  auto thread_config_setter =
      [&ui_tid, &raster_tid](const fml::Thread::ThreadConfig& config) {
        AndroidPlatformThreadConfigSetter(config);
        if (config.priority == fml::Thread::ThreadPriority::kDisplay) {
          ui_tid.store(gettid());
        } else if (config.priority == fml::Thread::ThreadPriority::kRaster) {
          raster_tid.store(gettid());
        }
      };

  flutter::ThreadHost::ThreadHostConfig host_config(thread_label, mask,
                                                    thread_config_setter);
  host_config.ui_config = fml::Thread::ThreadConfig(
      flutter::ThreadHost::ThreadHostConfig::MakeThreadName(
          flutter::ThreadHost::Type::kUi, thread_label),
      fml::Thread::ThreadPriority::kDisplay);
  host_config.raster_config = fml::Thread::ThreadConfig(
      flutter::ThreadHost::ThreadHostConfig::MakeThreadName(
          flutter::ThreadHost::Type::kRaster, thread_label),
      fml::Thread::ThreadPriority::kRaster);
  host_config.io_config = fml::Thread::ThreadConfig(
      flutter::ThreadHost::ThreadHostConfig::MakeThreadName(
          flutter::ThreadHost::Type::kIo, thread_label),
      fml::Thread::ThreadPriority::kNormal);

  // fml::Thread's constructor blocks until the spawned thread has initialized
  // its message loop and executed thread_config_setter, so ui_tid and
  // raster_tid are guaranteed to be populated when ThreadHost returns.
  thread_host_ = std::make_shared<ThreadHost>(host_config);
  FML_DCHECK(raster_tid.load() > 0);
  FML_DCHECK(settings.merged_platform_ui_thread ==
                 Settings::MergedPlatformUIThread::kEnabled ||
             ui_tid.load() > 0);

  double refresh_rate = jni_facade->GetDisplayRefreshRate();
  if (refresh_rate <= 0) {
    refresh_rate = 60.0;
  }
  const int64_t target_duration_ns = static_cast<int64_t>(1e9 / refresh_rate);

  const bool platform_ui_merged =
      (settings.merged_platform_ui_thread ==
           Settings::MergedPlatformUIThread::kEnabled ||
       settings.merged_platform_ui_thread ==
           Settings::MergedPlatformUIThread::kMergeAfterLaunch);
  std::vector<int32_t> ui_tids;
  if (ui_tid.load() > 0) {
    ui_tids.push_back(ui_tid.load());
  }
  if (platform_ui_merged &&
      std::find(ui_tids.begin(), ui_tids.end(), gettid()) == ui_tids.end()) {
    ui_tids.push_back(gettid());
  }
  if (!ui_tids.empty()) {
    ui_performance_hint_manager_ =
        AndroidPerformanceHintManager::Create(ui_tids, target_duration_ns);
  }
  const int32_t captured_raster_tid = raster_tid.load();
  if (captured_raster_tid > 0) {
    raster_performance_hint_manager_ = AndroidPerformanceHintManager::Create(
        {captured_raster_tid}, target_duration_ns);
  }

  auto shared_android_context =
      std::make_shared<std::weak_ptr<AndroidContext>>();

  fml::WeakPtr<PlatformViewAndroid> weak_platform_view;
  AndroidRenderingAPI rendering_api = android_rendering_api_;
  Shell::CreateCallback<PlatformView> on_create_platform_view =
      [&jni_facade, &weak_platform_view, rendering_api,
       ui_hint = ui_performance_hint_manager_,
       raster_hint = raster_performance_hint_manager_,
       shared_android_context](Shell& shell) {
        std::unique_ptr<PlatformViewAndroid> platform_view_android;
        platform_view_android = std::make_unique<PlatformViewAndroid>(
            shell,                   // delegate
            shell.GetTaskRunners(),  // task runners
            jni_facade,              // JNI interop
            rendering_api            // rendering API
        );
        platform_view_android->SetPerformanceHintManagers(ui_hint, raster_hint);
        if (shared_android_context) {
          *shared_android_context = platform_view_android->GetAndroidContext();
        }
        weak_platform_view = platform_view_android->GetWeakPtr();
        return platform_view_android;
      };

  Shell::CreateCallback<Rasterizer> on_create_rasterizer = [](Shell& shell) {
    return std::make_unique<Rasterizer>(shell);
  };

  // The current thread will be used as the platform thread. Ensure that the
  // message loop is initialized.
  fml::MessageLoop::EnsureInitializedForCurrentThread();
  fml::RefPtr<fml::TaskRunner> raster_runner;
  fml::RefPtr<fml::TaskRunner> ui_runner;
  fml::RefPtr<fml::TaskRunner> io_runner;
  fml::RefPtr<fml::TaskRunner> platform_runner =
      fml::MessageLoop::GetCurrent().GetTaskRunner();
  raster_runner = thread_host_->raster_thread->GetTaskRunner();
  if (settings.merged_platform_ui_thread ==
      Settings::MergedPlatformUIThread::kEnabled) {
    ui_runner = platform_runner;
  } else {
    ui_runner = thread_host_->ui_thread->GetTaskRunner();
  }
  io_runner = thread_host_->io_thread->GetTaskRunner();

  flutter::TaskRunners task_runners(thread_label,     // label
                                    platform_runner,  // platform
                                    raster_runner,    // raster
                                    ui_runner,        // ui
                                    io_runner         // io
  );

  flutter::Settings shell_settings = settings_;
  if (ui_performance_hint_manager_) {
    FrameBuiltCallback prev_built_callback =
        shell_settings.frame_built_callback;
    std::weak_ptr<AndroidPerformanceHintManager> weak_ui_hint =
        ui_performance_hint_manager_;
    const fml::TaskQueueId raster_queue_id =
        raster_runner ? raster_runner->GetTaskQueueId()
                      : fml::TaskQueueId::Invalid();
    shell_settings.frame_built_callback = [weak_ui_hint, raster_queue_id,
                                           prev_built_callback](
                                              fml::TimePoint build_start,
                                              fml::TimePoint build_end) {
      if (prev_built_callback) {
        prev_built_callback(build_start, build_end);
      }
      // When RasterThreadMerger has merged the raster queue onto the
      // current UI/platform thread, the thread's work for this frame
      // continues through Rasterizer::Draw + EndFrame and will be reported
      // as a combined duration in frame_rasterized_callback.
      if (raster_queue_id.is_valid() &&
          fml::MessageLoop::IsInitializedForCurrentThread() &&
          fml::TaskRunnerChecker::RunsOnTheSameThread(
              fml::MessageLoop::GetCurrentTaskQueueId(), raster_queue_id)) {
        return;
      }
      auto ui_hint = weak_ui_hint.lock();
      if (!ui_hint) {
        return;
      }
      const int64_t build_start_ns = build_start.ToEpochDelta().ToNanoseconds();
      const int64_t build_ns = (build_end - build_start).ToNanoseconds();
      if (build_start_ns > 0 && build_ns > 0) {
        ui_hint->ReportActualWorkDuration(build_start_ns, build_ns, build_ns);
      }
    };
  }
  if (ui_performance_hint_manager_ || raster_performance_hint_manager_) {
    auto last_gpu_generation = std::make_shared<uint64_t>(0);
    FrameRasterizedCallback prev_callback =
        shell_settings.frame_rasterized_callback;
    std::weak_ptr<AndroidPerformanceHintManager> weak_ui_hint =
        ui_performance_hint_manager_;
    std::weak_ptr<AndroidPerformanceHintManager> weak_raster_hint =
        raster_performance_hint_manager_;
    shell_settings.frame_rasterized_callback = [weak_ui_hint, weak_raster_hint,
                                                captured_raster_tid,
                                                platform_ui_merged,
                                                shared_android_context,
                                                last_gpu_generation,
                                                prev_callback](
                                                   const FrameTiming& timing) {
      if (prev_callback) {
        prev_callback(timing);
      }
      const int64_t raster_start_ns =
          timing.Get(FrameTiming::kRasterStart).ToEpochDelta().ToNanoseconds();
      // OnFrameRasterized is invoked in Rasterizer::Draw after
      // external_view_embedder_->EndFrame(), so fml::TimePoint::Now()
      // captures the full raster work period on the thread including
      // EndFrame.
      const fml::TimePoint raster_finish = std::max(
          timing.Get(FrameTiming::kRasterFinish), fml::TimePoint::Now());
      const int64_t raster_ns =
          (raster_finish - timing.Get(FrameTiming::kRasterStart))
              .ToNanoseconds();

      int64_t gpu_ns = 0;
      if (shared_android_context) {
        if (auto android_ctx = shared_android_context->lock()) {
          if (auto impeller_ctx = android_ctx->GetImpellerContext()) {
            const uint64_t gen = impeller_ctx->GetLastFrameGpuTimeGeneration();
            if (gen != *last_gpu_generation) {
              *last_gpu_generation = gen;
              gpu_ns =
                  std::max<int64_t>(0, impeller_ctx->GetLastFrameGpuTimeNs());
            }
          }
        }
      }

      // When RasterThreadMerger merges the rasterizer onto the platform
      // thread (e.g. legacy Hybrid Composition PlatformViews), the
      // dedicated raster thread sits idle. When the platform and UI threads
      // are merged, that thread executes both build and raster sequentially
      // and reports the combined duration to the UI session after EndFrame
      // finishes.
      const bool raster_merged =
          (captured_raster_tid > 0 && gettid() != captured_raster_tid);
      if (raster_merged) {
        if (platform_ui_merged) {
          if (auto ui_hint = weak_ui_hint.lock()) {
            const int64_t build_start_ns = timing.Get(FrameTiming::kBuildStart)
                                               .ToEpochDelta()
                                               .ToNanoseconds();
            const int64_t build_ns = (timing.Get(FrameTiming::kBuildFinish) -
                                      timing.Get(FrameTiming::kBuildStart))
                                         .ToNanoseconds();
            const int64_t start_ns =
                build_start_ns > 0 ? build_start_ns : raster_start_ns;
            const int64_t merged_cpu_ns =
                (build_ns > 0 ? build_ns : 0) + (raster_ns > 0 ? raster_ns : 0);
            const int64_t merged_total_ns = std::max(merged_cpu_ns, gpu_ns);
            if (start_ns > 0 && (merged_cpu_ns > 0 || gpu_ns > 0)) {
              ui_hint->ReportActualWorkDuration(start_ns, merged_total_ns,
                                                merged_cpu_ns, gpu_ns);
            }
          }
        }
        return;
      }

      if (raster_start_ns > 0 && (raster_ns > 0 || gpu_ns > 0)) {
        if (auto raster_hint = weak_raster_hint.lock()) {
          const int64_t raster_total_ns = std::max(raster_ns, gpu_ns);
          raster_hint->ReportActualWorkDuration(
              raster_start_ns, raster_total_ns, raster_ns, gpu_ns);
        }
      }
    };
  }

  shell_ =
      Shell::Create(GetDefaultPlatformData(),  // window data
                    task_runners,              // task runners
                    shell_settings,            // settings
                    on_create_platform_view,   // platform view create callback
                    on_create_rasterizer       // rasterizer create callback
      );

  if (shell_) {
    shell_->GetDartVM()->GetConcurrentMessageLoop()->PostTaskToAllWorkers([]() {
      if (::setpriority(PRIO_PROCESS, gettid(), 1) != 0) {
        FML_LOG(ERROR) << "Failed to set Workers task runner priority";
      }
    });

    shell_->RegisterImageDecoder(
        [runner = task_runners.GetIOTaskRunner()](sk_sp<SkData> buffer) {
          return AndroidImageGenerator::MakeFromData(std::move(buffer), runner);
        },
        -1);
    FML_DLOG(INFO) << "Registered Android SDK image decoder (API level 28+)";
  }

  platform_view_ = weak_platform_view;
  FML_DCHECK(platform_view_);
  is_valid_ = shell_ != nullptr;
}

AndroidShellHolder::AndroidShellHolder(
    const Settings& settings,
    const std::shared_ptr<PlatformViewAndroidJNI>& jni_facade,
    const std::shared_ptr<ThreadHost>& thread_host,
    std::unique_ptr<Shell> shell,
    std::unique_ptr<APKAssetProvider> apk_asset_provider,
    const fml::WeakPtr<PlatformViewAndroid>& platform_view,
    AndroidRenderingAPI rendering_api,
    const std::shared_ptr<AndroidPerformanceHintManager>&
        ui_performance_hint_manager,
    const std::shared_ptr<AndroidPerformanceHintManager>&
        raster_performance_hint_manager)
    : settings_(settings),
      jni_facade_(jni_facade),
      platform_view_(platform_view),
      thread_host_(thread_host),
      shell_(std::move(shell)),
      apk_asset_provider_(std::move(apk_asset_provider)),
      android_rendering_api_(rendering_api),
      ui_performance_hint_manager_(ui_performance_hint_manager),
      raster_performance_hint_manager_(raster_performance_hint_manager) {
  FML_DCHECK(jni_facade);
  FML_DCHECK(shell_);
  FML_DCHECK(shell_->IsSetup());
  FML_DCHECK(platform_view_);
  FML_DCHECK(thread_host_);
  is_valid_ = shell_ != nullptr;
}

AndroidShellHolder::~AndroidShellHolder() {
  shell_.reset();
  // Close ADPF hint sessions before joining and destroying the ThreadHost's OS
  // threads so their TIDs are unregistered before they exit.
  ui_performance_hint_manager_.reset();
  raster_performance_hint_manager_.reset();
  thread_host_.reset();
}

bool AndroidShellHolder::IsValid() const {
  return is_valid_;
}

const flutter::Settings& AndroidShellHolder::GetSettings() const {
  return settings_;
}

std::unique_ptr<AndroidShellHolder> AndroidShellHolder::Spawn(
    std::shared_ptr<PlatformViewAndroidJNI> jni_facade,
    const std::string& entrypoint,
    const std::string& libraryUrl,
    const std::string& initial_route,
    const std::vector<std::string>& entrypoint_args,
    int64_t engine_id) const {
  FML_DCHECK(shell_ && shell_->IsSetup())
      << "A new Shell can only be spawned "
         "if the current Shell is properly constructed";

  // Pull out the new PlatformViewAndroid from the new Shell to feed to it to
  // the new AndroidShellHolder.
  //
  // It's a weak pointer because it's owned by the Shell (which we're also)
  // making below. And the AndroidShellHolder then owns the Shell.
  fml::WeakPtr<PlatformViewAndroid> weak_platform_view;

  // Take out the old AndroidContext to reuse inside the PlatformViewAndroid
  // of the new Shell.
  PlatformViewAndroid* android_platform_view = platform_view_.get();
  // There's some indirection with platform_view_ being a weak pointer but
  // we just checked that the shell_ exists above and a valid shell is the
  // owner of the platform view so this weak pointer always exists.
  FML_DCHECK(android_platform_view);
  std::shared_ptr<flutter::AndroidContext> android_context =
      android_platform_view->GetAndroidContext();
  FML_DCHECK(android_context);

  // This is a synchronous call, so the captures don't have race checks.
  Shell::CreateCallback<PlatformView> on_create_platform_view =
      [&jni_facade, android_context, &weak_platform_view,
       ui_hint = ui_performance_hint_manager_,
       raster_hint = raster_performance_hint_manager_](Shell& shell) {
        std::unique_ptr<PlatformViewAndroid> platform_view_android;
        platform_view_android = std::make_unique<PlatformViewAndroid>(
            shell,                   // delegate
            shell.GetTaskRunners(),  // task runners
            jni_facade,              // JNI interop
            android_context          // Android context
        );
        platform_view_android->SetPerformanceHintManagers(ui_hint, raster_hint);
        weak_platform_view = platform_view_android->GetWeakPtr();
        return platform_view_android;
      };

  Shell::CreateCallback<Rasterizer> on_create_rasterizer = [](Shell& shell) {
    return std::make_unique<Rasterizer>(shell);
  };

  auto config = BuildRunConfiguration(entrypoint, libraryUrl, entrypoint_args);
  if (!config) {
    // If the RunConfiguration was null, the kernel blob wasn't readable.
    // Fail the whole thing.
    return nullptr;
  }
  config->SetEngineId(engine_id);

  std::unique_ptr<flutter::Shell> shell =
      shell_->Spawn(std::move(config.value()), initial_route,
                    on_create_platform_view, on_create_rasterizer);

  return std::unique_ptr<AndroidShellHolder>(new AndroidShellHolder(
      GetSettings(), jni_facade, thread_host_, std::move(shell),
      apk_asset_provider_->Clone(), weak_platform_view,
      android_context->RenderingApi(), ui_performance_hint_manager_,
      raster_performance_hint_manager_));
}

void AndroidShellHolder::Launch(
    std::unique_ptr<APKAssetProvider> apk_asset_provider,
    const std::string& entrypoint,
    const std::string& libraryUrl,
    const std::vector<std::string>& entrypoint_args,
    int64_t engine_id) {
  if (!IsValid()) {
    return;
  }

  apk_asset_provider_ = std::move(apk_asset_provider);
  auto config = BuildRunConfiguration(entrypoint, libraryUrl, entrypoint_args);
  if (!config) {
    return;
  }
  config->SetEngineId(engine_id);
  UpdateDisplayMetrics();
  shell_->RunEngine(std::move(config.value()));
}

Rasterizer::Screenshot AndroidShellHolder::Screenshot(
    Rasterizer::ScreenshotType type,
    bool base64_encode) {
  if (!IsValid()) {
    return {nullptr, DlISize(), "", Rasterizer::ScreenshotFormat::kUnknown};
  }
  return shell_->Screenshot(type, base64_encode);
}

fml::WeakPtr<PlatformViewAndroid> AndroidShellHolder::GetPlatformView() {
  FML_DCHECK(platform_view_);
  return platform_view_;
}

void AndroidShellHolder::NotifyLowMemoryWarning() {
  FML_DCHECK(shell_);
  shell_->NotifyLowMemoryWarning();
}

std::optional<RunConfiguration> AndroidShellHolder::BuildRunConfiguration(
    const std::string& entrypoint,
    const std::string& libraryUrl,
    const std::vector<std::string>& entrypoint_args) const {
  std::unique_ptr<IsolateConfiguration> isolate_configuration;
  if (flutter::DartVM::IsRunningPrecompiledCode()) {
    isolate_configuration = IsolateConfiguration::CreateForAppSnapshot();
  } else {
    std::unique_ptr<fml::Mapping> kernel_blob =
        fml::FileMapping::CreateReadOnly(
            GetSettings().application_kernel_asset);
    if (!kernel_blob) {
      FML_DLOG(ERROR) << "Unable to load the kernel blob asset.";
      return std::nullopt;
    }
    isolate_configuration =
        IsolateConfiguration::CreateForKernel(std::move(kernel_blob));
  }

  RunConfiguration config(std::move(isolate_configuration));
  config.AddAssetResolver(apk_asset_provider_->Clone());

  {
    if (!entrypoint.empty() && !libraryUrl.empty()) {
      config.SetEntrypointAndLibrary(entrypoint, libraryUrl);
    } else if (!entrypoint.empty()) {
      config.SetEntrypoint(entrypoint);
    }
    if (!entrypoint_args.empty()) {
      config.SetEntrypointArgs(entrypoint_args);
    }
  }
  return config;
}

void AndroidShellHolder::UpdateDisplayMetrics() {
  std::vector<std::unique_ptr<Display>> displays;
  displays.push_back(std::make_unique<AndroidDisplay>(jni_facade_));
  shell_->OnDisplayUpdates(std::move(displays));

  if (jni_facade_) {
    const double refresh_rate = jni_facade_->GetDisplayRefreshRate();
    if (refresh_rate > 0) {
      const int64_t target_ns = static_cast<int64_t>(1e9 / refresh_rate);
      if (ui_performance_hint_manager_) {
        ui_performance_hint_manager_->UpdateTargetWorkDuration(target_ns);
      }
      if (raster_performance_hint_manager_) {
        raster_performance_hint_manager_->UpdateTargetWorkDuration(target_ns);
      }
    }
  }
}

bool AndroidShellHolder::IsSurfaceControlEnabled() {
  return GetPlatformView()->IsSurfaceControlEnabled();
}

}  // namespace flutter
