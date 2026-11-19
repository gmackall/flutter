// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_sync_text_input_bridge.h"

#include <atomic>
#include <cstdint>
#include <vector>

#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/shell/common/shell_test.h"
#include "flutter/shell/common/thread_host.h"
#include "gtest/gtest.h"

namespace flutter {
namespace testing {

class AndroidSyncTextInputIsolateTest : public ShellTest {
 protected:
  void TearDown() override {
    AndroidSyncTextInputRegistry::Instance().ResetForTesting();
    ShellTest::TearDown();
  }
};

TEST_F(AndroidSyncTextInputIsolateTest,
       RealIsolateRoundTripMicrotaskFlushAndHotRestart) {
  Settings settings = CreateSettingsForFixture();
  settings.merged_platform_ui_thread =
      Settings::MergedPlatformUIThread::kEnabled;
  settings.enable_android_sync_text_input = true;

  ThreadHost thread_host(ThreadHost::ThreadHostConfig(
      "io.flutter.test.sync_ime_isolate.",
      ThreadHost::Type::kPlatform | ThreadHost::Type::kRaster |
          ThreadHost::Type::kIo));
  auto platform_runner = thread_host.platform_thread->GetTaskRunner();
  TaskRunners task_runners("test", platform_runner,
                           thread_host.raster_thread->GetTaskRunner(),
                           platform_runner,
                           thread_host.io_thread->GetTaskRunner());

  constexpr int64_t kEngineId = 1001;
  std::atomic<int> notify_count{0};
  int32_t last_client_id = -1;
  int32_t last_flags = -1;
  int32_t last_sel_start = -1;
  int32_t last_sel_end = -1;

  auto& registry = AndroidSyncTextInputRegistry::Instance();
  const uint64_t shell_token = registry.RegisterShell(
      kEngineId, /*sync_flag_enabled=*/true,
      [platform_runner]() {
        return platform_runner->RunsTasksOnCurrentThread();
      },
      [&](int32_t client_id, int32_t flags, int32_t sel_start, int32_t sel_end,
          int32_t comp_start, int32_t comp_end) {
        notify_count.fetch_add(1);
        last_client_id = client_id;
        last_flags = flags;
        last_sel_start = sel_start;
        last_sel_end = sel_end;
        EXPECT_EQ(comp_start, -1);
        EXPECT_EQ(comp_end, -1);
        return true;
      });

  std::unique_ptr<Shell> shell = CreateShell({
      .settings = settings,
      .task_runners = task_runners,
      .engine_id = kEngineId,
  });
  ASSERT_TRUE(shell);

  fml::AutoResetWaitableEvent registered_latch;
  AddFfiNativeCallback(
      "NotifyNative",
      CREATE_FFI_LAMBDA([&registered_latch]() { registered_latch.Signal(); }));

  auto configuration = RunConfiguration::InferFromSettings(settings);
  configuration.SetEngineId(kEngineId);
  configuration.SetEntrypoint("syncImeRegisterAndEcho");
  RunEngine(shell.get(), std::move(configuration));
  registered_latch.Wait();

  // Dispatch synchronously on the merged platform/UI thread and verify both
  // the NativeCallable.isolateLocal round-trip and inline FlushMicrotasksNow().
  fml::AutoResetWaitableEvent dispatch_latch;
  platform_runner->PostTask([&]() {
    EXPECT_TRUE(registry.IsActiveForPlatform(kEngineId));

    const std::vector<uint8_t> req = {0xAA, 0x10, 0x20};
    std::vector<uint8_t> resp;
    ASSERT_TRUE(registry.DispatchToDart(
        kEngineId, req.data(), static_cast<int32_t>(req.size()), &resp));
    ASSERT_EQ(resp.size(), 3u);
    EXPECT_EQ(resp[0], static_cast<uint8_t>(0xAA ^ 0x5A));
    EXPECT_EQ(resp[1], static_cast<uint8_t>(0x10 ^ 0x5A));
    EXPECT_EQ(resp[2], static_cast<uint8_t>(0x20 ^ 0x5A));

    // The microtask scheduled inside the sync callback must have flushed
    // synchronously before DispatchToDart returned.
    EXPECT_EQ(notify_count.load(), 1);
    EXPECT_EQ(last_client_id, 7);
    EXPECT_EQ(last_flags, 1);
    EXPECT_EQ(last_sel_start, 2);
    EXPECT_EQ(last_sel_end, 3);

    dispatch_latch.Signal();
  });
  dispatch_latch.Wait();

  // Simulate hot restart: launching a new isolate shuts down the old isolate,
  // firing DartIsolate::AddIsolateShutdownCallback -> UnregisterDartHandler.
  auto restart_config = RunConfiguration::InferFromSettings(settings);
  restart_config.SetEngineId(kEngineId);
  restart_config.SetEntrypoint("emptyMain");
  RestartEngine(shell.get(), std::move(restart_config));

  fml::AutoResetWaitableEvent after_restart_latch;
  platform_runner->PostTask([&]() {
    EXPECT_FALSE(registry.IsActiveForPlatform(kEngineId));
    const std::vector<uint8_t> req = {0x01};
    std::vector<uint8_t> resp;
    EXPECT_FALSE(registry.DispatchToDart(
        kEngineId, req.data(), static_cast<int32_t>(req.size()), &resp));
    EXPECT_TRUE(resp.empty());
    after_restart_latch.Signal();
  });
  after_restart_latch.Wait();

  registry.UnregisterShell(kEngineId, shell_token);
  DestroyShell(std::move(shell), task_runners);
}

TEST_F(AndroidSyncTextInputIsolateTest,
       RealIsolateOwnershipAndSpawnIsolation) {
  Settings settings = CreateSettingsForFixture();
  settings.merged_platform_ui_thread =
      Settings::MergedPlatformUIThread::kEnabled;
  settings.enable_android_sync_text_input = true;

  ThreadHost thread_host(ThreadHost::ThreadHostConfig(
      "io.flutter.test.sync_ime_spawn.",
      ThreadHost::Type::kPlatform | ThreadHost::Type::kRaster |
          ThreadHost::Type::kIo));
  auto platform_runner = thread_host.platform_thread->GetTaskRunner();
  TaskRunners task_runners("test", platform_runner,
                           thread_host.raster_thread->GetTaskRunner(),
                           platform_runner,
                           thread_host.io_thread->GetTaskRunner());

  constexpr int64_t kParentEngineId = 2001;
  constexpr int64_t kChildEngineId = 2002;
  std::atomic<int> parent_notifies{0};
  std::atomic<int> child_notifies{0};

  auto& registry = AndroidSyncTextInputRegistry::Instance();
  const uint64_t parent_token = registry.RegisterShell(
      kParentEngineId, /*sync_flag_enabled=*/true,
      [platform_runner]() {
        return platform_runner->RunsTasksOnCurrentThread();
      },
      [&](int32_t, int32_t, int32_t, int32_t, int32_t, int32_t) {
        parent_notifies.fetch_add(1);
        return true;
      });
  const uint64_t child_token = registry.RegisterShell(
      kChildEngineId, /*sync_flag_enabled=*/true,
      [platform_runner]() {
        return platform_runner->RunsTasksOnCurrentThread();
      },
      [&](int32_t, int32_t, int32_t, int32_t, int32_t, int32_t) {
        child_notifies.fetch_add(1);
        return true;
      });

  auto create_headless_platform_view = [](Shell& shell) {
    return std::make_unique<PlatformView>(shell, shell.GetTaskRunners());
  };

  std::unique_ptr<Shell> parent_shell = CreateShell({
      .settings = settings,
      .task_runners = task_runners,
      .platform_view_create_callback = create_headless_platform_view,
      .engine_id = kParentEngineId,
  });
  ASSERT_TRUE(parent_shell);

  fml::AutoResetWaitableEvent latch;
  AddFfiNativeCallback(
      "NotifyNative", CREATE_FFI_LAMBDA([&latch]() { latch.Signal(); }));

  auto parent_config = RunConfiguration::InferFromSettings(settings);
  parent_config.SetEngineId(kParentEngineId);
  parent_config.SetEntrypoint("syncImeRegisterAndCheckOwnership");
  RunEngine(parent_shell.get(), std::move(parent_config));
  latch.Wait();

  // Spawn child shell sharing the merged platform/UI thread and register it.
  std::unique_ptr<Shell> child_shell;
  fml::AutoResetWaitableEvent spawn_latch;
  platform_runner->PostTask([&]() {
    auto child_config = RunConfiguration::InferFromSettings(settings);
    child_config.SetEngineId(kChildEngineId);
    child_config.SetEntrypoint("syncImeRegisterAndCheckOwnership");
    child_shell = parent_shell->Spawn(
        std::move(child_config), "", create_headless_platform_view,
        [](Shell& shell) { return std::make_unique<Rasterizer>(shell); });
    spawn_latch.Signal();
  });
  spawn_latch.Wait();
  ASSERT_TRUE(child_shell);
  latch.Wait();

  // Verify both engines remain independently active and return distinct bytes.
  fml::AutoResetWaitableEvent verify_latch;
  platform_runner->PostTask([&]() {
    EXPECT_TRUE(registry.IsActiveForPlatform(kParentEngineId));
    EXPECT_TRUE(registry.IsActiveForPlatform(kChildEngineId));
    EXPECT_EQ(parent_notifies.load(), 0);
    EXPECT_EQ(child_notifies.load(), 0);

    std::vector<uint8_t> parent_resp;
    std::vector<uint8_t> child_resp;
    ASSERT_TRUE(registry.DispatchToDart(kParentEngineId, nullptr, 0,
                                        &parent_resp));
    ASSERT_TRUE(
        registry.DispatchToDart(kChildEngineId, nullptr, 0, &child_resp));
    ASSERT_EQ(parent_resp.size(), 1u);
    ASSERT_EQ(child_resp.size(), 1u);
    EXPECT_EQ(parent_resp[0], static_cast<uint8_t>(kParentEngineId & 0xFF));
    EXPECT_EQ(child_resp[0], static_cast<uint8_t>(kChildEngineId & 0xFF));
    verify_latch.Signal();
  });
  verify_latch.Wait();

  // Destroy child shell; parent must remain active and functional.
  registry.UnregisterShell(kChildEngineId, child_token);
  DestroyShell(std::move(child_shell), task_runners);

  fml::AutoResetWaitableEvent after_child_destroy_latch;
  platform_runner->PostTask([&]() {
    EXPECT_FALSE(registry.IsActiveForPlatform(kChildEngineId));
    EXPECT_TRUE(registry.IsActiveForPlatform(kParentEngineId));
    std::vector<uint8_t> parent_resp;
    ASSERT_TRUE(registry.DispatchToDart(kParentEngineId, nullptr, 0,
                                        &parent_resp));
    ASSERT_EQ(parent_resp.size(), 1u);
    EXPECT_EQ(parent_resp[0], static_cast<uint8_t>(kParentEngineId & 0xFF));
    after_child_destroy_latch.Signal();
  });
  after_child_destroy_latch.Wait();

  registry.UnregisterShell(kParentEngineId, parent_token);
  DestroyShell(std::move(parent_shell), task_runners);
}

}  // namespace testing
}  // namespace flutter
