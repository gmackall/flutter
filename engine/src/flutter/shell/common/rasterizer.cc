// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/common/rasterizer.h"

#include <algorithm>
#include <memory>
#include <utility>

#include "display_list/dl_builder.h"
#include "flow/frame_timings.h"
#include "flutter/common/constants.h"
#include "flutter/common/graphics/persistent_cache.h"
#include "flutter/flow/layers/offscreen_surface.h"
#include "flutter/fml/time/time_delta.h"
#include "flutter/fml/time/time_point.h"
#include "flutter/shell/common/base64.h"
#include "flutter/shell/common/serialization_callbacks.h"
#include "fml/closure.h"
#include "fml/make_copyable.h"
#include "fml/synchronization/waitable_event.h"
#include "impeller/renderer/context.h"
#include "third_party/skia/include/core/SkColorSpace.h"
#include "third_party/skia/include/core/SkData.h"
#include "third_party/skia/include/core/SkImage.h"
#include "third_party/skia/include/core/SkImageInfo.h"
#include "third_party/skia/include/core/SkMatrix.h"
#include "third_party/skia/include/core/SkPictureRecorder.h"
#include "third_party/skia/include/core/SkRect.h"
#include "third_party/skia/include/core/SkSerialProcs.h"
#include "third_party/skia/include/core/SkSize.h"
#include "third_party/skia/include/core/SkSurface.h"
#include "third_party/skia/include/encode/SkPngEncoder.h"
#include "third_party/skia/include/gpu/GpuTypes.h"
#include "third_party/skia/include/gpu/ganesh/GrBackendSurface.h"
#include "third_party/skia/include/gpu/ganesh/GrDirectContext.h"
#include "third_party/skia/include/gpu/ganesh/GrTypes.h"
#include "third_party/skia/include/gpu/ganesh/SkSurfaceGanesh.h"

#if IMPELLER_SUPPORTS_RENDERING
#include "impeller/core/formats.h"                // nogncheck
#include "impeller/display_list/aiks_context.h"   // nogncheck
#include "impeller/display_list/dl_dispatcher.h"  // nogncheck
#endif

