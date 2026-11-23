// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_performance_hint_manager.h"

#include <android/api-level.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <vector>

#include "flutter/fml/logging.h"
#include "flutter/fml/native_library.h"
#include "flutter/fml/time/time_point.h"
#include "gtest/gtest.h"

namespace flutter {
namespace testing {

namespace {

constexpr int64_t kTarget60HzNs = 16'666'666;
constexpr int64_t kTarget80HzNs = 12'500'000;
constexpr int64_t kTarget90HzNs = 11'111'111;
constexpr int64_t kTarget100HzNs = 10'000'000;
constexpr int64_t kTarget120HzNs = 8'333'333;

struct WorkDurationFields {
  int64_t start_ns = 0;
  int64_t total_ns = 0;
  int64_t cpu_ns = 0;
  int64_t gpu_ns = 0;
  bool operator==(const WorkDurationFields&) const = default;
};

struct FakeAdpf {
  FakeAdpf() {
    FML_CHECK(active == nullptr);
    active = this;
  }
  ~FakeAdpf() { active = nullptr; }

  AndroidPerformanceHintManager::Api MakeApi() const {
    return {
        .get_manager = &GetManager,
        .create_session = &CreateSession,
        .report_actual_work_duration2 = &ReportActualWorkDuration2,
        .update_target_work_duration = &UpdateTargetWorkDuration,
        .close_session = &CloseSession,
        .create_work_duration = &WorkDurationCreate,
        .release_work_duration = &WorkDurationRelease,
        .set_work_period_start = &SetField<&WorkDurationFields::start_ns>,
        .set_actual_total_duration = &SetField<&WorkDurationFields::total_ns>,
        .set_actual_cpu_duration = &SetField<&WorkDurationFields::cpu_ns>,
        .set_actual_gpu_duration = &SetField<&WorkDurationFields::gpu_ns>,
    };
  }

  bool return_null_manager = false;
  bool return_null_session = false;
  bool return_null_work_duration = false;
  int report_result = 0;
  int update_target_result = 0;

  int close_session_calls = 0;
  int release_work_duration_calls = 0;
  bool session_open = false;
  bool work_duration_alive = false;
  bool saw_invalid_handle = false;

  std::vector<int32_t> created_tids;
  int64_t created_target_ns = 0;
  WorkDurationFields staging;
  std::vector<WorkDurationFields> reported_durations;
  std::vector<int64_t> updated_targets;

  int manager_token = 0;
  int session_token = 0;
  int work_duration_token = 0;
  static FakeAdpf* active;

  APerformanceHintManager* ManagerHandle() {
    return reinterpret_cast<APerformanceHintManager*>(&manager_token);
  }
  APerformanceHintSession* SessionHandle() {
    return reinterpret_cast<APerformanceHintSession*>(&session_token);
  }
  AWorkDuration* WorkDurationHandle() {
    return reinterpret_cast<AWorkDuration*>(&work_duration_token);
  }
  void CheckSession(APerformanceHintSession* s) {
    if (s != SessionHandle() || !session_open) {
      saw_invalid_handle = true;
    }
  }
  void CheckWorkDuration(AWorkDuration* wd) {
    if (wd != WorkDurationHandle() || !work_duration_alive) {
      saw_invalid_handle = true;
    }
  }

  static APerformanceHintManager* GetManager() {
    return active->return_null_manager ? nullptr : active->ManagerHandle();
  }
  static APerformanceHintSession* CreateSession(APerformanceHintManager* mgr,
                                                const int32_t* tids,
                                                size_t size,
                                                int64_t target_ns) {
    if (mgr != active->ManagerHandle() || !tids || size == 0) {
      active->saw_invalid_handle = true;
    } else {
      active->created_tids.assign(tids, tids + size);
    }
    active->created_target_ns = target_ns;
    if (active->return_null_session) {
      return nullptr;
    }
    active->session_open = true;
    return active->SessionHandle();
  }
  static int ReportActualWorkDuration2(APerformanceHintSession* s,
                                       AWorkDuration* wd) {
    active->CheckSession(s);
    active->CheckWorkDuration(wd);
    active->reported_durations.push_back(active->staging);
    return active->report_result;
  }
  static int UpdateTargetWorkDuration(APerformanceHintSession* s,
                                      int64_t target_ns) {
    active->CheckSession(s);
    active->updated_targets.push_back(target_ns);
    return active->update_target_result;
  }
  static void CloseSession(APerformanceHintSession* s) {
    ++active->close_session_calls;
    active->CheckSession(s);
    active->session_open = false;
  }
  static AWorkDuration* WorkDurationCreate() {
    if (active->return_null_work_duration) {
      return nullptr;
    }
    active->work_duration_alive = true;
    return active->WorkDurationHandle();
  }
  static void WorkDurationRelease(AWorkDuration* wd) {
    ++active->release_work_duration_calls;
    active->CheckWorkDuration(wd);
    active->work_duration_alive = false;
  }
  template <int64_t WorkDurationFields::* Field>
  static void SetField(AWorkDuration* wd, int64_t value) {
    active->CheckWorkDuration(wd);
    active->staging.*Field = value;
  }
};

FakeAdpf* FakeAdpf::active = nullptr;

class AndroidPerformanceHintManagerTest : public ::testing::Test {
 protected:
  void TearDown() override {
    EXPECT_FALSE(fake_.saw_invalid_handle);
    EXPECT_FALSE(fake_.session_open);
    EXPECT_FALSE(fake_.work_duration_alive);
  }

