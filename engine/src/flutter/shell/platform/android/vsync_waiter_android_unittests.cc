// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#define FML_USED_ON_EMBEDDER

#include <memory>

#include "flutter/common/task_runners.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/thread.h"
#include "flutter/shell/platform/android/vsync_waiter_android.h"
#include "gtest/gtest.h"

namespace flutter {
namespace testing {

class VsyncWaiterAndroidTest : public ::testing::Test {
 public:
  static void InvokeOnVsyncFromJava(
      const std::shared_ptr<VsyncWaiterAndroid>& waiter,
      jlong frame_delay_nanos,
      jlong refresh_period_nanos) {
    auto* weak_waiter = new std::weak_ptr<VsyncWaiter>(waiter);
    VsyncWaiterAndroid::OnVsyncFromJava(nullptr, nullptr, frame_delay_nanos,
                                        refresh_period_nanos,
                                        reinterpret_cast<jlong>(weak_waiter));
  }

  static void SetPendingCallback(
      const std::shared_ptr<VsyncWaiterAndroid>& waiter,
      const VsyncWaiter::Callback& callback) {
    std::scoped_lock lock(waiter->callback_mutex_);
    waiter->callback_ = callback;
  }
};

TEST_F(VsyncWaiterAndroidTest,
       OnVsyncFromJavaFiresSynchronouslyOnUIThreadAndPreservesPeriod) {
  bool invoked_synchronously = false;
  fml::TimePoint start_time;
  fml::TimePoint target_time;
  fml::AutoResetWaitableEvent latch;

  fml::Thread ui_thread("vsync_waiter_android_test_ui");
  auto task_runner = ui_thread.GetTaskRunner();
  const TaskRunners task_runners("vsync_waiter_android_test", task_runner,
                                 task_runner, task_runner, task_runner);
  auto waiter = std::make_shared<VsyncWaiterAndroid>(task_runners);

  constexpr jlong kDelayNanos = 1000000;   // 1 ms
  constexpr jlong kPeriodNanos = 8333333;  // ~120 Hz

  task_runner->PostTask([&]() {
    bool invoked = false;
    SetPendingCallback(waiter,
                       [&](std::unique_ptr<FrameTimingsRecorder> recorder) {
                         invoked = true;
                         start_time = recorder->GetVsyncStartTime();
                         target_time = recorder->GetVsyncTargetTime();
                       });
    InvokeOnVsyncFromJava(waiter, kDelayNanos, kPeriodNanos);
    invoked_synchronously = invoked;
    latch.Signal();
  });
  latch.Wait();

  EXPECT_TRUE(invoked_synchronously);
  EXPECT_LE(start_time, fml::TimePoint::Now());
  EXPECT_EQ(target_time - start_time,
            fml::TimeDelta::FromNanoseconds(kPeriodNanos));
}

TEST_F(VsyncWaiterAndroidTest,
       OnVsyncFromJavaClampsFutureFrameTimeAndTargetTime) {
  bool invoked_synchronously = false;
  fml::TimePoint before;
  fml::TimePoint after;
  fml::TimePoint start_time;
  fml::TimePoint target_time;
  fml::AutoResetWaitableEvent latch;

  fml::Thread ui_thread("vsync_waiter_android_clamp_test_ui");
  auto task_runner = ui_thread.GetTaskRunner();
  const TaskRunners task_runners("vsync_waiter_android_clamp_test", task_runner,
                                 task_runner, task_runner, task_runner);
  auto waiter = std::make_shared<VsyncWaiterAndroid>(task_runners);

  // Negative delay would place frame_time in the future without clamping.
  constexpr jlong kNegativeDelayNanos = -5000000;
  constexpr jlong kPeriodNanos = 8333333;

  task_runner->PostTask([&]() {
    bool invoked = false;
    SetPendingCallback(waiter,
                       [&](std::unique_ptr<FrameTimingsRecorder> recorder) {
                         invoked = true;
                         start_time = recorder->GetVsyncStartTime();
                         target_time = recorder->GetVsyncTargetTime();
                       });
    before = fml::TimePoint::Now();
    InvokeOnVsyncFromJava(waiter, kNegativeDelayNanos, kPeriodNanos);
    after = fml::TimePoint::Now();
    invoked_synchronously = invoked;
    latch.Signal();
  });
  latch.Wait();

  EXPECT_TRUE(invoked_synchronously);
  EXPECT_GE(start_time, before);
  EXPECT_LE(start_time, after);
  EXPECT_EQ(target_time - start_time,
            fml::TimeDelta::FromNanoseconds(kPeriodNanos));
}

}  // namespace testing
}  // namespace flutter
