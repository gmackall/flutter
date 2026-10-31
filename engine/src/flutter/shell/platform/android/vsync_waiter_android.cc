// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/vsync_waiter_android.h"

#include <algorithm>
#include <cmath>
#include <utility>

#include "flutter/common/task_runners.h"
#include "flutter/fml/logging.h"
#include "flutter/fml/platform/android/jni_util.h"
#include "flutter/fml/platform/android/scoped_java_ref.h"
#include "flutter/fml/trace_event.h"
#include "impeller/toolkit/android/choreographer.h"
#include "third_party/dart/runtime/include/dart_api.h"

namespace flutter {

static fml::jni::ScopedJavaGlobalRef<jclass>* g_vsync_waiter_class = nullptr;
static jmethodID g_async_wait_for_vsync_method_ = nullptr;
static std::atomic_uint g_refresh_rate_ = 60;

VsyncWaiterAndroid::VsyncWaiterAndroid(const flutter::TaskRunners& task_runners)
    : VsyncWaiter(task_runners) {}

VsyncWaiterAndroid::~VsyncWaiterAndroid() = default;

// |VsyncWaiter|
void VsyncWaiterAndroid::AwaitVSync() {
  const static bool use_choreographer =
      impeller::android::Choreographer::IsAvailableOnPlatform();
  // Prefer the Java Choreographer when UI/platform threads are merged so
  // BeginFrame runs inside doFrame's CALLBACK_ANIMATION, before
  // CALLBACK_TRAVERSAL; keep NDK for unmerged threads and when JNI isn't
  // registered.
  if (use_choreographer &&
      (g_vsync_waiter_class == nullptr ||
       g_async_wait_for_vsync_method_ == nullptr ||
       !task_runners_.GetPlatformTaskRunner()->RunsTasksOnCurrentThread())) {
    auto* weak_this = new std::weak_ptr<VsyncWaiter>(shared_from_this());
    // NOLINTNEXTLINE(clang-analyzer-cplusplus.NewDeleteLeaks)
    fml::TaskRunner::RunNowOrPostTask(
        task_runners_.GetUITaskRunner(), [weak_this]() {
          const auto& choreographer =
              impeller::android::Choreographer::GetInstance();
          choreographer.PostFrameCallback([weak_this](auto time) {
            auto time_ns =
                std::chrono::time_point_cast<std::chrono::nanoseconds>(time)
                    .time_since_epoch()
                    .count();
            OnVsyncFromNDK(time_ns, weak_this);
          });
        });
  } else {
    auto* weak_this = new std::weak_ptr<VsyncWaiter>(shared_from_this());
    jlong java_baton = reinterpret_cast<jlong>(weak_this);
    // NOLINTNEXTLINE(clang-analyzer-cplusplus.NewDeleteLeaks)
    fml::TaskRunner::RunNowOrPostTask(
        task_runners_.GetPlatformTaskRunner(), [java_baton]() {
          JNIEnv* env = fml::jni::AttachCurrentThread();
          env->CallStaticVoidMethod(g_vsync_waiter_class->obj(),     //
                                    g_async_wait_for_vsync_method_,  //
                                    java_baton                       //
          );
        });
  }
}

// static
void VsyncWaiterAndroid::OnVsyncFromNDK(int64_t frame_nanos, void* data) {
  auto frame_time = fml::TimePoint::FromEpochDelta(
      fml::TimeDelta::FromNanoseconds(frame_nanos));
  auto now = fml::TimePoint::Now();
  if (frame_time > now) {
    frame_time = now;
  }
  auto target_time = frame_time + fml::TimeDelta::FromNanoseconds(
                                      1000000000.0 / g_refresh_rate_);

  TRACE_EVENT2_INT("flutter", "PlatformVsync", "frame_start_time",
                   frame_time.ToEpochDelta().ToMicroseconds(),
                   "frame_target_time",
                   target_time.ToEpochDelta().ToMicroseconds());

  auto* weak_this = reinterpret_cast<std::weak_ptr<VsyncWaiter>*>(data);
  ConsumePendingCallback(weak_this, frame_time, target_time,
                         Dart_CurrentIsolate() == nullptr);
}

// static
void VsyncWaiterAndroid::OnVsyncFromJava(JNIEnv* env,
                                         jclass jcaller,
                                         jlong frameDelayNanos,
                                         jlong refreshPeriodNanos,
                                         jlong java_baton) {
  auto now = fml::TimePoint::Now();
  auto vsync_period =
      fml::TimeDelta::FromNanoseconds(std::max<jlong>(0, refreshPeriodNanos));
  auto frame_time = now - fml::TimeDelta::FromNanoseconds(frameDelayNanos);
  auto target_time = frame_time + vsync_period;
  // Defensive guard in case a negative frameDelayNanos is ever passed across
  // JNI (VsyncWaiter.java already clamps delay < 0 to 0).
  if (frame_time > now) {
    frame_time = now;
    target_time = now + vsync_period;
  }

  TRACE_EVENT2_INT("flutter", "PlatformVsync", "frame_start_time",
                   frame_time.ToEpochDelta().ToMicroseconds(),
                   "frame_target_time",
                   target_time.ToEpochDelta().ToMicroseconds());

  auto* weak_this = reinterpret_cast<std::weak_ptr<VsyncWaiter>*>(java_baton);
  ConsumePendingCallback(weak_this, frame_time, target_time,
                         Dart_CurrentIsolate() == nullptr);
}

// static
void VsyncWaiterAndroid::ConsumePendingCallback(
    std::weak_ptr<VsyncWaiter>* weak_this,
    fml::TimePoint frame_start_time,
    fml::TimePoint frame_target_time,
    bool can_target_current_thread) {
  auto shared_this = weak_this->lock();
  delete weak_this;

  if (shared_this) {
    shared_this->FireCallback(frame_start_time, frame_target_time,
                              can_target_current_thread);
  }
}

// static
void VsyncWaiterAndroid::OnUpdateRefreshRate(JNIEnv* env,
                                             jclass jcaller,
                                             jfloat refresh_rate) {
  FML_DCHECK(refresh_rate > 0);
  g_refresh_rate_ = static_cast<uint>(refresh_rate);
}

// static
bool VsyncWaiterAndroid::Register(JNIEnv* env) {
  static const JNINativeMethod methods[] = {
      {
          .name = "nativeOnVsync",
          .signature = "(JJJ)V",
          .fnPtr = reinterpret_cast<void*>(&OnVsyncFromJava),
      },
      {
          .name = "nativeUpdateRefreshRate",
          .signature = "(F)V",
          .fnPtr = reinterpret_cast<void*>(&OnUpdateRefreshRate),
      }};

  jclass clazz = env->FindClass("io/flutter/embedding/engine/FlutterJNI");

  if (clazz == nullptr) {
    return false;
  }

  g_vsync_waiter_class = new fml::jni::ScopedJavaGlobalRef<jclass>(env, clazz);
  FML_CHECK(!g_vsync_waiter_class->is_null());

  g_async_wait_for_vsync_method_ = env->GetStaticMethodID(
      g_vsync_waiter_class->obj(), "asyncWaitForVsync", "(J)V");
  FML_CHECK(g_async_wait_for_vsync_method_ != nullptr);

  return env->RegisterNatives(clazz, methods, std::size(methods)) == 0;
}

}  // namespace flutter