  std::unique_ptr<AndroidPerformanceHintManager> CreateManager(
      const std::vector<int32_t>& tids = {1001},
      int64_t target_ns = kTarget60HzNs) {
    return AndroidPerformanceHintManager::CreateForTesting(tids, target_ns,
                                                           fake_.MakeApi());
  }

  void ExpectClosedAndSuppressed(
      std::unique_ptr<AndroidPerformanceHintManager>& manager) {
    EXPECT_FALSE(fake_.session_open);
    EXPECT_FALSE(fake_.work_duration_alive);
    EXPECT_EQ(fake_.close_session_calls, 1);
    EXPECT_EQ(fake_.release_work_duration_calls, 1);
    EXPECT_EQ(manager->GetTargetWorkDuration(), 0);

    const size_t reports = fake_.reported_durations.size();
    const size_t targets = fake_.updated_targets.size();
    fake_.report_result = 0;
    fake_.update_target_result = 0;

    manager->ReportActualWorkDuration(1'000'000, 8'000'000, 6'000'000);
    manager->UpdateTargetWorkDuration(kTarget120HzNs);
    EXPECT_EQ(fake_.reported_durations.size(), reports);
    EXPECT_EQ(fake_.updated_targets.size(), targets);

    manager.reset();
    EXPECT_EQ(fake_.close_session_calls, 1);
    EXPECT_EQ(fake_.release_work_duration_calls, 1);
  }

