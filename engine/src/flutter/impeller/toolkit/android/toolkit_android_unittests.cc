// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/testing/testing.h"
#include "impeller/toolkit/android/choreographer.h"
#include "impeller/toolkit/android/hardware_buffer.h"
#include "impeller/toolkit/android/proc_table.h"
#include "impeller/toolkit/android/surface_control.h"
#include "impeller/toolkit/android/surface_transaction.h"

namespace impeller::android::testing {

#define DISABLE_ANDROID_PROC(name)                                    \
  struct Disable##name {                                              \
    Disable##name() {                                                 \
      real_proc = GetMutableProcTable().name.proc;                    \
      GetMutableProcTable().name.proc = nullptr;                      \
    }                                                                 \
    ~Disable##name() { GetMutableProcTable().name.proc = real_proc; } \
    decltype(name)* real_proc;                                        \
  } disable##name;

TEST(ToolkitAndroidTest, CanCreateProcTable) {
  ProcTable proc_table;
  ASSERT_TRUE(proc_table.IsValid());
}

TEST(ToolkitAndroidTest, GuardsAgainstZeroSizedDescriptors) {
  auto desc = HardwareBufferDescriptor::MakeForSwapchainImage({0, 0});
  ASSERT_GT(desc.size.width, 0u);
  ASSERT_GT(desc.size.height, 0u);
}

TEST(ToolkitAndroidTest, CanCreateHardwareBuffer) {
  if (!HardwareBuffer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Hardware buffers are not supported on this platform.";
  }
  ASSERT_TRUE(HardwareBuffer::IsAvailableOnPlatform());
  auto desc = HardwareBufferDescriptor::MakeForSwapchainImage({100, 100});
  ASSERT_TRUE(desc.IsAllocatable());
  HardwareBuffer buffer(desc);
  ASSERT_TRUE(buffer.IsValid());
}

TEST(ToolkitAndroidTest, CanGetHardwareBufferIDs) {
  if (!HardwareBuffer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Hardware buffers are not supported on this platform.";
  }
  ASSERT_TRUE(HardwareBuffer::IsAvailableOnPlatform());
  if (!GetProcTable().AHardwareBuffer_getId.IsAvailable()) {
    GTEST_SKIP() << "Hardware buffer IDs are not available on this platform.";
  }
  auto desc = HardwareBufferDescriptor::MakeForSwapchainImage({100, 100});
  ASSERT_TRUE(desc.IsAllocatable());
  HardwareBuffer buffer(desc);
  ASSERT_TRUE(buffer.IsValid());
  ASSERT_TRUE(buffer.GetSystemUniqueID().has_value());
}

TEST(ToolkitAndroidTest, HardwareBufferNullIDIfAPIUnavailable) {
  DISABLE_ANDROID_PROC(AHardwareBuffer_getId);
  ASSERT_FALSE(HardwareBuffer::GetSystemUniqueID(nullptr).has_value());
}

TEST(ToolkitAndroidTest, CanDescribeHardwareBufferHandles) {
  if (!HardwareBuffer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Hardware buffers are not supported on this platform.";
  }
  ASSERT_TRUE(HardwareBuffer::IsAvailableOnPlatform());
  auto desc = HardwareBufferDescriptor::MakeForSwapchainImage({100, 100});
  ASSERT_TRUE(desc.IsAllocatable());
  HardwareBuffer buffer(desc);
  ASSERT_TRUE(buffer.IsValid());
  auto a_desc = HardwareBuffer::Describe(buffer.GetHandle());
  ASSERT_TRUE(a_desc.has_value());
  ASSERT_EQ(a_desc->width, 100u);   // NOLINT
  ASSERT_EQ(a_desc->height, 100u);  // NOLINT
}

TEST(ToolkitAndroidTest, CanApplySurfaceTransaction) {
  if (!SurfaceTransaction::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Surface controls are not supported on this platform.";
  }
  ASSERT_TRUE(SurfaceTransaction::IsAvailableOnPlatform());
  SurfaceTransaction transaction;
  ASSERT_TRUE(transaction.IsValid());
  if (GetProcTable().ASurfaceTransaction_setFrameTimeline.IsAvailable()) {
    EXPECT_TRUE(transaction.SetFrameTimeline(42));
  }
  fml::AutoResetWaitableEvent event;
  ASSERT_TRUE(transaction.Apply([&event](auto) { event.Signal(); }));
  event.Wait();
}

TEST(ToolkitAndroidTest, SurfaceTransactionSetFrameTimelineIfAPIUnavailable) {
  if (!SurfaceTransaction::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Surface controls are not supported on this platform.";
  }
  DISABLE_ANDROID_PROC(ASurfaceTransaction_setFrameTimeline);
  SurfaceTransaction transaction;
  ASSERT_TRUE(transaction.IsValid());
  EXPECT_FALSE(transaction.SetFrameTimeline(42));
}

TEST(ToolkitAndroidTest, SurfacControlsAreAvailable) {
  if (!SurfaceControl::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Surface controls are not supported on this platform.";
  }
  ASSERT_TRUE(SurfaceControl::IsAvailableOnPlatform());
}

TEST(ToolkitAndroidTest, ChoreographerIsAvailable) {
  if (!Choreographer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Choreographer is not supported on this platform.";
  }
  ASSERT_TRUE(Choreographer::IsAvailableOnPlatform());
}

