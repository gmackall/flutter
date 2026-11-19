// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_ANDROID_SYNC_TEXT_INPUT_BRIDGE_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_ANDROID_SYNC_TEXT_INPUT_BRIDGE_H_

#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "flutter/fml/macros.h"

namespace flutter {

class DartIsolate;

// C-ABI callback implemented by Dart via NativeCallable.isolateLocal.
//
// Parameters:
// - request_data / request_length: read-only binary request frame valid only
//   for the synchronous duration of this call.
// - response_context: opaque handle passed back to
//   InternalFlutterAndroidSyncImeReserveResponse to allocate the response body.
//
// Returns:
// - 1 on handled request,
// - 0 on unhandled / stale request,
// - -1 on exceptional return.
using SyncImeDispatchCallback = int32_t (*)(const uint8_t* request_data,
                                            int32_t request_length,
                                            void* response_context);

// Result codes for InternalFlutterAndroidSyncImeIsSupported and
// InternalFlutterAndroidSyncImeRegisterHandler.
enum SyncImeSupportStatus : int32_t {
  // Feature is disabled or engine_id is unknown. Callers should not retry.
  kSyncImeUnsupported = 0,
  // Supported / registered successfully.
  kSyncImeSupported = 1,
  // Temporarily unavailable on the calling thread (for example, while
  // mergeAfterLaunch has not yet migrated the UI thread onto the platform
  // thread). Callers may retry on a later attach.
  kSyncImeRetryLater = 2,
};

// Maximum allowed payload size (16 MiB) for synchronous text input messages.
inline constexpr int32_t kMaxSyncImePayloadBytes = 16 * 1024 * 1024;

// Process-wide registry binding Android FlutterEngine instances (keyed by
// engine_id) to their root Dart isolate's synchronous IME callback.
//
// Threading & lifetime invariants:
// - The internal mutex is only held while reading/updating map entries and is
//   NEVER held while invoking Dart or JNI callbacks (preventing deadlocks on
//   reentrant IME <-> framework calls).
// - Dart callbacks are backed by a weak_ptr<DartIsolate> and cleared on all
//   three lifecycle boundaries: DartIsolate::AddIsolateShutdownCallback,
//   PlatformViewAndroid::OnPreEngineRestart, and ~AndroidShellHolder.
// - Every dispatch verifies that the engine's UI task runner executes on the
//   current thread and enters tonic::DartIsolateScope before invoking the
//   NativeCallable.isolateLocal pointer.
class AndroidSyncTextInputRegistry {
 public:
  using ThreadCheckCallback = std::function<bool()>;
  using NotifyEditingStateCallback = std::function<bool(int32_t client_id,
                                                        int32_t flags,
                                                        int32_t selection_start,
                                                        int32_t selection_end,
                                                        int32_t composing_start,
                                                        int32_t composing_end)>;
  // Test-only bypass for dispatching without a live Dart VM isolate.
  using TestDispatchCallback = std::function<int32_t(const uint8_t* request,
                                                     int32_t length,
                                                     void* response_context)>;

  static AndroidSyncTextInputRegistry& Instance();

  // Registers an AndroidShellHolder for |engine_id| and returns a monotonic
  // shell token used to safely unregister on destruction without clobbering a
  // newer shell.
  uint64_t RegisterShell(int64_t engine_id,
                         bool sync_flag_enabled,
                         ThreadCheckCallback is_ui_on_current_thread,
                         NotifyEditingStateCallback notify_editing_state);

  // Unregisters the shell entry for |engine_id| if its token matches
  // |shell_token|. Clears any registered Dart handler for this engine.
  void UnregisterShell(int64_t engine_id, uint64_t shell_token);

  // Clears any registered Dart handler for |engine_id| (e.g. on hot restart).
  void ClearDartHandler(int64_t engine_id);

  // Clears the registered Dart handler only if its registration token matches
  // |handler_token| (used by DartIsolate shutdown callbacks so a dying isolate
  // cannot clear a newer restarted isolate's handler).
  void UnregisterDartHandler(int64_t engine_id, uint64_t handler_token);

  // Returns whether |engine_id| supports registering a synchronous IME handler
  // from the current thread.
  int32_t IsSupportedForRegistration(int64_t engine_id) const;

  // Returns true iff |engine_id| has sync IME enabled, is currently running on
  // the merged UI/platform thread, and has a live Dart handler registered.
  bool IsActiveForPlatform(int64_t engine_id) const;

  // Registers or unregisters (when |callback| is nullptr) the root Dart
  // isolate's synchronous IME callback for |engine_id|.
  int32_t RegisterDartHandler(int64_t engine_id,
                              SyncImeDispatchCallback callback);

  // Dispatches a binary IME request from Android to Dart synchronously.
  // Returns true if handled and populates |response_out|.
  bool DispatchToDart(int64_t engine_id,
                      const uint8_t* request_data,
                      int32_t request_length,
                      std::vector<uint8_t>* response_out);

  // Delivers a synchronous programmatic editing state notification from Dart
  // to the Android platform's TextInputPlugin.
  bool NotifyPlatformEditingState(int64_t engine_id,
                                  int32_t client_id,
                                  int32_t flags,
                                  int32_t selection_start,
                                  int32_t selection_end,
                                  int32_t composing_start,
                                  int32_t composing_end);

  // Reserves |length| bytes in the response buffer pointed to by
  // |response_context|. Returns nullptr if |response_context| is null or
  // |length| is negative / exceeds kMaxSyncImePayloadBytes.
  static uint8_t* ReserveResponse(void* response_context, int32_t length);

  // Test helpers (allow exercising registry lifecycle and dispatch logic in
  // C++ unit tests without a running Dart VM).
  uint64_t RegisterTestHandlerForTesting(int64_t engine_id,
                                         TestDispatchCallback handler);
  void ResetForTesting();

 private:
  struct EngineEntry {
    bool sync_flag_enabled = false;
    ThreadCheckCallback is_ui_on_current_thread;
    NotifyEditingStateCallback notify_editing_state;
    uint64_t shell_token = 0;

    SyncImeDispatchCallback dart_callback = nullptr;
    std::weak_ptr<DartIsolate> weak_isolate;
    TestDispatchCallback test_callback;
    uint64_t handler_token = 0;
  };

  AndroidSyncTextInputRegistry() = default;

  mutable std::mutex mutex_;
  std::unordered_map<int64_t, EngineEntry> engines_;
  uint64_t next_token_ = 1;

  FML_DISALLOW_COPY_AND_ASSIGN(AndroidSyncTextInputRegistry);
};

}  // namespace flutter

// Internal FFI entry points exported by libflutter.so for package:flutter's
// Android synchronous text input bridge. Not part of any public ABI.
extern "C" {

__attribute__((visibility("default"))) int32_t
InternalFlutterAndroidSyncImeIsSupported(int64_t engine_id);

__attribute__((visibility("default"))) int32_t
InternalFlutterAndroidSyncImeRegisterHandler(
    int64_t engine_id,
    flutter::SyncImeDispatchCallback callback);

__attribute__((visibility("default"))) uint8_t*
InternalFlutterAndroidSyncImeReserveResponse(void* response_context,
                                             int32_t length);

__attribute__((visibility("default"))) int32_t
InternalFlutterAndroidSyncImeNotifyEditingState(int64_t engine_id,
                                                int32_t client_id,
                                                int32_t flags,
                                                int32_t selection_start,
                                                int32_t selection_end,
                                                int32_t composing_start,
                                                int32_t composing_end);

}  // extern "C"

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_ANDROID_SYNC_TEXT_INPUT_BRIDGE_H_
