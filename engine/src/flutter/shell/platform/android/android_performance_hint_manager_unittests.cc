// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_performance_hint_manager.h"

#include <android/api-level.h>
#include <unistd.h>

#include "gtest/gtest.h"

namespace flutter {
namespace testing {

TEST(AndroidPerformanceHintManagerTest, RejectsInvalidArguments) {
  // Empty TIDs.
  EXPECT_EQ(AndroidPerformanceHintManager::Create({}, 16666666), nullptr);

  // Negative or zero target duration.
  EXPECT_EQ(AndroidPerformanceHintManager::Create({gettid()}, 0), nullptr);
  EXPECT_EQ(AndroidPerformanceHintManager::Create({gettid()}, -100), nullptr);
}

TEST(AndroidPerformanceHintManagerTest, LifecycleOnSupportedDevice) {
  if (android_get_device_api_level() < 31) {
    GTEST_SKIP() << "ADPF PerformanceHint requires Android API level 31+";
  }

  constexpr int64_t target_ns = 16666666;  // ~60 Hz
  std::unique_ptr<AndroidPerformanceHintManager> manager =
      AndroidPerformanceHintManager::Create({gettid()}, target_ns);

  // If the OS / PowerHAL supports ADPF, verify the methods execute cleanly.
  if (manager) {
    EXPECT_EQ(manager->GetTargetWorkDuration(), target_ns);

    manager->NotifyWorkloadReset();

    // Test reporting actual duration with AWorkDuration parameters.
    manager->ReportActualWorkDuration(1000000000, 12000000, 8000000, 0);

    // Small target jitter (< 15%) should be suppressed by hysteresis.
    manager->UpdateTargetWorkDuration(target_ns - 500000);
    EXPECT_EQ(manager->GetTargetWorkDuration(), target_ns);

    // Large target change (e.g. 60 Hz -> 120 Hz) should update target duration.
    int64_t new_target_ns = 8333333;
    manager->UpdateTargetWorkDuration(new_target_ns);
    EXPECT_EQ(manager->GetTargetWorkDuration(), new_target_ns);
  }
}

}  // namespace testing
}  // namespace flutter