  FakeAdpf fake_;
};

TEST_F(AndroidPerformanceHintManagerTest, RejectsInvalidArguments) {
  EXPECT_EQ(CreateManager({}, kTarget60HzNs), nullptr);
  EXPECT_EQ(CreateManager({1001}, 0), nullptr);
  EXPECT_EQ(CreateManager({1001}, -100), nullptr);
  EXPECT_EQ(CreateManager({0}, kTarget60HzNs), nullptr);
  EXPECT_EQ(CreateManager({-1}, kTarget60HzNs), nullptr);
  EXPECT_EQ(CreateManager({1001, 0}, kTarget60HzNs), nullptr);

  EXPECT_EQ(AndroidPerformanceHintManager::Create({}, kTarget60HzNs), nullptr);
  EXPECT_EQ(AndroidPerformanceHintManager::Create({gettid()}, 0), nullptr);
  EXPECT_EQ(AndroidPerformanceHintManager::Create({gettid()}, -100), nullptr);
  EXPECT_EQ(AndroidPerformanceHintManager::Create({0}, kTarget60HzNs), nullptr);
}

TEST_F(AndroidPerformanceHintManagerTest,
       ShouldUseAdpfChecksApiLevelAndHwuiProperty) {
  for (int api : {24, 33, 34}) {
    EXPECT_FALSE(AndroidPerformanceHintManager::ShouldUseAdpf(api, "true"));
  }
  for (const char* disabled :
       {"", "0", "n", "no", "off", "false", "TRUE", "True", "YES", "ON", "Y",
        "2", "true ", " true", "enabled"}) {
    SCOPED_TRACE(disabled);
    EXPECT_FALSE(AndroidPerformanceHintManager::ShouldUseAdpf(35, disabled));
    EXPECT_FALSE(AndroidPerformanceHintManager::ShouldUseAdpf(36, disabled));
  }
  for (const char* enabled : {"1", "y", "yes", "on", "true"}) {
    SCOPED_TRACE(enabled);
    EXPECT_TRUE(AndroidPerformanceHintManager::ShouldUseAdpf(35, enabled));
    EXPECT_TRUE(AndroidPerformanceHintManager::ShouldUseAdpf(36, enabled));
  }
}

TEST_F(AndroidPerformanceHintManagerTest,
       FailsCleanlyOnMissingSymbolsAndInitErrors) {
  using Api = AndroidPerformanceHintManager::Api;
  const std::array<void (*)(Api&), 11> clear_required = {
      [](Api& a) { a.get_manager = nullptr; },
      [](Api& a) { a.create_session = nullptr; },
      [](Api& a) { a.report_actual_work_duration2 = nullptr; },
      [](Api& a) { a.update_target_work_duration = nullptr; },
      [](Api& a) { a.close_session = nullptr; },
      [](Api& a) { a.create_work_duration = nullptr; },
      [](Api& a) { a.release_work_duration = nullptr; },
      [](Api& a) { a.set_work_period_start = nullptr; },
      [](Api& a) { a.set_actual_total_duration = nullptr; },
      [](Api& a) { a.set_actual_cpu_duration = nullptr; },
      [](Api& a) { a.set_actual_gpu_duration = nullptr; },
  };
  for (size_t i = 0; i < clear_required.size(); ++i) {
    SCOPED_TRACE(i);
    Api api = fake_.MakeApi();
    clear_required[i](api);
    EXPECT_EQ(AndroidPerformanceHintManager::CreateForTesting(
                  {1001}, kTarget60HzNs, api),
              nullptr);
  }

  fake_.return_null_manager = true;
  EXPECT_EQ(CreateManager(), nullptr);
  EXPECT_EQ(fake_.close_session_calls, 0);

  fake_.return_null_manager = false;
  fake_.return_null_session = true;
  EXPECT_EQ(CreateManager({101, 202}, kTarget120HzNs), nullptr);
  EXPECT_EQ(fake_.created_tids, (std::vector<int32_t>{101, 202}));
  EXPECT_EQ(fake_.created_target_ns, kTarget120HzNs);
  EXPECT_EQ(fake_.close_session_calls, 0);

  // Partial creation failure must close the newly created session without leak.
  fake_.return_null_session = false;
  fake_.return_null_work_duration = true;
  EXPECT_EQ(CreateManager({101}, kTarget60HzNs), nullptr);
  EXPECT_EQ(fake_.close_session_calls, 1);
  EXPECT_EQ(fake_.release_work_duration_calls, 0);
}

TEST_F(AndroidPerformanceHintManagerTest, DestructorReleasesResourcesOnce) {
  {
    auto manager = CreateManager({101, 202}, kTarget90HzNs);
    ASSERT_NE(manager, nullptr);
    EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget90HzNs);
    EXPECT_EQ(fake_.created_tids, (std::vector<int32_t>{101, 202}));
  }
  EXPECT_EQ(fake_.close_session_calls, 1);
  EXPECT_EQ(fake_.release_work_duration_calls, 1);
}

TEST_F(AndroidPerformanceHintManagerTest, ReportActualWorkDurationAndEpipe) {
  auto manager = CreateManager();
  ASSERT_NE(manager, nullptr);

  manager->ReportActualWorkDuration(0, 8'000'000, 6'000'000, 0);
  manager->ReportActualWorkDuration(-1, 8'000'000, 6'000'000, 0);
  manager->ReportActualWorkDuration(1'000, 0, 6'000'000, 0);
  manager->ReportActualWorkDuration(1'000, -5, 6'000'000, 0);
  manager->ReportActualWorkDuration(1'000, 8'000'000, -1, 2'000'000);
  manager->ReportActualWorkDuration(1'000, 8'000'000, 2'000'000, -1);
  manager->ReportActualWorkDuration(1'000, 8'000'000, 0, 0);
  EXPECT_TRUE(fake_.reported_durations.empty());

  manager->ReportActualWorkDuration(1'000'000, 8'000'000, 6'000'000);
  manager->ReportActualWorkDuration(2'000'000, 5'000'000, 0, 4'500'000);
  manager->ReportActualWorkDuration(3'000'000, 9'000'000, 5'000'000, 4'000'000);
  ASSERT_EQ(fake_.reported_durations.size(), 3u);
  EXPECT_EQ(fake_.reported_durations[0],
            (WorkDurationFields{1'000'000, 8'000'000, 6'000'000, 0}));
  EXPECT_EQ(fake_.reported_durations[1],
            (WorkDurationFields{2'000'000, 5'000'000, 0, 4'500'000}));
  EXPECT_EQ(fake_.reported_durations[2],
            (WorkDurationFields{3'000'000, 9'000'000, 5'000'000, 4'000'000}));

  fake_.report_result = EINVAL;
  manager->ReportActualWorkDuration(4'000'000, 8'000'000, 6'000'000);
  EXPECT_TRUE(fake_.session_open);

  fake_.report_result = EPIPE;
  manager->ReportActualWorkDuration(5'000'000, 8'000'000, 6'000'000);
  ExpectClosedAndSuppressed(manager);
}

TEST_F(AndroidPerformanceHintManagerTest, UpdateTargetWorkDurationHysteresis) {
  auto manager = CreateManager({1001}, kTarget100HzNs);
  ASSERT_NE(manager, nullptr);

  manager->UpdateTargetWorkDuration(0);
  manager->UpdateTargetWorkDuration(-1);
  manager->UpdateTargetWorkDuration(kTarget100HzNs);
  manager->UpdateTargetWorkDuration(kTarget100HzNs - 499'999);
  manager->UpdateTargetWorkDuration(kTarget100HzNs + 499'999);
  EXPECT_TRUE(fake_.updated_targets.empty());

  // Exact -5% and +5% boundaries apply.
  manager->UpdateTargetWorkDuration(kTarget100HzNs - 500'000);
  EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget100HzNs - 500'000);
  manager->UpdateTargetWorkDuration(kTarget100HzNs);
  manager->UpdateTargetWorkDuration(kTarget100HzNs + 500'000);
  EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget100HzNs + 500'000);

  // Non-multiple-of-20 target (60 Hz -> ceil(target/20) = 833,334 ns).
  manager->UpdateTargetWorkDuration(kTarget60HzNs);
  const size_t at_60hz = fake_.updated_targets.size();
  manager->UpdateTargetWorkDuration(kTarget60HzNs - 833'333);
  manager->UpdateTargetWorkDuration(kTarget60HzNs + 833'333);
  EXPECT_EQ(fake_.updated_targets.size(), at_60hz);
  manager->UpdateTargetWorkDuration(kTarget60HzNs - 833'334);
  EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget60HzNs - 833'334);

  // Display mode transitions (90 -> 80 -> 120 Hz) and INT64_MAX overflow check.
  for (int64_t target : {kTarget90HzNs, kTarget80HzNs, kTarget120HzNs,
                         std::numeric_limits<int64_t>::max(), int64_t{1},
                         std::numeric_limits<int64_t>::max()}) {
    manager->UpdateTargetWorkDuration(target);
    EXPECT_EQ(manager->GetTargetWorkDuration(), target);
  }

  fake_.update_target_result = EINVAL;
  manager->UpdateTargetWorkDuration(kTarget60HzNs);
  EXPECT_EQ(manager->GetTargetWorkDuration(),
            std::numeric_limits<int64_t>::max());
  EXPECT_TRUE(fake_.session_open);

  fake_.update_target_result = EPIPE;
  manager->UpdateTargetWorkDuration(kTarget60HzNs);
  ExpectClosedAndSuppressed(manager);
}

TEST(AndroidPerformanceHintManagerLiveTest, LifecycleOnSupportedDevice) {
  if (android_get_device_api_level() < 35) {
    EXPECT_EQ(AndroidPerformanceHintManager::Create({gettid()}, kTarget60HzNs),
              nullptr);
    GTEST_SKIP() << "ADPF AWorkDuration requires Android API level 35+";
  }

  fml::RefPtr<fml::NativeLibrary> lib_android =
      fml::NativeLibrary::Create("libandroid.so");
  ASSERT_TRUE(lib_android);
  constexpr std::array<const char*, 11> kApi35Symbols = {
      "APerformanceHint_getManager",
      "APerformanceHint_createSession",
      "APerformanceHint_updateTargetWorkDuration",
      "APerformanceHint_closeSession",
      "APerformanceHint_reportActualWorkDuration2",
      "AWorkDuration_create",
      "AWorkDuration_release",
      "AWorkDuration_setWorkPeriodStartTimestampNanos",
      "AWorkDuration_setActualTotalDurationNanos",
      "AWorkDuration_setActualCpuDurationNanos",
      "AWorkDuration_setActualGpuDurationNanos",
  };
  for (const char* symbol : kApi35Symbols) {
    SCOPED_TRACE(symbol);
    EXPECT_TRUE(lib_android->ResolveFunction<void*>(symbol).has_value());
  }

  std::unique_ptr<AndroidPerformanceHintManager> manager =
      AndroidPerformanceHintManager::Create({gettid()}, kTarget60HzNs);
  if (manager) {
    EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget60HzNs);

    const int64_t now_ns = fml::TimePoint::Now().ToEpochDelta().ToNanoseconds();
    manager->ReportActualWorkDuration(now_ns, 8'000'000, 6'000'000, 0);

    manager->UpdateTargetWorkDuration(kTarget60HzNs - 500'000);
    EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget60HzNs);
    manager->UpdateTargetWorkDuration(kTarget90HzNs);
    EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget90HzNs);
    manager->UpdateTargetWorkDuration(kTarget80HzNs);
    EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget80HzNs);
    manager->UpdateTargetWorkDuration(kTarget120HzNs);
    EXPECT_EQ(manager->GetTargetWorkDuration(), kTarget120HzNs);
  }
}

}  // namespace
}  // namespace testing
}  // namespace flutter
