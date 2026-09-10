// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/impeller/toolkit/android/choreographer.h"

#include "flutter/fml/message_loop.h"

namespace impeller::android {

Choreographer& Choreographer::GetInstance() {
  static thread_local Choreographer tChoreographer;
  return tChoreographer;
}

Choreographer::Choreographer() {
  if (!IsAvailableOnPlatform()) {
    return;
  }

  // We need a message loop on the current thread for the choreographer to
  // schedule callbacks for us on.
  fml::MessageLoop::EnsureInitializedForCurrentThread();
  instance_ = GetProcTable().AChoreographer_getInstance();
}

Choreographer::~Choreographer() = default;

bool Choreographer::IsValid() const {
  return !!instance_;
}

static Choreographer::FrameTimePoint ClockMonotonicNanosToFrameTimePoint(
    int64_t p_nanos) {
  return Choreographer::FrameTimePoint{std::chrono::nanoseconds(p_nanos)};
}

bool Choreographer::PostFrameCallback(FrameCallback callback) const {
  if (!callback || !IsValid()) {
    return false;
  }

  struct InFlightData {
    FrameCallback callback;
  };

  auto data = std::make_unique<InFlightData>();
  data->callback = std::move(callback);

  const auto& table = GetProcTable();
  if (table.AChoreographer_postFrameCallback64) {
    table.AChoreographer_postFrameCallback64(
        const_cast<AChoreographer*>(instance_),
        [](int64_t nanos, void* p_data) {
          auto data = reinterpret_cast<InFlightData*>(p_data);
          data->callback(ClockMonotonicNanosToFrameTimePoint(nanos));
          delete data;
        },
        data.release());
    return true;
  } else if (table.AChoreographer_postFrameCallback) {
    table.AChoreographer_postFrameCallback(
        const_cast<AChoreographer*>(instance_),
        [](long /*NOLINT*/ nanos, void* p_data) {
          auto data = reinterpret_cast<InFlightData*>(p_data);
          data->callback(ClockMonotonicNanosToFrameTimePoint(nanos));
          delete data;
        },
        data.release());
    return true;
  }

  // The validity check should have tripped by now.
  FML_UNREACHABLE();
  return false;
}

bool Choreographer::PostVsyncCallback(VsyncCallback callback) const {
  if (!callback || !IsValid()) {
    return false;
  }

  const ProcTable& table = GetProcTable();
  if (table.AChoreographer_postVsyncCallback &&
      table.AChoreographerFrameCallbackData_getFrameTimeNanos &&
      table.AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex &&
      table.AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos) {
    struct InFlightVsyncData {
      VsyncCallback callback;
    };

    std::unique_ptr<InFlightVsyncData> data =
        std::make_unique<InFlightVsyncData>();
    data->callback = std::move(callback);

    table.AChoreographer_postVsyncCallback(
        const_cast<AChoreographer*>(instance_),
        [](const AChoreographerFrameCallbackData* frame_data, void* p_data) {
          InFlightVsyncData* data =
              reinterpret_cast<InFlightVsyncData*>(p_data);
          const ProcTable& table = GetProcTable();

          int64_t frame_time_nanos =
              table.AChoreographerFrameCallbackData_getFrameTimeNanos(
                  frame_data);
          // Target timeline index 0 to target the single-frame vsync deadline,
          // rather than accepting a multi-frame downclocked timeline
          // recommendation.
          int64_t deadline_nanos =
              table
                  .AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos(
                      frame_data, 0);

          VsyncData vsync_data;
          vsync_data.frame_time =
              ClockMonotonicNanosToFrameTimePoint(frame_time_nanos);
          vsync_data.target_deadline =
              ClockMonotonicNanosToFrameTimePoint(deadline_nanos);

          data->callback(vsync_data);
          delete data;
        },
        data.release());
    return true;
  }

  return PostFrameCallback(
      [callback = std::move(callback)](FrameTimePoint time) {
        VsyncData vsync_data;
        vsync_data.frame_time = time;
        vsync_data.target_deadline = FrameTimePoint{};
        callback(vsync_data);
      });
}

bool Choreographer::IsAvailableOnPlatform() {
  return GetProcTable().AChoreographer_getInstance &&
         (GetProcTable().AChoreographer_postFrameCallback64 ||
          GetProcTable().AChoreographer_postFrameCallback);
}

}  // namespace impeller::android
