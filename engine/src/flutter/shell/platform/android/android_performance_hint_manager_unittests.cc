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
  if (android_get_device_api_level() < 35) {
    EXPECT_EQ(AndroidPerformanceHintManager::Create({gettid()}, 16666666),
              nullptr);
    GTEST_SKIP() << "ADPF AWorkDuration requires Android API level 35+";
  }

  constexpr int64_t target_ns = 16666666;  // ~60 Hz
  std::unique_ptr<AndroidPerformanceHintManager> manager =
      AndroidPerformanceHintManager::Create({gettid()}, target_ns);

  // If the OS / PowerHAL supports ADPF, verify the methods execute cleanly.
  if (manager) {
    EXPECT_EQ(manager->GetTargetWorkDuration(), target_ns);
    EXPECT_EQ(manager->GetThreads(), std::vector<int32_t>{gettid()});

    // Test reporting actual duration with AWorkDuration parameters.
    manager->ReportActualWorkDuration(1000000000, 8000000, 6000000, 0);

    // Same-vsync multi-engine report (< 0.5 * target_ns delta) should coalesce
    // cleanly without error.
    manager->ReportActualWorkDuration(1002000000, 3000000, 2500000, 0);

    // Small target jitter (< 5%) should be suppressed by hysteresis.
    manager->UpdateTargetWorkDuration(target_ns - 500000);
    EXPECT_EQ(manager->GetTargetWorkDuration(), target_ns);

    // 90 Hz -> 80 Hz transition (11.1% change) should update target duration.
    constexpr int64_t target_90hz_ns = 11111111;
    constexpr int64_t target_80hz_ns = 12500000;
    manager->UpdateTargetWorkDuration(target_90hz_ns);
    EXPECT_EQ(manager->GetTargetWorkDuration(), target_90hz_ns);
    manager->UpdateTargetWorkDuration(target_80hz_ns);
    EXPECT_EQ(manager->GetTargetWorkDuration(), target_80hz_ns);

    // Large target change (e.g. 80 Hz -> 120 Hz) should update target duration.
    constexpr int64_t new_target_ns = 8333333;
    manager->UpdateTargetWorkDuration(new_target_ns);
    EXPECT_EQ(manager->GetTargetWorkDuration(), new_target_ns);

    // SetThreads with the same or updated TID list.
    EXPECT_TRUE(manager->SetThreads({gettid()}));
    EXPECT_FALSE(manager->SetThreads({}));
  }
}

}  // namespace testing
}  // namespace flutter