TEST(ToolkitAndroidTest, CanPostAndNotWaitForFrameCallbacks) {
  if (!Choreographer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Choreographer is not supported on this platform.";
  }
  const auto& choreographer = Choreographer::GetInstance();
  ASSERT_TRUE(choreographer.IsValid());
  ASSERT_TRUE(choreographer.PostFrameCallback([](auto) {}));
  ASSERT_TRUE(choreographer.PostVsyncCallback([](const auto&) {}));
}

TEST(ToolkitAndroidTest, CanPostVsyncCallbacksWithFallback) {
  if (!Choreographer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Choreographer is not supported on this platform.";
  }
  DISABLE_ANDROID_PROC(AChoreographer_postVsyncCallback);
  const auto& choreographer = Choreographer::GetInstance();
  ASSERT_TRUE(choreographer.IsValid());
  ASSERT_TRUE(choreographer.PostVsyncCallback([](const auto&) {}));
}

TEST(ToolkitAndroidTest, PostVsyncCallbackExtractsPreferredAndNextVsyncIds) {
  if (!Choreographer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Choreographer is not supported on this platform.";
  }
  const auto& choreographer = Choreographer::GetInstance();
  ASSERT_TRUE(choreographer.IsValid());

  auto& table = GetMutableProcTable();
  const auto orig_post_vsync = table.AChoreographer_postVsyncCallback.proc;
  const auto orig_get_time =
      table.AChoreographerFrameCallbackData_getFrameTimeNanos.proc;
  const auto orig_get_len =
      table.AChoreographerFrameCallbackData_getFrameTimelinesLength.proc;
  const auto orig_get_pref =
      table.AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex.proc;
  const auto orig_get_id =
      table.AChoreographerFrameCallbackData_getFrameTimelineVsyncId.proc;

  struct ScopedRestore {
    std::function<void()> restore;
    ~ScopedRestore() { restore(); }
  } restore{[&]() {
    table.AChoreographer_postVsyncCallback.proc = orig_post_vsync;
    table.AChoreographerFrameCallbackData_getFrameTimeNanos.proc =
        orig_get_time;
    table.AChoreographerFrameCallbackData_getFrameTimelinesLength.proc =
        orig_get_len;
    table.AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex.proc =
        orig_get_pref;
    table.AChoreographerFrameCallbackData_getFrameTimelineVsyncId.proc =
        orig_get_id;
  }};

  static size_t fake_timelines_length = 7;
  static size_t fake_preferred_index = 0;

  table.AChoreographer_postVsyncCallback.proc =
      [](AChoreographer*, AChoreographer_vsyncCallback callback, void* data) {
        callback(reinterpret_cast<const AChoreographerFrameCallbackData*>(0x1),
                 data);
      };
  table.AChoreographerFrameCallbackData_getFrameTimeNanos.proc =
      [](const AChoreographerFrameCallbackData*) -> int64_t {
    return 123456789LL;
  };
  table.AChoreographerFrameCallbackData_getFrameTimelinesLength.proc =
      [](const AChoreographerFrameCallbackData*) -> size_t {
    return fake_timelines_length;
  };
  table.AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex.proc =
      [](const AChoreographerFrameCallbackData*) -> size_t {
    return fake_preferred_index;
  };
  table.AChoreographerFrameCallbackData_getFrameTimelineVsyncId.proc =
      [](const AChoreographerFrameCallbackData*, size_t index) -> AVsyncId {
    return static_cast<AVsyncId>(1000 + index);
  };

  // Multi-timeline case (standard 7 timelines): preferred = 1000, next = 1001.
  Choreographer::VsyncData observed{};
  ASSERT_TRUE(choreographer.PostVsyncCallback(
      [&](const Choreographer::VsyncData& data) { observed = data; }));
  EXPECT_EQ(observed.frame_time,
            Choreographer::FrameTimePoint{std::chrono::nanoseconds(123456789)});
  EXPECT_EQ(observed.preferred_vsync_id, 1000);
  EXPECT_EQ(observed.next_vsync_id, 1001);

  // Single-timeline edge case (preferred_idx + 1 == timelines_length):
  // next_vsync_id must stay kInvalidVsyncId rather than clamping to preferred.
  fake_timelines_length = 1;
  fake_preferred_index = 0;
  observed = {};
  ASSERT_TRUE(choreographer.PostVsyncCallback(
      [&](const Choreographer::VsyncData& data) { observed = data; }));
  EXPECT_EQ(observed.preferred_vsync_id, 1000);
  EXPECT_EQ(observed.next_vsync_id, Choreographer::kInvalidVsyncId);
}

TEST(ToolkitAndroidTest, CanPostAndWaitForFrameCallbacks) {
  if (!Choreographer::IsAvailableOnPlatform()) {
    GTEST_SKIP() << "Choreographer is not supported on this platform.";
  }
  if ((true)) {
    GTEST_SKIP()
        << "Disabled till the test harness is in an Android activity. "
           "Running it without one will hang because the choreographer "
           "frame callback will never execute.";
  }
  const auto& choreographer = Choreographer::GetInstance();
  ASSERT_TRUE(choreographer.IsValid());
  fml::AutoResetWaitableEvent event;
  ASSERT_TRUE(choreographer.PostFrameCallback(
      [&event](auto point) { event.Signal(); }));
  event.Wait();
}

}  // namespace impeller::android::testing
