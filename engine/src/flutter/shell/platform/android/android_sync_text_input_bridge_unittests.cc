// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_sync_text_input_bridge.h"

#include <cstring>
#include <vector>

#include "gtest/gtest.h"

namespace flutter {
namespace testing {

class AndroidSyncTextInputBridgeTest : public ::testing::Test {
 protected:
  void TearDown() override {
    AndroidSyncTextInputRegistry::Instance().ResetForTesting();
  }
};

TEST_F(AndroidSyncTextInputBridgeTest, UnsupportedWhenUnregisteredOrDisabled) {
  auto& registry = AndroidSyncTextInputRegistry::Instance();

  EXPECT_EQ(registry.IsSupportedForRegistration(42), kSyncImeUnsupported);
  EXPECT_FALSE(registry.IsActiveForPlatform(42));
  EXPECT_EQ(InternalFlutterAndroidSyncImeIsSupported(42), kSyncImeUnsupported);

  const uint64_t token = registry.RegisterShell(
      42, /*sync_flag_enabled=*/false, []() { return true; }, nullptr);
  EXPECT_NE(token, 0u);
  EXPECT_EQ(registry.IsSupportedForRegistration(42), kSyncImeUnsupported);
  EXPECT_FALSE(registry.IsActiveForPlatform(42));
  registry.UnregisterShell(42, token);
}

TEST_F(AndroidSyncTextInputBridgeTest, RetryLaterBeforeThreadMerge) {
  auto& registry = AndroidSyncTextInputRegistry::Instance();

  bool merged = false;
  const uint64_t token = registry.RegisterShell(
      7, /*sync_flag_enabled=*/true, [&merged]() { return merged; }, nullptr);

  EXPECT_EQ(registry.IsSupportedForRegistration(7), kSyncImeRetryLater);
  EXPECT_FALSE(registry.IsActiveForPlatform(7));

  merged = true;
  EXPECT_EQ(registry.IsSupportedForRegistration(7), kSyncImeSupported);
  // Still inactive for platform until Dart registers a handler.
  EXPECT_FALSE(registry.IsActiveForPlatform(7));

  registry.RegisterTestHandlerForTesting(
      7, [](const uint8_t*, int32_t, void*) { return 1; });
  EXPECT_TRUE(registry.IsActiveForPlatform(7));

  merged = false;
  EXPECT_FALSE(registry.IsActiveForPlatform(7));

  registry.UnregisterShell(7, token);
}

TEST_F(AndroidSyncTextInputBridgeTest, DispatchRoundTripAndEmptyResponse) {
  auto& registry = AndroidSyncTextInputRegistry::Instance();

  const uint64_t shell_token = registry.RegisterShell(
      10, /*sync_flag_enabled=*/true, []() { return true; }, nullptr);
  registry.RegisterTestHandlerForTesting(
      10, [](const uint8_t* request, int32_t length, void* response_context) {
        if (length == 0) {
          uint8_t* out =
              InternalFlutterAndroidSyncImeReserveResponse(response_context, 0);
          return out != nullptr ? 1 : -1;
        }
        uint8_t* out = InternalFlutterAndroidSyncImeReserveResponse(
            response_context, length);
        if (out == nullptr) {
          return -1;
        }
        for (int32_t i = 0; i < length; ++i) {
          out[i] = static_cast<uint8_t>(request[i] ^ 0x5A);
        }
        return 1;
      });

  const std::vector<uint8_t> req = {0x01, 0x02, 0x03, 0xFF};
  std::vector<uint8_t> resp;
  ASSERT_TRUE(registry.DispatchToDart(10, req.data(),
                                      static_cast<int32_t>(req.size()), &resp));
  ASSERT_EQ(resp.size(), req.size());
  EXPECT_EQ(resp[0], 0x5B);
  EXPECT_EQ(resp[1], 0x58);
  EXPECT_EQ(resp[2], 0x59);
  EXPECT_EQ(resp[3], 0xA5);

  // Empty response is valid and returns a non-null sentinel from ReserveResponse.
  ASSERT_TRUE(registry.DispatchToDart(10, nullptr, 0, &resp));
  EXPECT_TRUE(resp.empty());

  registry.UnregisterShell(10, shell_token);
}

TEST_F(AndroidSyncTextInputBridgeTest, ReserveResponseValidation) {
  EXPECT_EQ(InternalFlutterAndroidSyncImeReserveResponse(nullptr, 4), nullptr);

  uint64_t fake_context = 0x12345678ULL;
  EXPECT_EQ(InternalFlutterAndroidSyncImeReserveResponse(&fake_context, 4),
            nullptr);

  auto& registry = AndroidSyncTextInputRegistry::Instance();
  registry.RegisterShell(
      11, /*sync_flag_enabled=*/true, []() { return true; }, nullptr);
  registry.RegisterTestHandlerForTesting(
      11, [](const uint8_t*, int32_t, void* response_context) {
        EXPECT_EQ(
            InternalFlutterAndroidSyncImeReserveResponse(response_context, -1),
            nullptr);
        EXPECT_EQ(InternalFlutterAndroidSyncImeReserveResponse(
                      response_context, kMaxSyncImePayloadBytes + 1),
                  nullptr);
        return 0;
      });

  std::vector<uint8_t> resp;
  EXPECT_FALSE(registry.DispatchToDart(11, nullptr, 0, &resp));
  EXPECT_TRUE(resp.empty());
}

TEST_F(AndroidSyncTextInputBridgeTest, TokenGuardsPreventStaleCleanup) {
  auto& registry = AndroidSyncTextInputRegistry::Instance();

  const uint64_t shell_token_1 = registry.RegisterShell(
      20, /*sync_flag_enabled=*/true, []() { return true; }, nullptr);
  const uint64_t handler_token_1 = registry.RegisterTestHandlerForTesting(
      20, [](const uint8_t*, int32_t, void*) { return 1; });

  // Simulate hot restart replacing the handler on the same engine.
  const uint64_t handler_token_2 = registry.RegisterTestHandlerForTesting(
      20, [](const uint8_t*, int32_t, void*) { return 1; });
  EXPECT_NE(handler_token_1, handler_token_2);

  // Stale shutdown callback from the old isolate must not clear the new one.
  registry.UnregisterDartHandler(20, handler_token_1);
  EXPECT_TRUE(registry.IsActiveForPlatform(20));

  // Matching handler token clears it.
  registry.UnregisterDartHandler(20, handler_token_2);
  EXPECT_FALSE(registry.IsActiveForPlatform(20));

  // Replacing shell with a newer shell token protects against stale unregister.
  const uint64_t shell_token_2 = registry.RegisterShell(
      20, /*sync_flag_enabled=*/true, []() { return true; }, nullptr);
  registry.RegisterTestHandlerForTesting(
      20, [](const uint8_t*, int32_t, void*) { return 1; });
  registry.UnregisterShell(20, shell_token_1);
  EXPECT_TRUE(registry.IsActiveForPlatform(20));

  registry.UnregisterShell(20, shell_token_2);
  EXPECT_FALSE(registry.IsActiveForPlatform(20));
}

TEST_F(AndroidSyncTextInputBridgeTest, ReentrantNotifyDoesNotDeadlock) {
  auto& registry = AndroidSyncTextInputRegistry::Instance();

  int notify_calls = 0;
  int last_client_id = -1;
  int last_sel_start = -1;
  registry.RegisterShell(
      30, /*sync_flag_enabled=*/true, []() { return true; },
      [&](int32_t client_id, int32_t flags, int32_t sel_start, int32_t sel_end,
          int32_t comp_start, int32_t comp_end) {
        ++notify_calls;
        last_client_id = client_id;
        last_sel_start = sel_start;
        EXPECT_EQ(flags, 3);
        EXPECT_EQ(sel_end, 5);
        EXPECT_EQ(comp_start, -1);
        EXPECT_EQ(comp_end, -1);
        return true;
      });

  // Inside DispatchToDart, synchronously invoke NotifyEditingState (which must
  // re-enter AndroidSyncTextInputRegistry without deadlocking on its mutex).
  registry.RegisterTestHandlerForTesting(
      30, [](const uint8_t*, int32_t, void* response_context) {
        const int32_t notified =
            InternalFlutterAndroidSyncImeNotifyEditingState(30, 99, 3, 2, 5, -1,
                                                            -1);
        EXPECT_EQ(notified, 1);
        uint8_t* out =
            InternalFlutterAndroidSyncImeReserveResponse(response_context, 1);
        out[0] = 0x7F;
        return 1;
      });

  std::vector<uint8_t> resp;
  ASSERT_TRUE(registry.DispatchToDart(30, nullptr, 0, &resp));
  EXPECT_EQ(notify_calls, 1);
  EXPECT_EQ(last_client_id, 99);
  EXPECT_EQ(last_sel_start, 2);
  ASSERT_EQ(resp.size(), 1u);
  EXPECT_EQ(resp[0], 0x7F);
}

TEST_F(AndroidSyncTextInputBridgeTest, MultiEngineIsolation) {
  auto& registry = AndroidSyncTextInputRegistry::Instance();

  registry.RegisterShell(
      100, /*sync_flag_enabled=*/true, []() { return true; }, nullptr);
  registry.RegisterShell(
      200, /*sync_flag_enabled=*/true, []() { return true; }, nullptr);

  registry.RegisterTestHandlerForTesting(
      100, [](const uint8_t*, int32_t, void* response_context) {
        uint8_t* out =
            InternalFlutterAndroidSyncImeReserveResponse(response_context, 1);
        out[0] = 0x11;
        return 1;
      });
  registry.RegisterTestHandlerForTesting(
      200, [](const uint8_t*, int32_t, void* response_context) {
        uint8_t* out =
            InternalFlutterAndroidSyncImeReserveResponse(response_context, 1);
        out[0] = 0x22;
        return 1;
      });

  std::vector<uint8_t> resp1;
  std::vector<uint8_t> resp2;
  ASSERT_TRUE(registry.DispatchToDart(100, nullptr, 0, &resp1));
  ASSERT_TRUE(registry.DispatchToDart(200, nullptr, 0, &resp2));
  ASSERT_EQ(resp1.size(), 1u);
  ASSERT_EQ(resp2.size(), 1u);
  EXPECT_EQ(resp1[0], 0x11);
  EXPECT_EQ(resp2[0], 0x22);

  // Clearing engine 100 leaves engine 200 unaffected.
  registry.ClearDartHandler(100);
  EXPECT_FALSE(registry.IsActiveForPlatform(100));
  EXPECT_TRUE(registry.IsActiveForPlatform(200));
}

}  // namespace testing
}  // namespace flutter