namespace flutter {

// The rasterizer will tell Skia to purge cached resources that have not been
// used within this interval.
[[maybe_unused]] static constexpr std::chrono::milliseconds
    kSkiaCleanupExpiration(15000);

Rasterizer::Rasterizer(Delegate& delegate,
                       MakeGpuImageBehavior gpu_image_behavior)
    : delegate_(delegate),
      gpu_image_behavior_(gpu_image_behavior),
      compositor_context_(std::make_unique<flutter::CompositorContext>(*this)),
      snapshot_controller_(
          SnapshotController::Make(*this, delegate.GetSettings())),
      weak_factory_(this) {
  FML_DCHECK(compositor_context_);
}

Rasterizer::~Rasterizer() {
  CancelHeldSyncFrame();
}

fml::TaskRunnerAffineWeakPtr<Rasterizer> Rasterizer::GetWeakPtr() const {
  return weak_factory_.GetWeakPtr();
}

fml::TaskRunnerAffineWeakPtr<SnapshotDelegate> Rasterizer::GetSnapshotDelegate()
    const {
  return weak_factory_.GetWeakPtr();
}

void Rasterizer::SetImpellerContext(
    std::shared_ptr<impeller::ImpellerContextFuture> impeller_context) {
  impeller_context_ = std::move(impeller_context);
}

void Rasterizer::Setup(std::unique_ptr<Surface> surface) {
  is_torn_down_ = false;
  surface_ = std::move(surface);

  if (max_cache_bytes_.has_value()) {
    SetResourceCacheMaxBytes(max_cache_bytes_.value(),
                             user_override_resource_cache_bytes_);
  }

  auto context_switch = surface_->MakeRenderContextCurrent();
  if (context_switch->GetResult()) {
    compositor_context_->OnGrContextCreated();
  }

  if (external_view_embedder_ &&
      external_view_embedder_->SupportsDynamicThreadMerging() &&
      !raster_thread_merger_) {
    const auto platform_id =
        delegate_.GetTaskRunners().GetPlatformTaskRunner()->GetTaskQueueId();
    const auto gpu_id =
        delegate_.GetTaskRunners().GetRasterTaskRunner()->GetTaskQueueId();
    raster_thread_merger_ = fml::RasterThreadMerger::CreateOrShareThreadMerger(
        delegate_.GetParentRasterThreadMerger(), platform_id, gpu_id);
  }
  if (raster_thread_merger_) {
    raster_thread_merger_->SetMergeUnmergeCallback([this]() {
      // Clear the GL context after the thread configuration has changed.
      if (surface_) {
        surface_->ClearRenderContext();
      }
    });
  }
}

void Rasterizer::TeardownExternalViewEmbedder() {
  CancelHeldSyncFrame();
  if (external_view_embedder_) {
    external_view_embedder_->Teardown();
  }
}

void Rasterizer::Teardown() {
  is_torn_down_ = true;
  CancelHeldSyncFrame();
  if (surface_) {
    auto context_switch = surface_->MakeRenderContextCurrent();
    if (context_switch->GetResult()) {
      compositor_context_->OnGrContextDestroyed();
#if !SLIMPELLER
      if (auto* context = surface_->GetContext()) {
        context->purgeUnlockedResources(GrPurgeResourceOptions::kAllResources);
      }
#endif  //  !SLIMPELLER
    }
    context_switch.reset();
    surface_->ClearRenderContext();
    surface_.reset();
  }

  view_records_.clear();

  if (raster_thread_merger_.get() != nullptr &&
      raster_thread_merger_.get()->IsMerged()) {
    FML_DCHECK(raster_thread_merger_->IsEnabled());
    raster_thread_merger_->UnMergeNowIfLastOne();
    raster_thread_merger_->SetMergeUnmergeCallback(nullptr);
  }
}

bool Rasterizer::IsTornDown() {
  return is_torn_down_;
}

std::optional<DrawSurfaceStatus> Rasterizer::GetLastDrawStatus(
    int64_t view_id) {
  auto found = view_records_.find(view_id);
  if (found != view_records_.end()) {
    return found->second.last_draw_status;
  } else {
    return std::optional<DrawSurfaceStatus>();
  }
}

void Rasterizer::EnableThreadMergerIfNeeded() {
  if (raster_thread_merger_) {
    raster_thread_merger_->Enable();
  }
}

void Rasterizer::DisableThreadMergerIfNeeded() {
  if (raster_thread_merger_) {
    raster_thread_merger_->Disable();
  }
}

void Rasterizer::NotifyLowMemoryWarning() const {
  ClearRenderTargetCache();
}

void Rasterizer::ClearRenderTargetCache() const {
  if (!surface_) {
    FML_DLOG(INFO)
        << "Rasterizer::ClearRenderTargetCache called with no surface.";
    return;
  }
#if IMPELLER_SUPPORTS_RENDERING
  if (auto aiks_context = surface_->GetAiksContext()) {
    auto context_switch = surface_->MakeRenderContextCurrent();
    if (context_switch->GetResult()) {
      aiks_context->ClearRenderTargetCache();
      aiks_context->ClearCachedTextures();
    }
    return;
  }
#endif  // IMPELLER_SUPPORTS_RENDERING
#if !SLIMPELLER
  auto context = surface_->GetContext();
  if (!context) {
    FML_DLOG(INFO)
        << "Rasterizer::ClearRenderTargetCache called with no GrContext.";
    return;
  }
  auto context_switch = surface_->MakeRenderContextCurrent();
  if (!context_switch->GetResult()) {
    return;
  }
  context->performDeferredCleanup(std::chrono::milliseconds(0));
#endif  // !SLIMPELLER
}

void Rasterizer::CollectView(int64_t view_id) {
  if (held_sync_frame_.has_value() && held_sync_frame_->frame_item) {
    auto& held_tasks = held_sync_frame_->frame_item->layer_tree_tasks;
    held_tasks.erase(
        std::remove_if(held_tasks.begin(), held_tasks.end(),
                       [view_id](const std::unique_ptr<LayerTreeTask>& task) {
                         return task && task->view_id == view_id;
                       }),
        held_tasks.end());
    if (held_tasks.empty()) {
      CancelHeldSyncFrame();
    }
  }
  if (external_view_embedder_) {
    external_view_embedder_->CollectView(view_id);
  }
  view_records_.erase(view_id);
}

std::shared_ptr<flutter::TextureRegistry> Rasterizer::GetTextureRegistry() {
  return compositor_context_->texture_registry();
}

GrDirectContext* Rasterizer::GetGrContext() {
  return surface_ ? surface_->GetContext() : nullptr;
}

flutter::LayerTree* Rasterizer::GetLastLayerTree(int64_t view_id) {
  auto found = view_records_.find(view_id);
  if (found == view_records_.end()) {
    return nullptr;
  }
  auto& last_task = found->second.last_successful_task;
  if (last_task == nullptr) {
    return nullptr;
  }
  return last_task->layer_tree.get();
}

void Rasterizer::DrawLastLayerTrees(
    std::unique_ptr<FrameTimingsRecorder> frame_timings_recorder) {
  if (!surface_) {
    return;
  }
  if (held_sync_frame_.has_value()) {
    if (AreSyncRequirementsSatisfied(
            held_sync_frame_->frame_item->layer_tree_tasks,
            held_sync_frame_->active_requirements)) {
      ++sync_frames_latched_count_;
      FlushHeldSyncFrame();
    }
    return;
  }
  std::vector<std::unique_ptr<LayerTreeTask>> tasks;
  tasks.reserve(view_records_.size());
  for (auto& [view_id, view_record] : view_records_) {
    if (view_record.last_successful_task) {
      tasks.push_back(std::move(view_record.last_successful_task));
    }
  }
  if (tasks.empty()) {
    return;
  }

  DoDrawResult result =
      DrawToSurfaces(*frame_timings_recorder, std::move(tasks));

  // EndFrame should perform cleanups for the external_view_embedder.
  if (external_view_embedder_ && external_view_embedder_->GetUsedThisFrame()) {
    bool should_resubmit_frame = ShouldResubmitFrame(result);
    external_view_embedder_->SetUsedThisFrame(false);
    external_view_embedder_->EndFrame(should_resubmit_frame,
                                      raster_thread_merger_);
  }
}

DrawStatus Rasterizer::Draw(const std::shared_ptr<FramePipeline>& pipeline) {
  TRACE_EVENT0("flutter", "GPURasterizer::Draw");
  if (raster_thread_merger_ &&
      !raster_thread_merger_->IsOnRasterizingThread()) {
    // we yield and let this frame be serviced on the right thread.
    return DrawStatus::kYielded;
  }
  FML_DCHECK(delegate_.GetTaskRunners()
                 .GetRasterTaskRunner()
                 ->RunsTasksOnCurrentThread());

  DoDrawResult draw_result;
  FramePipeline::Consumer consumer = [&draw_result, &pipeline,
                                      this](std::unique_ptr<FrameItem> item) {
    std::vector<flutter::TextureSyncRequirement> carryover_requirements;
    std::optional<fml::TimePoint> inherited_deadline;
    if (held_sync_frame_.has_value()) {
      // Preserve any multi-view LayerTreeTask from the superseded held frame
      // whose view_id is not present in the incoming `item` so views not
      // updated in `item` still render their latest LayerTree.
      if (held_sync_frame_->frame_item && item) {
        for (auto& old_task : held_sync_frame_->frame_item->layer_tree_tasks) {
          if (!old_task) {
            continue;
          }
          const int64_t old_view_id = old_task->view_id;
          const bool replaced = std::any_of(
              item->layer_tree_tasks.begin(), item->layer_tree_tasks.end(),
              [old_view_id](const std::unique_ptr<LayerTreeTask>& new_task) {
                return new_task && new_task->view_id == old_view_id;
              });
          if (!replaced) {
            item->layer_tree_tasks.push_back(std::move(old_task));
          }
        }
      }
      carryover_requirements = std::move(held_sync_frame_->active_requirements);
      inherited_deadline = held_sync_frame_->deadline;
      CancelHeldSyncFrame();
      ++sync_frames_superseded_count_;
    }
    if (TryHoldFrameForSync(item, pipeline, carryover_requirements,
                            inherited_deadline)) {
      draw_result = DoDrawResult{.status = DoDrawStatus::kDone};
      return;
    }
    draw_result = DoDraw(std::move(item->frame_timings_recorder),
                         std::move(item->layer_tree_tasks));
  };

  PipelineConsumeResult consume_result = pipeline->Consume(consumer);
  if (consume_result == PipelineConsumeResult::NoneAvailable) {
    return DrawStatus::kPipelineEmpty;
  }
  // if the raster status is to resubmit the frame, we push the frame to the
  // front of the queue and also change the consume status to more available.

  bool should_resubmit_frame = ShouldResubmitFrame(draw_result);
  if (should_resubmit_frame) {
    FML_CHECK(draw_result.resubmitted_item);
    auto front_continuation = pipeline->ProduceIfEmpty();
    PipelineProduceResult pipeline_result =
        front_continuation.Complete(std::move(draw_result.resubmitted_item));
    if (pipeline_result.success) {
      consume_result = PipelineConsumeResult::MoreAvailable;
    }
  } else if (draw_result.status == DoDrawStatus::kEnqueuePipeline) {
    consume_result = PipelineConsumeResult::MoreAvailable;
  }

  // EndFrame should perform cleanups for the external_view_embedder.
  if (external_view_embedder_ && external_view_embedder_->GetUsedThisFrame()) {
    external_view_embedder_->SetUsedThisFrame(false);
    external_view_embedder_->EndFrame(should_resubmit_frame,
                                      raster_thread_merger_);
  }

  // Consume as many pipeline items as possible. But yield the event loop
  // between successive tries.
  switch (consume_result) {
    case PipelineConsumeResult::MoreAvailable: {
      delegate_.GetTaskRunners().GetRasterTaskRunner()->PostTask(
          [weak_this = weak_factory_.GetWeakPtr(), pipeline]() {
            if (weak_this) {
              weak_this->Draw(pipeline);
            }
          });
      break;
    }
    default:
      break;
  }

  return ToDrawStatus(draw_result.status);
}

bool Rasterizer::ShouldResubmitFrame(const DoDrawResult& result) {
  if (result.resubmitted_item) {
    FML_CHECK(!result.resubmitted_item->layer_tree_tasks.empty());
    return true;
  }
  return false;
}

DrawStatus Rasterizer::ToDrawStatus(DoDrawStatus status) {
  switch (status) {
    case DoDrawStatus::kEnqueuePipeline:
      return DrawStatus::kDone;
    case DoDrawStatus::kNotSetUp:
      return DrawStatus::kNotSetUp;
    case DoDrawStatus::kGpuUnavailable:
      return DrawStatus::kGpuUnavailable;
    case DoDrawStatus::kDone:
      return DrawStatus::kDone;
  }
  FML_UNREACHABLE();
}

#if !SLIMPELLER
namespace {
std::unique_ptr<SnapshotDelegate::GpuImageResult> MakeBitmapImage(
    const sk_sp<DisplayList>& display_list,
    const SkImageInfo& image_info) {
  FML_DCHECK(display_list);
  // Use 16384 as a proxy for the maximum texture size for a GPU image.
  // This is meant to be large enough to avoid false positives in test contexts,
  // but not so artificially large to be completely unrealistic on any platform.
  // This limit is taken from the Metal specification. D3D, Vulkan, and GL
  // generally have lower limits.
  if (image_info.width() > 16384 || image_info.height() > 16384) {
    return std::make_unique<SnapshotDelegate::GpuImageResult>(
        GrBackendTexture(), nullptr, nullptr,
        "unable to create bitmap render target at specified size " +
            std::to_string(image_info.width()) + "x" +
            std::to_string(image_info.height()));
  };

  sk_sp<SkSurface> surface = SkSurfaces::Raster(image_info);
  auto canvas = DlSkCanvasAdapter(surface->getCanvas());
  canvas.Clear(DlColor::kTransparent());
  canvas.DrawDisplayList(display_list);

  sk_sp<SkImage> image = surface->makeImageSnapshot();
  return std::make_unique<SnapshotDelegate::GpuImageResult>(
      GrBackendTexture(), nullptr, image,
      image ? "" : "Unable to create image");
}
}  // namespace
#endif  //  !SLIMPELLER

std::unique_ptr<Rasterizer::GpuImageResult> Rasterizer::MakeSkiaGpuImage(
    sk_sp<DisplayList> display_list,
    const SkImageInfo& image_info) {
#if SLIMPELLER
  FML_LOG(FATAL) << "Impeller opt-out unavailable.";
  return nullptr;
#else   // SLIMPELLER
  TRACE_EVENT0("flutter", "Rasterizer::MakeGpuImage");
  FML_DCHECK(display_list);

  std::unique_ptr<SnapshotDelegate::GpuImageResult> result;
  delegate_.GetIsGpuDisabledSyncSwitch()->Execute(
      fml::SyncSwitch::Handlers()
          .SetIfTrue([&result, &image_info, &display_list] {
            // TODO(dnfield): This isn't safe if display_list contains any GPU
            // resources like an SkImage_gpu.
            result = MakeBitmapImage(display_list, image_info);
          })
          .SetIfFalse([&result, &image_info, &display_list,
                       surface = surface_.get(),
                       gpu_image_behavior = gpu_image_behavior_] {
            if (!surface ||
                gpu_image_behavior == MakeGpuImageBehavior::kBitmap) {
              // TODO(dnfield): This isn't safe if display_list contains any GPU
              // resources like an SkImage_gpu.
              result = MakeBitmapImage(display_list, image_info);
              return;
            }

            auto context_switch = surface->MakeRenderContextCurrent();
            if (!context_switch->GetResult()) {
              result = MakeBitmapImage(display_list, image_info);
              return;
            }

            auto* context = surface->GetContext();
            if (!context) {
              result = MakeBitmapImage(display_list, image_info);
              return;
            }

            GrBackendTexture texture = context->createBackendTexture(
                image_info.width(), image_info.height(), image_info.colorType(),
                skgpu::Mipmapped::kNo, GrRenderable::kYes);
            if (!texture.isValid()) {
              result = std::make_unique<SnapshotDelegate::GpuImageResult>(
                  GrBackendTexture(), nullptr, nullptr,
                  "unable to create texture render target at specified size " +
                      std::to_string(image_info.width()) + "x" +
                      std::to_string(image_info.height()));
              return;
            }

            sk_sp<SkSurface> sk_surface = SkSurfaces::WrapBackendTexture(
                context, texture, kTopLeft_GrSurfaceOrigin, /*sampleCnt=*/0,
                image_info.colorType(), image_info.refColorSpace(), nullptr);
            if (!sk_surface) {
              result = std::make_unique<SnapshotDelegate::GpuImageResult>(
                  GrBackendTexture(), nullptr, nullptr,
                  "unable to create rendering surface for image");
              return;
            }

            auto canvas = DlSkCanvasAdapter(sk_surface->getCanvas());
            canvas.Clear(DlColor::kTransparent());
            canvas.DrawDisplayList(display_list);

            result = std::make_unique<SnapshotDelegate::GpuImageResult>(
                texture, sk_ref_sp(context), nullptr, "");
          }));
  return result;
#endif  //  !SLIMPELLER
}

void Rasterizer::MakeSkiaSnapshot(sk_sp<DisplayList> display_list,
                                  DlISize picture_size,
                                  std::function<void(sk_sp<SkImage>)> callback,
                                  SnapshotPixelFormat pixel_format) {
  return snapshot_controller_->MakeSkiaSnapshot(
      display_list, picture_size, std::move(callback), pixel_format);
}

sk_sp<SkImage> Rasterizer::MakeSkiaSnapshotSync(
    sk_sp<DisplayList> display_list,
    DlISize picture_size,
    SnapshotPixelFormat pixel_format) {
  return snapshot_controller_->MakeSkiaSnapshotSync(std::move(display_list),
                                                    picture_size, pixel_format);
}

void Rasterizer::MakeImpellerSnapshot(
    SnapshotDisplayListProvider provider,
    DlISize picture_size,
    std::function<void(std::shared_ptr<impeller::Texture>)> callback,
    SnapshotPixelFormat pixel_format) {
  return snapshot_controller_->MakeImpellerSnapshot(
      std::move(provider), picture_size, std::move(callback), pixel_format);
}

std::shared_ptr<impeller::Texture> Rasterizer::MakeImpellerSnapshotSync(
    SnapshotDisplayListProvider provider,
    DlISize picture_size,
    SnapshotPixelFormat pixel_format) {
  return snapshot_controller_->MakeImpellerSnapshotSync(
      std::move(provider), picture_size, pixel_format);
}

sk_sp<SkImage> Rasterizer::ConvertToRasterImage(sk_sp<SkImage> image) {
  TRACE_EVENT0("flutter", __FUNCTION__);
  return snapshot_controller_->ConvertToRasterImage(image);
}

sk_sp<SkImage> Rasterizer::MakeSkiaTextureImage(
    sk_sp<SkImage> image,
    SnapshotPixelFormat pixel_format) {
  TRACE_EVENT0("flutter", __FUNCTION__);
  return snapshot_controller_->MakeSkiaTextureImage(image, pixel_format);
}

std::shared_ptr<impeller::Texture> Rasterizer::MakeImpellerTextureImage(
    sk_sp<SkImage> image,
    SnapshotPixelFormat pixel_format) {
  TRACE_EVENT0("flutter", __FUNCTION__);
  return snapshot_controller_->MakeImpellerTextureImage(image, pixel_format);
}

// |SnapshotDelegate|
void Rasterizer::CacheRuntimeStage(
    const std::shared_ptr<impeller::RuntimeStage>& runtime_stage) {
  if (snapshot_controller_) {
    snapshot_controller_->CacheRuntimeStage(runtime_stage);
  }
}

// |SnapshotDelegate|
bool Rasterizer::MakeRenderContextCurrent() {
  return snapshot_controller_->MakeRenderContextCurrent();
}

fml::Milliseconds Rasterizer::GetFrameBudget() const {
  return delegate_.GetFrameBudget();
};

Rasterizer::DoDrawResult Rasterizer::DoDraw(
    std::unique_ptr<FrameTimingsRecorder> frame_timings_recorder,
    std::vector<std::unique_ptr<LayerTreeTask>> tasks) {
  TRACE_EVENT_WITH_FRAME_NUMBER(frame_timings_recorder, "flutter",
                                "Rasterizer::DoDraw", /*flow_id_count=*/0,
                                /*flow_ids=*/nullptr);
  FML_DCHECK(delegate_.GetTaskRunners()
                 .GetRasterTaskRunner()
                 ->RunsTasksOnCurrentThread());
  frame_timings_recorder->AssertInState(FrameTimingsRecorder::State::kBuildEnd);

  if (tasks.empty()) {
    return DoDrawResult{DoDrawStatus::kDone};
  }
  if (!surface_) {
    return DoDrawResult{DoDrawStatus::kNotSetUp};
  }

#if !SLIMPELLER
  PersistentCache* persistent_cache = PersistentCache::GetCacheForProcess();
  persistent_cache->ResetStoredNewShaders();
#endif  //  !SLIMPELLER

  DoDrawResult result =
      DrawToSurfaces(*frame_timings_recorder, std::move(tasks));

  FML_DCHECK(result.status != DoDrawStatus::kEnqueuePipeline);
  if (result.status == DoDrawStatus::kGpuUnavailable) {
    return DoDrawResult{DoDrawStatus::kGpuUnavailable};
  }

#if !SLIMPELLER
  if (persistent_cache->IsDumpingSkp() &&
      persistent_cache->StoredNewShaders()) {
    auto screenshot =
        ScreenshotLastLayerTree(ScreenshotType::SkiaPicture, false);
    persistent_cache->DumpSkp(*screenshot.data);
  }
#endif  //  !SLIMPELLER

  // TODO(liyuqian): in Fuchsia, the rasterization doesn't finish when
  // Rasterizer::DoDraw finishes. Future work is needed to adapt the timestamp
  // for Fuchsia to capture SceneUpdateContext::ExecutePaintTasks.
  delegate_.OnFrameRasterized(frame_timings_recorder->GetRecordedTime());

// SceneDisplayLag events are disabled on Fuchsia.
// see: https://github.com/flutter/flutter/issues/56598
#if !defined(OS_FUCHSIA)
  const fml::TimePoint raster_finish_time =
      frame_timings_recorder->GetRasterEndTime();
  fml::TimePoint frame_target_time =
      frame_timings_recorder->GetVsyncTargetTime();
  if (raster_finish_time > frame_target_time) {
    fml::TimePoint latest_frame_target_time =
        delegate_.GetLatestFrameTargetTime();
    const auto frame_budget_millis = delegate_.GetFrameBudget().count();
    if (latest_frame_target_time < raster_finish_time) {
      latest_frame_target_time =
          latest_frame_target_time +
          fml::TimeDelta::FromMillisecondsF(frame_budget_millis);
    }
    const auto frame_lag =
        (latest_frame_target_time - frame_target_time).ToMillisecondsF();
    const int vsync_transitions_missed = round(frame_lag / frame_budget_millis);
    fml::tracing::TraceEventAsyncComplete(
        "flutter",                    // category
        "SceneDisplayLag",            // name
        raster_finish_time,           // begin_time
        latest_frame_target_time,     // end_time
        "frame_target_time",          // arg_key_1
        frame_target_time,            // arg_val_1
        "current_frame_target_time",  // arg_key_2
        latest_frame_target_time,     // arg_val_2
        "vsync_transitions_missed",   // arg_key_3
        vsync_transitions_missed      // arg_val_3
    );
  }
#endif

  // Pipeline pressure is applied from a couple of places:
  // rasterizer: When there are more items as of the time of Consume.
  // animator (via shell): Frame gets produces every vsync.
  // Enqueing here is to account for the following scenario:
  // T = 1
  //  - one item (A) in the pipeline
  //  - rasterizer starts (and merges the threads)
  //  - pipeline consume result says no items to process
  // T = 2
  //  - animator produces (B) to the pipeline
  //  - applies pipeline pressure via platform thread.
  // T = 3
  //   - rasterizes finished (and un-merges the threads)
  //   - |Draw| for B yields as its on the wrong thread.
  // This enqueue ensures that we attempt to consume from the right
  // thread one more time after un-merge.
  if (raster_thread_merger_) {
    if (raster_thread_merger_->DecrementLease() ==
        fml::RasterThreadStatus::kUnmergedNow) {
      return DoDrawResult{
          .status = DoDrawStatus::kEnqueuePipeline,
          .resubmitted_item = std::move(result.resubmitted_item),
      };
    }
  }

  return result;
}

Rasterizer::DoDrawResult Rasterizer::DrawToSurfaces(
    FrameTimingsRecorder& frame_timings_recorder,
    std::vector<std::unique_ptr<LayerTreeTask>> tasks) {
  TRACE_EVENT0("flutter", "Rasterizer::DrawToSurfaces");
  FML_DCHECK(surface_);
  frame_timings_recorder.AssertInState(FrameTimingsRecorder::State::kBuildEnd);

  DoDrawResult result{
      .status = DoDrawStatus::kDone,
  };
  if (surface_->AllowsDrawingWhenGpuDisabled()) {
    result.resubmitted_item =
        DrawToSurfacesUnsafe(frame_timings_recorder, std::move(tasks));
  } else {
    delegate_.GetIsGpuDisabledSyncSwitch()->Execute(
        fml::SyncSwitch::Handlers()
            .SetIfTrue([&] {
              result.status = DoDrawStatus::kGpuUnavailable;
              frame_timings_recorder.RecordRasterStart(fml::TimePoint::Now());
              frame_timings_recorder.RecordRasterEnd();
            })
            .SetIfFalse([&] {
              result.resubmitted_item = DrawToSurfacesUnsafe(
                  frame_timings_recorder, std::move(tasks));
            }));
  }
  frame_timings_recorder.AssertInState(FrameTimingsRecorder::State::kRasterEnd);

  return result;
}

std::unique_ptr<FrameItem> Rasterizer::DrawToSurfacesUnsafe(
    FrameTimingsRecorder& frame_timings_recorder,
    std::vector<std::unique_ptr<LayerTreeTask>> tasks) {
  compositor_context_->ui_time().SetLapTime(
      frame_timings_recorder.GetBuildDuration());

  // First traverse: Filter out discarded trees
  auto task_iter = tasks.begin();
  while (task_iter != tasks.end()) {
    LayerTreeTask& task = **task_iter;
    if (delegate_.ShouldDiscardLayerTree(task.view_id, *task.layer_tree)) {
      EnsureViewRecord(task.view_id).last_draw_status =
          DrawSurfaceStatus::kDiscarded;
      task_iter = tasks.erase(task_iter);
    } else {
      ++task_iter;
    }
  }
  if (tasks.empty()) {
    frame_timings_recorder.RecordRasterStart(fml::TimePoint::Now());
    frame_timings_recorder.RecordRasterEnd();
    return nullptr;
  }

  if (external_view_embedder_) {
    FML_DCHECK(!external_view_embedder_->GetUsedThisFrame());
    external_view_embedder_->SetUsedThisFrame(true);
    external_view_embedder_->BeginFrame(surface_->GetContext(),
                                        raster_thread_merger_);
  }

  std::optional<fml::TimePoint> presentation_time = std::nullopt;
  // TODO (https://github.com/flutter/flutter/issues/105596): this can be in
  // the past and might need to get snapped to future as this frame could
  // have been resubmitted. `presentation_time` on SubmitInfo is not set
  // in this case.
  {
    const auto vsync_target_time = frame_timings_recorder.GetVsyncTargetTime();
    if (vsync_target_time > fml::TimePoint::Now()) {
      presentation_time = vsync_target_time;
    }
  }

  frame_timings_recorder.RecordRasterStart(fml::TimePoint::Now());

  // Second traverse: draw all layer trees.
  std::vector<std::unique_ptr<LayerTreeTask>> resubmitted_tasks;
  for (std::unique_ptr<LayerTreeTask>& task : tasks) {
    int64_t view_id = task->view_id;
    float device_pixel_ratio = task->device_pixel_ratio;

    DrawSurfaceStatus status = DrawToSurfaceUnsafe(
        view_id, *task->layer_tree, device_pixel_ratio, presentation_time);
    FML_DCHECK(status != DrawSurfaceStatus::kDiscarded);

    auto& view_record = EnsureViewRecord(view_id);
    view_record.last_draw_status = status;
    if (status == DrawSurfaceStatus::kSuccess) {
      task->layer_tree->MarkPrerollReusable();
      view_record.last_successful_task = std::move(task);
    } else if (status == DrawSurfaceStatus::kRetry) {
      resubmitted_tasks.push_back(std::move(task));
    } else if (!view_record.last_successful_task &&
               task->layer_tree->IsPrerollReusable()) {
      view_record.last_successful_task = std::move(task);
    }
  }
  // TODO(dkwingsmt): Pass in raster cache(s) for all views.
  // See https://github.com/flutter/flutter/issues/135530, item 4.
  frame_timings_recorder.RecordRasterEnd(
      NOT_SLIMPELLER(&compositor_context_->raster_cache()));

  FireNextFrameCallbackIfPresent();

#if !SLIMPELLER
  if (surface_->GetContext()) {
    surface_->GetContext()->performDeferredCleanup(kSkiaCleanupExpiration);
  }
#endif  //  !SLIMPELLER

  if (resubmitted_tasks.empty()) {
    return nullptr;
  } else {
    return std::make_unique<FrameItem>(
        std::move(resubmitted_tasks),
        frame_timings_recorder.CloneUntil(
            FrameTimingsRecorder::State::kBuildEnd));
  }
}

/// \see Rasterizer::DrawToSurfaces
DrawSurfaceStatus Rasterizer::DrawToSurfaceUnsafe(
    int64_t view_id,
    flutter::LayerTree& layer_tree,
    float device_pixel_ratio,
    std::optional<fml::TimePoint> presentation_time) {
  FML_DCHECK(surface_);

  DlCanvas* embedder_root_canvas = nullptr;
  if (external_view_embedder_) {
    external_view_embedder_->PrepareFlutterView(layer_tree.frame_size(),
                                                device_pixel_ratio);
    // TODO(dkwingsmt): Add view ID here.
    embedder_root_canvas = external_view_embedder_->GetRootCanvas();
  }

  // On Android, the external view embedder deletes surfaces in `BeginFrame`.
  //
  // Deleting a surface also clears the GL context. Therefore, acquire the
  // frame after calling `BeginFrame` as this operation resets the GL context.
  auto frame = surface_->AcquireFrame(layer_tree.frame_size());
  if (frame == nullptr) {
    return DrawSurfaceStatus::kFailed;
  }

  // If the external view embedder has specified an optional root surface, the
  // root surface transformation is set by the embedder instead of
  // having to apply it here.
  DlMatrix root_surface_transformation =
      embedder_root_canvas ? DlMatrix() : surface_->GetRootTransformation();

  auto root_surface_canvas =
      embedder_root_canvas ? embedder_root_canvas : frame->Canvas();
  auto compositor_frame = compositor_context_->AcquireFrame(
      surface_->GetContext(),         // skia GrContext
      root_surface_canvas,            // root surface canvas
      external_view_embedder_.get(),  // external view embedder
      root_surface_transformation,    // root surface transformation
      true,                           // instrumentation enabled
      frame->framebuffer_info()
          .supports_readback,           // surface supports pixel reads
      raster_thread_merger_,            // thread merger
      surface_->GetAiksContext().get()  // aiks context
  );
  if (compositor_frame) {
    NOT_SLIMPELLER(compositor_context_->raster_cache().BeginFrame());

    std::unique_ptr<FrameDamage> damage;
    // when leaf layer tracing is enabled we wish to repaint the whole frame
    // for accurate performance metrics.
    if (frame->framebuffer_info().supports_partial_repaint) {
      // Disable partial repaint if external_view_embedder_ SubmitFlutterView is
      // involved - ExternalViewEmbedder unconditionally clears the entire
      // surface and also partial repaint with platform view present is
      // something that still need to be figured out.
      bool force_full_repaint =
          external_view_embedder_ &&
          (!raster_thread_merger_ || raster_thread_merger_->IsMerged());

      damage = std::make_unique<FrameDamage>();
      auto existing_damage = frame->framebuffer_info().existing_damage;
      if (existing_damage.has_value() && !force_full_repaint) {
        damage->SetPreviousLayerTree(GetLastLayerTree(view_id));
        damage->AddAdditionalDamage(existing_damage.value());
        damage->SetClipAlignment(
            frame->framebuffer_info().horizontal_clip_alignment,
            frame->framebuffer_info().vertical_clip_alignment);
      }
    }

    bool ignore_raster_cache = true;
    if (surface_->EnableRasterCache()) {
      ignore_raster_cache = false;
    }

    RasterStatus frame_status =
        compositor_frame->Raster(layer_tree,           // layer tree
                                 ignore_raster_cache,  // ignore raster cache
                                 damage.get()          // frame damage
        );
    if (frame_status == RasterStatus::kSkipAndRetry) {
      return DrawSurfaceStatus::kRetry;
    }

    SurfaceFrame::SubmitInfo submit_info;
    submit_info.presentation_time = presentation_time;
    if (damage) {
      submit_info.frame_damage = damage->GetFrameDamage();
      submit_info.buffer_damage = damage->GetBufferDamage();
    }

    frame->set_submit_info(submit_info);

    if (external_view_embedder_ &&
        (!raster_thread_merger_ || raster_thread_merger_->IsMerged())) {
      FML_DCHECK(!frame->IsSubmitted());
      external_view_embedder_->SubmitFlutterView(
          view_id, surface_->GetContext(), surface_->GetAiksContext(),
          std::move(frame));
    } else {
      frame->Submit();
    }

#if !SLIMPELLER
    // Do not update raster cache metrics for kResubmit because that status
    // indicates that the frame was not actually painted.
    if (frame_status != RasterStatus::kResubmit) {
      compositor_context_->raster_cache().EndFrame();
    }
#endif  //  !SLIMPELLER

    if (frame_status == RasterStatus::kResubmit) {
      return DrawSurfaceStatus::kRetry;
    } else {
      FML_CHECK(frame_status == RasterStatus::kSuccess);
      return DrawSurfaceStatus::kSuccess;
    }
  }

  return DrawSurfaceStatus::kFailed;
}

Rasterizer::ViewRecord& Rasterizer::EnsureViewRecord(int64_t view_id) {
  return view_records_[view_id];
}

static sk_sp<SkData> ScreenshotLayerTreeAsPicture(
    flutter::LayerTree* tree,
    flutter::CompositorContext& compositor_context) {
#if SLIMPELLER
  return nullptr;
#else  // SLIMPELLER
  FML_DCHECK(tree != nullptr);
  SkPictureRecorder recorder;
  recorder.beginRecording(
      SkRect::MakeWH(tree->frame_size().width, tree->frame_size().height));

  DlMatrix root_surface_transformation;
  DlSkCanvasAdapter canvas(recorder.getRecordingCanvas());

  // TODO(amirh): figure out how to take a screenshot with embedded UIView.
  // https://github.com/flutter/flutter/issues/23435
  auto frame = compositor_context.AcquireFrame(nullptr, &canvas, nullptr,
                                               root_surface_transformation,
                                               false, true, nullptr, nullptr);
  frame->Raster(*tree, true, nullptr);

#if defined(OS_FUCHSIA)
  SkSerialProcs procs = {0};
  procs.fImageProc = SerializeImageWithoutData;
  procs.fTypefaceProc = SerializeTypefaceWithoutData;
#else
  SkSerialProcs procs = {0};
  procs.fTypefaceProc = SerializeTypefaceWithData;
  procs.fImageProc = [](SkImage* img, void*) -> SkSerialReturnType {
    return SkPngEncoder::Encode(nullptr, img, SkPngEncoder::Options{});
  };
#endif

  return recorder.finishRecordingAsPicture()->serialize(&procs);
#endif  //  SLIMPELLER
}

static void RenderFrameForScreenshot(
    flutter::CompositorContext& compositor_context,
    DlCanvas* canvas,
    flutter::LayerTree* tree,
    GrDirectContext* surface_context,
    const std::shared_ptr<impeller::AiksContext>& aiks_context) {
  // There is no root surface transformation for the screenshot layer. Reset
  // the matrix to identity.
  DlMatrix root_surface_transformation;

  auto frame = compositor_context.AcquireFrame(
      /*gr_context=*/surface_context,
      /*canvas=*/canvas,
      /*view_embedder=*/nullptr,
      /*root_surface_transformation=*/root_surface_transformation,
      /*instrumentation_enabled=*/false,
      /*surface_supports_readback=*/true,
      /*raster_thread_merger=*/nullptr,
      /*aiks_context=*/aiks_context.get());
  canvas->Clear(DlColor::kTransparent());
  frame->Raster(*tree, true, nullptr);
  canvas->Flush();
}

#if IMPELLER_SUPPORTS_RENDERING
Rasterizer::ScreenshotFormat ToScreenshotFormat(impeller::PixelFormat format) {
  switch (format) {
    case impeller::PixelFormat::kUnknown:
    case impeller::PixelFormat::kA8UNormInt:
    case impeller::PixelFormat::kR8UNormInt:
    case impeller::PixelFormat::kR8G8UNormInt:
    case impeller::PixelFormat::kR8G8B8A8UNormIntSRGB:
    case impeller::PixelFormat::kB8G8R8A8UNormIntSRGB:
    case impeller::PixelFormat::kB10G10R10XRSRGB:
    case impeller::PixelFormat::kS8UInt:
    case impeller::PixelFormat::kD24UnormS8Uint:
    case impeller::PixelFormat::kD32FloatS8UInt:
    case impeller::PixelFormat::kR32G32B32A32Float:
    case impeller::PixelFormat::kB10G10R10XR:
    case impeller::PixelFormat::kB10G10R10A10XR:
    case impeller::PixelFormat::kR32Float:
    case impeller::PixelFormat::kBC1RGBAUNormInt:
    case impeller::PixelFormat::kBC1RGBAUNormIntSRGB:
    case impeller::PixelFormat::kBC3RGBAUNormInt:
    case impeller::PixelFormat::kBC3RGBAUNormIntSRGB:
    case impeller::PixelFormat::kBC5RGUNormInt:
    case impeller::PixelFormat::kBC7RGBAUNormInt:
    case impeller::PixelFormat::kBC7RGBAUNormIntSRGB:
    case impeller::PixelFormat::kETC2RGB8UNormInt:
    case impeller::PixelFormat::kETC2RGB8UNormIntSRGB:
    case impeller::PixelFormat::kETC2RGBA8UNormInt:
    case impeller::PixelFormat::kETC2RGBA8UNormIntSRGB:
    case impeller::PixelFormat::kASTC4x4LDR:
    case impeller::PixelFormat::kASTC4x4LDRSRGB:
    case impeller::PixelFormat::kASTC8x8LDR:
    case impeller::PixelFormat::kASTC8x8LDRSRGB:
    case impeller::PixelFormat::kASTC4x4HDR:
    case impeller::PixelFormat::kASTC8x8HDR:
      FML_DCHECK(false);
      return Rasterizer::ScreenshotFormat::kUnknown;
    case impeller::PixelFormat::kR8G8B8A8UNormInt:
      return Rasterizer::ScreenshotFormat::kR8G8B8A8UNormInt;
    case impeller::PixelFormat::kB8G8R8A8UNormInt:
      return Rasterizer::ScreenshotFormat::kB8G8R8A8UNormInt;
    case impeller::PixelFormat::kR16G16B16A16Float:
      return Rasterizer::ScreenshotFormat::kR16G16B16A16Float;
  }
}

static std::pair<sk_sp<SkData>, Rasterizer::ScreenshotFormat>
ScreenshotLayerTreeAsImageImpeller(
    const std::shared_ptr<impeller::AiksContext>& aiks_context,
    flutter::LayerTree* tree,
    flutter::CompositorContext& compositor_context,
    bool compressed) {
  if (compressed) {
    FML_LOG(ERROR) << "Compressed screenshots not supported for Impeller";
    return {nullptr, Rasterizer::ScreenshotFormat::kUnknown};
  }

  DisplayListBuilder builder(DlRect::MakeSize(tree->frame_size()));

  RenderFrameForScreenshot(compositor_context, &builder, tree, nullptr,
                           aiks_context);

  std::shared_ptr<impeller::Texture> texture = impeller::DisplayListToTexture(
      builder.Build(), impeller::ISize(tree->frame_size()), *aiks_context);
  if (!texture) {
    FML_LOG(ERROR) << "Failed to render to texture";
    return {nullptr, Rasterizer::ScreenshotFormat::kUnknown};
  }

  impeller::DeviceBufferDescriptor buffer_desc;
  buffer_desc.storage_mode = impeller::StorageMode::kHostVisible;
  buffer_desc.size =
      texture->GetTextureDescriptor().GetByteSizeOfBaseMipLevel();
  auto impeller_context = aiks_context->GetContext();
  auto buffer =
      impeller_context->GetResourceAllocator()->CreateBuffer(buffer_desc);
  auto command_buffer = impeller_context->CreateCommandBuffer();
  command_buffer->SetLabel("BlitTextureToBuffer Command Buffer");
  auto pass = command_buffer->CreateBlitPass();
  pass->AddCopy(texture, buffer);
  pass->EncodeCommands();
  fml::AutoResetWaitableEvent latch;
  sk_sp<SkData> sk_data;
  auto completion = [buffer, &buffer_desc, &sk_data,
                     &latch](impeller::CommandBuffer::Status status) {
    fml::ScopedCleanupClosure cleanup([&latch]() { latch.Signal(); });
    if (status != impeller::CommandBuffer::Status::kCompleted) {
      FML_LOG(ERROR) << "Failed to complete blit pass.";
      return;
    }
    sk_data = SkData::MakeWithCopy(buffer->OnGetContents(), buffer_desc.size);
  };

  if (!impeller_context->GetCommandQueue()
           ->Submit({command_buffer}, completion)
           .ok()) {
    FML_LOG(ERROR) << "Failed to submit commands.";
  }
  latch.Wait();
  return std::make_pair(
      sk_data, ToScreenshotFormat(texture->GetTextureDescriptor().format));
}
#endif

std::pair<sk_sp<SkData>, Rasterizer::ScreenshotFormat>
Rasterizer::ScreenshotLayerTreeAsImage(
    flutter::LayerTree* tree,
    flutter::CompositorContext& compositor_context,
    bool compressed) {
#if IMPELLER_SUPPORTS_RENDERING
  if (delegate_.GetSettings().enable_impeller) {
    return ScreenshotLayerTreeAsImageImpeller(GetAiksContext(), tree,
                                              compositor_context, compressed);
  }
#endif  // IMPELLER_SUPPORTS_RENDERING

#if SLIMPELLER
  FML_LOG(FATAL) << "Impeller opt-out unavailable.";
  return {nullptr, ScreenshotFormat::kUnknown};
#else   // SLIMPELLER
  GrDirectContext* surface_context = GetGrContext();
  // Attempt to create a snapshot surface depending on whether we have access
  // to a valid GPU rendering context.
  std::unique_ptr<OffscreenSurface> snapshot_surface =
      std::make_unique<OffscreenSurface>(surface_context, tree->frame_size());

  if (!snapshot_surface->IsValid()) {
    FML_LOG(ERROR) << "Screenshot: unable to create snapshot surface";
    return {nullptr, ScreenshotFormat::kUnknown};
  }

  // Draw the current layer tree into the snapshot surface.
  DlCanvas* canvas = snapshot_surface->GetCanvas();

  // snapshot_surface->makeImageSnapshot needs the GL context to be set if the
  // render context is GL. frame->Raster() pops the gl context in platforms
  // that gl context switching are used. (For example, older iOS that uses GL)
  // We reset the GL context using the context switch.
  auto context_switch = surface_->MakeRenderContextCurrent();
  if (!context_switch->GetResult()) {
    FML_LOG(ERROR) << "Screenshot: unable to make image screenshot";
    return {nullptr, ScreenshotFormat::kUnknown};
  }

  RenderFrameForScreenshot(compositor_context, canvas, tree, surface_context,
                           nullptr);

  return std::make_pair(snapshot_surface->GetRasterData(compressed),
                        ScreenshotFormat::kUnknown);
#endif  //  !SLIMPELLER
}

Rasterizer::Screenshot Rasterizer::ScreenshotLastLayerTree(
    Rasterizer::ScreenshotType type,
    bool base64_encode) {
  if (delegate_.GetSettings().enable_impeller &&
      type == ScreenshotType::SkiaPicture) {
    FML_DCHECK(false);
    FML_LOG(ERROR) << "Last layer tree cannot be screenshotted as a "
                      "SkiaPicture when using Impeller.";
    return {};
  }
  // TODO(dkwingsmt): Support screenshotting all last layer trees
  // when the shell protocol supports multi-views.
  // https://github.com/flutter/flutter/issues/135534
  // https://github.com/flutter/flutter/issues/135535
  auto* layer_tree = GetLastLayerTree(kFlutterImplicitViewId);
  if (layer_tree == nullptr) {
    FML_LOG(ERROR) << "Last layer tree was null when screenshotting.";
    return {};
  }

  std::pair<sk_sp<SkData>, ScreenshotFormat> data{nullptr,
                                                  ScreenshotFormat::kUnknown};
  std::string format;

  switch (type) {
    case ScreenshotType::SkiaPicture:
      format = "ScreenshotType::SkiaPicture";
      data.first =
          ScreenshotLayerTreeAsPicture(layer_tree, *compositor_context_);
      break;
    case ScreenshotType::UncompressedImage:
      format = "ScreenshotType::UncompressedImage";
      data =
          ScreenshotLayerTreeAsImage(layer_tree, *compositor_context_, false);
      break;
    case ScreenshotType::CompressedImage:
      format = "ScreenshotType::CompressedImage";
      data = ScreenshotLayerTreeAsImage(layer_tree, *compositor_context_, true);
      break;
    case ScreenshotType::SurfaceData: {
      Surface::SurfaceData surface_data = surface_->GetSurfaceData();
      format = surface_data.pixel_format;
      data.first = surface_data.data;
      break;
    }
  }

  if (data.first == nullptr) {
    FML_LOG(ERROR) << "Screenshot data was null.";
    return {};
  }

  if (base64_encode) {
    size_t b64_size = Base64::EncodedSize(data.first->size());
    auto b64_data = SkData::MakeUninitialized(b64_size);
    Base64::Encode(data.first->data(), data.first->size(),
                   b64_data->writable_data());
    return Rasterizer::Screenshot{b64_data, layer_tree->frame_size(), format,
                                  data.second};
  }

  return Rasterizer::Screenshot{data.first, layer_tree->frame_size(), format,
                                data.second};
}

void Rasterizer::SetNextFrameCallback(const fml::closure& callback) {
  next_frame_callback_ = callback;
}

void Rasterizer::SetExternalViewEmbedder(
    const std::shared_ptr<ExternalViewEmbedder>& view_embedder) {
  external_view_embedder_ = view_embedder;
}

void Rasterizer::SetSnapshotSurfaceProducer(
    std::unique_ptr<SnapshotSurfaceProducer> producer) {
  snapshot_surface_producer_ = std::move(producer);
}

fml::RefPtr<fml::RasterThreadMerger> Rasterizer::GetRasterThreadMerger() {
  return raster_thread_merger_;
}

void Rasterizer::FireNextFrameCallbackIfPresent() {
  if (!next_frame_callback_) {
    return;
  }
  // It is safe for the callback to set a new callback.
  auto callback = next_frame_callback_;
  next_frame_callback_ = nullptr;
  callback();
}

void Rasterizer::SetResourceCacheMaxBytes(size_t max_bytes, bool from_user) {
#if !SLIMPELLER
  user_override_resource_cache_bytes_ |= from_user;

  if (!from_user && user_override_resource_cache_bytes_) {
    // We should not update the setting here if a user has explicitly set a
    // value for this over the flutter/skia channel.
    return;
  }

  max_cache_bytes_ = max_bytes;
  if (!surface_) {
    return;
  }

  GrDirectContext* context = surface_->GetContext();
  if (context) {
    auto context_switch = surface_->MakeRenderContextCurrent();
    if (!context_switch->GetResult()) {
      return;
    }

    context->setResourceCacheLimit(max_bytes);
  }
#endif  //  !SLIMPELLER
}

std::optional<size_t> Rasterizer::GetResourceCacheMaxBytes() const {
#if SLIMPELLER
  return std::nullopt;
#else   // SLIMPELLER
  if (!surface_) {
    return std::nullopt;
  }
  GrDirectContext* context = surface_->GetContext();
  if (context) {
    return context->getResourceCacheLimit();
  }
  return std::nullopt;
#endif  //  SLIMPELLER
}

Rasterizer::Screenshot::Screenshot() {}

Rasterizer::Screenshot::Screenshot(sk_sp<SkData> p_data,
                                   DlISize p_size,
                                   const std::string& p_format,
                                   ScreenshotFormat p_pixel_format)
    : data(std::move(p_data)),
      frame_size(p_size),
      format(p_format),
      pixel_format(p_pixel_format) {}

Rasterizer::Screenshot::Screenshot(const Screenshot& other) = default;

Rasterizer::Screenshot::~Screenshot() = default;

bool Rasterizer::CollectUnsatisfiedSyncRequirements(
    const std::vector<std::unique_ptr<LayerTreeTask>>& tasks,
    const std::vector<flutter::TextureSyncRequirement>& carryover_requirements,
    std::vector<UnsatisfiedSyncEntry>& out_unsatisfied) const {
  out_unsatisfied.clear();
  if (!compositor_context_) {
    return false;
  }
  auto texture_registry = compositor_context_->texture_registry();
  if (!texture_registry) {
    return false;
  }

  std::vector<flutter::TextureSyncRequirement> merged;
  auto merge_req = [&merged](const flutter::TextureSyncRequirement& req) {
    if (!req.IsActive()) {
      return;
    }
    for (auto& existing : merged) {
      if (existing.texture_id == req.texture_id) {
        existing.min_frame_generation =
            std::max(existing.min_frame_generation, req.min_frame_generation);
        if (!req.expected_physical_size.isEmpty()) {
          existing.expected_physical_size = req.expected_physical_size;
        }
        existing.max_wait =
            std::max(existing.ClampedMaxWait(), req.ClampedMaxWait());
        return;
      }
    }
    flutter::TextureSyncRequirement copy = req;
    copy.max_wait = req.ClampedMaxWait();
    merged.push_back(copy);
  };

  for (const auto& req : carryover_requirements) {
    merge_req(req);
  }

  for (const auto& task : tasks) {
    if (!task || !task->layer_tree) {
      continue;
    }
    if (delegate_.ShouldDiscardLayerTree(task->view_id, *task->layer_tree)) {
      continue;
    }
    const auto& reqs = task->layer_tree->CollectTextureSyncRequirements();
    for (const auto& req : reqs) {
      merge_req(req);
    }
  }

  for (const auto& req : merged) {
    std::shared_ptr<flutter::Texture> texture =
        texture_registry->GetTexture(req.texture_id);
    if (!texture) {
      timed_out_sync_records_.erase(req.texture_id);
      continue;
    }
    if (texture->SatisfiesSyncRequirement(req.min_frame_generation,
                                          req.expected_physical_size)) {
      timed_out_sync_records_.erase(req.texture_id);
      continue;
    }
    auto prev_timeout = timed_out_sync_records_.find(req.texture_id);
    if (prev_timeout != timed_out_sync_records_.end()) {
      const TimedOutSyncRecord& rec = prev_timeout->second;
      const uint64_t cur_gen = texture->GetCurrentFrameGeneration();
      const SkISize cur_size = texture->GetCurrentFrameSize();
      if (rec.min_generation == req.min_frame_generation &&
          rec.expected_size == req.expected_physical_size &&
          rec.texture_generation_at_timeout == cur_gen &&
          rec.texture_size_at_timeout == cur_size) {
        // Identical requirement already timed out and the producer has not
        // published any new frame yet; fast-path so a stuck or DPR-mismatched
        // producer does not re-stall every subsequent frame.
        continue;
      }
      timed_out_sync_records_.erase(prev_timeout);
    }
    out_unsatisfied.push_back(UnsatisfiedSyncEntry{
        .texture = std::move(texture),
        .requirement = req,
    });
  }
  return !out_unsatisfied.empty();
}

void Rasterizer::RecordTimedOutRequirements(
    const std::vector<flutter::TextureSyncRequirement>& requirements) {
  if (!compositor_context_) {
    return;
  }
  auto texture_registry = compositor_context_->texture_registry();
  if (!texture_registry) {
    return;
  }
  for (const auto& req : requirements) {
    std::shared_ptr<flutter::Texture> texture =
        texture_registry->GetTexture(req.texture_id);
    if (!texture) {
      continue;
    }
    timed_out_sync_records_[req.texture_id] = TimedOutSyncRecord{
        .min_generation = req.min_frame_generation,
        .expected_size = req.expected_physical_size,
        .texture_generation_at_timeout = texture->GetCurrentFrameGeneration(),
        .texture_size_at_timeout = texture->GetCurrentFrameSize(),
    };
  }
}

bool Rasterizer::AreSyncRequirementsSatisfied(
    const std::vector<std::unique_ptr<LayerTreeTask>>& tasks,
    const std::vector<flutter::TextureSyncRequirement>& carryover_requirements)
    const {
  std::vector<UnsatisfiedSyncEntry> unsatisfied;
  return !CollectUnsatisfiedSyncRequirements(tasks, carryover_requirements,
                                             unsatisfied);
}

void Rasterizer::CancelRegisteredWaiters(
    std::vector<RegisteredSyncWaiter>& waiters) {
  for (auto& entry : waiters) {
    if (entry.token == 0) {
      continue;
    }
    if (auto texture = entry.texture.lock()) {
      texture->CancelSyncReadyCallback(entry.token);
    }
  }
  waiters.clear();
}

bool Rasterizer::TryHoldFrameForSync(
    std::unique_ptr<FrameItem>& item,
    const std::shared_ptr<FramePipeline>& pipeline,
    const std::vector<flutter::TextureSyncRequirement>& carryover_requirements,
    std::optional<fml::TimePoint> inherited_deadline) {
  if (is_torn_down_ || !surface_ || !item || item->layer_tree_tasks.empty() ||
      !item->frame_timings_recorder) {
    return false;
  }

  std::vector<UnsatisfiedSyncEntry> unsatisfied;
  if (!CollectUnsatisfiedSyncRequirements(
          item->layer_tree_tasks, carryover_requirements, unsatisfied)) {
    return false;
  }

  const fml::TimePoint now = fml::TimePoint::Now();
  const fml::TimePoint vsync_target =
      item->frame_timings_recorder->GetVsyncTargetTime();

  fml::TimePoint latest_deadline = fml::TimePoint::Min();
  bool has_candidate_deadline = false;
  for (const auto& entry : unsatisfied) {
    const fml::TimeDelta max_wait = entry.requirement.ClampedMaxWait();
    if (max_wait <= fml::TimeDelta::Zero()) {
      continue;
    }
    // Anchor the bounded hold window to `vsync_target` when it is either in
    // the future or within the current frame window (`now - vsync_target <
    // max_wait`). If a synthetic or stale timestamp is far in the past, bound
    // relative to `now` so `max_wait` is strictly enforced in both production
    // VSync scheduling and synthetic unit tests.
    const fml::TimePoint anchor =
        (vsync_target > now || (now - vsync_target) < max_wait) ? vsync_target
                                                                : now;
    const fml::TimePoint candidate_deadline = anchor + max_wait;
    if (!has_candidate_deadline || candidate_deadline > latest_deadline) {
      latest_deadline = candidate_deadline;
      has_candidate_deadline = true;
    }
  }

  if (!has_candidate_deadline) {
    return false;
  }

  // Never extend an inherited hold deadline when superseding an earlier held
  // frame; once the original bounded window expires, present immediately so a
  // continuous 120 Hz stream of unsatisfied frames can never starve rendering.
  if (inherited_deadline.has_value() && *inherited_deadline < latest_deadline) {
    latest_deadline = *inherited_deadline;
  }

  if (latest_deadline <= now) {
    if (inherited_deadline.has_value()) {
      std::vector<flutter::TextureSyncRequirement> expired_reqs;
      expired_reqs.reserve(unsatisfied.size());
      for (const auto& entry : unsatisfied) {
        expired_reqs.push_back(entry.requirement);
      }
      RecordTimedOutRequirements(expired_reqs);
      ++sync_frames_timed_out_count_;
      FML_TRACE_COUNTER("flutter", "RasterizerSyncHold",
                        reinterpret_cast<int64_t>(this), "held",
                        sync_frames_held_count_, "latched",
                        sync_frames_latched_count_, "timed_out",
                        sync_frames_timed_out_count_);
    }
    return false;
  }

  const uint64_t hold_gen = ++sync_hold_generation_;
  ++sync_frames_held_count_;
  FML_TRACE_COUNTER(
      "flutter", "RasterizerSyncHold", reinterpret_cast<int64_t>(this), "held",
      sync_frames_held_count_, "latched", sync_frames_latched_count_,
      "timed_out", sync_frames_timed_out_count_);

  std::vector<flutter::TextureSyncRequirement> active_reqs;
  active_reqs.reserve(unsatisfied.size());
  for (const auto& entry : unsatisfied) {
    active_reqs.push_back(entry.requirement);
  }

  auto raster_task_runner = delegate_.GetTaskRunners().GetRasterTaskRunner();
  auto weak_self = weak_factory_.GetWeakPtr();

  std::vector<RegisteredSyncWaiter> registered_waiters;
  registered_waiters.reserve(unsatisfied.size());
  for (const auto& entry : unsatisfied) {
    const uint64_t waiter_token = entry.texture->RegisterSyncReadyCallback(
        entry.requirement.min_frame_generation,
        entry.requirement.expected_physical_size,
        [raster_task_runner, weak_self, hold_gen]() {
          raster_task_runner->PostTask([weak_self, hold_gen]() {
            if (weak_self) {
              weak_self->OnSyncRequirementReady(hold_gen);
            }
          });
        });
    if (waiter_token != 0) {
      registered_waiters.push_back(RegisteredSyncWaiter{
          .texture = entry.texture,
          .token = waiter_token,
      });
    }
  }

  held_sync_frame_ = HeldSyncFrame{
      .frame_item = std::move(item),
      .pipeline = pipeline,
      .hold_generation = hold_gen,
      .deadline = latest_deadline,
      .active_requirements = std::move(active_reqs),
      .registered_waiters = std::move(registered_waiters),
  };

  // Re-check immediately after registering waiters in case the producer became
  // ready concurrently and fired synchronously before `held_sync_frame_` was
  // populated. Restore `item` and return `false` so the caller draws via the
  // normal post-`Consume` path (preserving `kResubmit` handling).
  if (AreSyncRequirementsSatisfied(
          held_sync_frame_->frame_item->layer_tree_tasks,
          held_sync_frame_->active_requirements)) {
    item = std::move(held_sync_frame_->frame_item);
    CancelHeldSyncFrame();
    --sync_frames_held_count_;
    return false;
  }

  const fml::TimeDelta wait_duration = (latest_deadline > now)
                                           ? (latest_deadline - now)
                                           : fml::TimeDelta::Zero();
  raster_task_runner->PostDelayedTask(
      [weak_self, hold_gen]() {
        if (weak_self) {
          weak_self->OnSyncHoldTimeout(hold_gen);
        }
      },
      wait_duration);

  return true;
}

void Rasterizer::OnSyncRequirementReady(uint64_t hold_generation) {
  FML_DCHECK(delegate_.GetTaskRunners()
                 .GetRasterTaskRunner()
                 ->RunsTasksOnCurrentThread());
  if (is_torn_down_ || !surface_ || !held_sync_frame_.has_value() ||
      held_sync_frame_->hold_generation != hold_generation) {
    return;
  }
  if (!AreSyncRequirementsSatisfied(
          held_sync_frame_->frame_item->layer_tree_tasks,
          held_sync_frame_->active_requirements)) {
    return;
  }
  ++sync_frames_latched_count_;
  FML_TRACE_COUNTER(
      "flutter", "RasterizerSyncHold", reinterpret_cast<int64_t>(this), "held",
      sync_frames_held_count_, "latched", sync_frames_latched_count_,
      "timed_out", sync_frames_timed_out_count_);
  FlushHeldSyncFrame();
}

void Rasterizer::OnSyncHoldTimeout(uint64_t hold_generation) {
  FML_DCHECK(delegate_.GetTaskRunners()
                 .GetRasterTaskRunner()
                 ->RunsTasksOnCurrentThread());
  if (is_torn_down_ || !surface_ || !held_sync_frame_.has_value() ||
      held_sync_frame_->hold_generation != hold_generation) {
    return;
  }
  RecordTimedOutRequirements(held_sync_frame_->active_requirements);
  ++sync_frames_timed_out_count_;
  FML_TRACE_COUNTER(
      "flutter", "RasterizerSyncHold", reinterpret_cast<int64_t>(this), "held",
      sync_frames_held_count_, "latched", sync_frames_latched_count_,
      "timed_out", sync_frames_timed_out_count_);
  FlushHeldSyncFrame();
}

void Rasterizer::FlushHeldSyncFrame() {
  FML_DCHECK(delegate_.GetTaskRunners()
                 .GetRasterTaskRunner()
                 ->RunsTasksOnCurrentThread());
  if (!held_sync_frame_.has_value()) {
    return;
  }
  HeldSyncFrame held = std::move(*held_sync_frame_);
  held_sync_frame_.reset();
  ++sync_hold_generation_;
  CancelRegisteredWaiters(held.registered_waiters);

  if (is_torn_down_ || !surface_ || !held.frame_item) {
    return;
  }

  std::shared_ptr<FramePipeline> pipeline = held.pipeline.lock();
  DoDrawResult draw_result =
      DoDraw(std::move(held.frame_item->frame_timings_recorder),
             std::move(held.frame_item->layer_tree_tasks));

  bool should_resubmit_frame = ShouldResubmitFrame(draw_result);
  bool enqueue_pipeline = false;
  if (should_resubmit_frame && pipeline) {
    FML_CHECK(draw_result.resubmitted_item);
    auto front_continuation = pipeline->ProduceIfEmpty();
    PipelineProduceResult pipeline_result =
        front_continuation.Complete(std::move(draw_result.resubmitted_item));
    if (pipeline_result.success) {
      enqueue_pipeline = true;
    }
  } else if (draw_result.status == DoDrawStatus::kEnqueuePipeline) {
    enqueue_pipeline = true;
  }

  if (external_view_embedder_ && external_view_embedder_->GetUsedThisFrame()) {
    external_view_embedder_->SetUsedThisFrame(false);
    external_view_embedder_->EndFrame(should_resubmit_frame,
                                      raster_thread_merger_);
  }

  if (enqueue_pipeline && pipeline) {
    delegate_.GetTaskRunners().GetRasterTaskRunner()->PostTask(
        [weak_this = weak_factory_.GetWeakPtr(), pipeline]() {
          if (weak_this) {
            weak_this->Draw(pipeline);
          }
        });
  }
}

void Rasterizer::CancelHeldSyncFrame() {
  if (held_sync_frame_.has_value()) {
    CancelRegisteredWaiters(held_sync_frame_->registered_waiters);
  }
  held_sync_frame_.reset();
  ++sync_hold_generation_;
}

}  // namespace flutter
