// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/android_sync_text_input_bridge.h"

#include <utility>

#include "flutter/fml/logging.h"
#include "flutter/lib/ui/ui_dart_state.h"
#include "flutter/lib/ui/window/platform_configuration.h"
#include "flutter/runtime/dart_isolate.h"
#include "third_party/dart/runtime/include/dart_api.h"
#include "third_party/tonic/scopes/dart_isolate_scope.h"

namespace flutter {

namespace {

// A response buffer passed through the opaque void* response_context pointer
// during synchronous dispatch. Using a tagged struct avoids accidental
// misinterpretation if an invalid context were ever passed.
struct SyncImeResponseBuffer {
  static constexpr uint32_t kMagic = 0x53494D45;  // 'SIME'
  uint32_t magic = kMagic;
  std::vector<uint8_t>* bytes = nullptr;
};

// Verifies that the currently executing Dart isolate is the root isolate that
// owns |engine_id|. This prevents background isolates or another engine's
// isolate (when multiple engines share the merged platform/UI thread) from
// registering, clearing, or pushing notifications for an unowned engine.
bool DoesCurrentIsolateOwnEngine(int64_t engine_id,
                                 bool require_running,
                                 DartIsolate** out_isolate = nullptr) {
  if (Dart_CurrentIsolate() == nullptr) {
    return false;
  }
  UIDartState* ui_state = UIDartState::Current();
  if (ui_state == nullptr) {
    return false;
  }
  auto* dart_isolate = static_cast<DartIsolate*>(ui_state);
  if (!dart_isolate->IsRootIsolate()) {
    return false;
  }
  if (require_running &&
      (dart_isolate->GetPhase() != DartIsolate::Phase::Running ||
       dart_isolate->IsShuttingDown())) {
    return false;
  }
  PlatformConfiguration* platform_config = ui_state->platform_configuration();
  if (platform_config == nullptr || !platform_config->engine_id().has_value() ||
      *platform_config->engine_id() != engine_id) {
    return false;
  }
  if (out_isolate != nullptr) {
    *out_isolate = dart_isolate;
  }
  return true;
}

}  // namespace

AndroidSyncTextInputRegistry& AndroidSyncTextInputRegistry::Instance() {
  static auto* instance = new AndroidSyncTextInputRegistry();
  return *instance;
}

uint64_t AndroidSyncTextInputRegistry::RegisterShell(
    int64_t engine_id,
    bool sync_flag_enabled,
    ThreadCheckCallback is_ui_on_current_thread,
    NotifyEditingStateCallback notify_editing_state) {
  std::lock_guard<std::mutex> lock(mutex_);
  const uint64_t token = next_token_++;
  EngineEntry& entry = engines_[engine_id];
  entry.sync_flag_enabled = sync_flag_enabled;
  entry.is_ui_on_current_thread = std::move(is_ui_on_current_thread);
  entry.notify_editing_state = std::move(notify_editing_state);
  entry.shell_token = token;
  entry.dart_callback = nullptr;
  entry.weak_isolate.reset();
  entry.test_callback = nullptr;
  entry.handler_token = 0;
  return token;
}

void AndroidSyncTextInputRegistry::UnregisterShell(int64_t engine_id,
                                                   uint64_t shell_token) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = engines_.find(engine_id);
  if (it == engines_.end() || it->second.shell_token != shell_token) {
    return;
  }
  engines_.erase(it);
}

void AndroidSyncTextInputRegistry::ClearDartHandler(int64_t engine_id) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = engines_.find(engine_id);
  if (it == engines_.end()) {
    return;
  }
  it->second.dart_callback = nullptr;
  it->second.weak_isolate.reset();
  it->second.test_callback = nullptr;
  it->second.handler_token = 0;
}

void AndroidSyncTextInputRegistry::UnregisterDartHandler(
    int64_t engine_id,
    uint64_t handler_token) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = engines_.find(engine_id);
  if (it == engines_.end() || it->second.handler_token != handler_token) {
    return;
  }
  it->second.dart_callback = nullptr;
  it->second.weak_isolate.reset();
  it->second.test_callback = nullptr;
  it->second.handler_token = 0;
}

int32_t AndroidSyncTextInputRegistry::IsSupportedForRegistration(
    int64_t engine_id) const {
  ThreadCheckCallback thread_check;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = engines_.find(engine_id);
    if (it == engines_.end() || !it->second.sync_flag_enabled ||
        !it->second.is_ui_on_current_thread) {
      return kSyncImeUnsupported;
    }
    thread_check = it->second.is_ui_on_current_thread;
  }
  if (!thread_check()) {
    return kSyncImeRetryLater;
  }
  return kSyncImeSupported;
}

bool AndroidSyncTextInputRegistry::IsActiveForPlatform(
    int64_t engine_id) const {
  ThreadCheckCallback thread_check;
  SyncImeDispatchCallback dart_callback = nullptr;
  std::weak_ptr<DartIsolate> weak_isolate;
  bool has_test_callback = false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = engines_.find(engine_id);
    if (it == engines_.end() || !it->second.sync_flag_enabled ||
        !it->second.is_ui_on_current_thread) {
      return false;
    }
    thread_check = it->second.is_ui_on_current_thread;
    dart_callback = it->second.dart_callback;
    weak_isolate = it->second.weak_isolate;
    has_test_callback = static_cast<bool>(it->second.test_callback);
  }
  if (!thread_check()) {
    return false;
  }
  if (has_test_callback) {
    return true;
  }
  if (dart_callback == nullptr) {
    return false;
  }
  auto isolate = weak_isolate.lock();
  if (!isolate || isolate->GetPhase() != DartIsolate::Phase::Running ||
      isolate->IsShuttingDown()) {
    return false;
  }
  const auto& ui_runner = isolate->GetTaskRunners().GetUITaskRunner();
  return ui_runner && ui_runner->RunsTasksOnCurrentThread();
}

int32_t AndroidSyncTextInputRegistry::RegisterDartHandler(
    int64_t engine_id,
    SyncImeDispatchCallback callback) {
  if (callback == nullptr) {
    if (!DoesCurrentIsolateOwnEngine(engine_id, /*require_running=*/false)) {
      return kSyncImeUnsupported;
    }
    ClearDartHandler(engine_id);
    return kSyncImeSupported;
  }

  DartIsolate* dart_isolate = nullptr;
  if (!DoesCurrentIsolateOwnEngine(engine_id, /*require_running=*/true,
                                   &dart_isolate)) {
    return kSyncImeUnsupported;
  }

  ThreadCheckCallback thread_check;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = engines_.find(engine_id);
    if (it == engines_.end() || !it->second.sync_flag_enabled ||
        !it->second.is_ui_on_current_thread) {
      return kSyncImeUnsupported;
    }
    thread_check = it->second.is_ui_on_current_thread;
  }

  // Verify the isolate runs on the merged platform/UI thread. If the engine is
  // using mergeAfterLaunch and has not yet merged onto the platform thread,
  // signal RetryLater so Dart can retry on a subsequent attach.
  const auto& ui_runner = dart_isolate->GetTaskRunners().GetUITaskRunner();
  if (!thread_check() || !ui_runner || !ui_runner->RunsTasksOnCurrentThread()) {
    return kSyncImeRetryLater;
  }

  uint64_t handler_token = 0;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = engines_.find(engine_id);
    if (it == engines_.end() || !it->second.sync_flag_enabled) {
      return kSyncImeUnsupported;
    }
    handler_token = next_token_++;
    it->second.dart_callback = callback;
    it->second.weak_isolate = dart_isolate->GetWeakIsolatePtr();
    it->second.test_callback = nullptr;
    it->second.handler_token = handler_token;
  }

  // Register a shutdown hook outside the lock so the callback pointer is
  // guaranteed to be cleared before this isolate tears down (including on hot
  // restart or unhandled fatal errors).
  dart_isolate->AddIsolateShutdownCallback([engine_id, handler_token]() {
    AndroidSyncTextInputRegistry::Instance().UnregisterDartHandler(
        engine_id, handler_token);
  });

  return kSyncImeSupported;
}

bool AndroidSyncTextInputRegistry::DispatchToDart(
    int64_t engine_id,
    const uint8_t* request_data,
    int32_t request_length,
    std::vector<uint8_t>* response_out) {
  if (response_out == nullptr || request_length < 0 ||
      request_length > kMaxSyncImePayloadBytes ||
      (request_length > 0 && request_data == nullptr)) {
    return false;
  }
  response_out->clear();

  ThreadCheckCallback thread_check;
  SyncImeDispatchCallback dart_callback = nullptr;
  std::weak_ptr<DartIsolate> weak_isolate;
  TestDispatchCallback test_callback;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = engines_.find(engine_id);
    if (it == engines_.end() || !it->second.sync_flag_enabled ||
        !it->second.is_ui_on_current_thread) {
      return false;
    }
    thread_check = it->second.is_ui_on_current_thread;
    dart_callback = it->second.dart_callback;
    weak_isolate = it->second.weak_isolate;
    test_callback = it->second.test_callback;
  }

  if (!thread_check()) {
    return false;
  }

  SyncImeResponseBuffer response_buffer;
  response_buffer.bytes = response_out;

  if (test_callback) {
    const int32_t status =
        test_callback(request_data, request_length, &response_buffer);
    if (status != 1) {
      response_out->clear();
      return false;
    }
    return true;
  }

  if (dart_callback == nullptr) {
    return false;
  }

  auto isolate = weak_isolate.lock();
  if (!isolate || isolate->GetPhase() != DartIsolate::Phase::Running ||
      isolate->IsShuttingDown()) {
    return false;
  }

  const auto& ui_runner = isolate->GetTaskRunners().GetUITaskRunner();
  if (!ui_runner || !ui_runner->RunsTasksOnCurrentThread()) {
    return false;
  }

  if (isolate->isolate() == nullptr) {
    return false;
  }

  const bool was_in_dart = (Dart_CurrentIsolate() != nullptr);
  int32_t status = 0;
  {
    tonic::DartIsolateScope isolate_scope(isolate->isolate());
    status = dart_callback(request_data, request_length, &response_buffer);
  }

  // Raw JNI -> NativeCallable calls bypass fml::MessageLoop task observers,
  // so drain microtasks on outermost exit (e.g. TextInput._scheduleHide).
  // FlushMicrotasksNow() establishes its own DartState::Scope per microtask.
  // When re-entrant (was_in_dart), avoid running microtasks mid-turn; post
  // a flush task so they still drain promptly.
  if (!was_in_dart) {
    if (isolate->HasPendingMicrotasks() && !isolate->IsShuttingDown() &&
        isolate->GetPhase() == DartIsolate::Phase::Running) {
      isolate->FlushMicrotasksNow();
    }
  } else if (isolate->HasPendingMicrotasks()) {
    ui_runner->PostTask([weak = weak_isolate]() {
      auto locked = weak.lock();
      if (!locked || locked->IsShuttingDown() ||
          locked->GetPhase() != DartIsolate::Phase::Running) {
        return;
      }
      if (locked->HasPendingMicrotasks()) {
        locked->FlushMicrotasksNow();
      }
    });
  }

  if (status != 1) {
    response_out->clear();
    return false;
  }
  return true;
}

bool AndroidSyncTextInputRegistry::NotifyPlatformEditingState(
    int64_t engine_id,
    int32_t client_id,
    int32_t flags,
    int32_t selection_start,
    int32_t selection_end,
    int32_t composing_start,
    int32_t composing_end) {
  if (Dart_CurrentIsolate() != nullptr &&
      !DoesCurrentIsolateOwnEngine(engine_id, /*require_running=*/true)) {
    return false;
  }
  ThreadCheckCallback thread_check;
  NotifyEditingStateCallback notify_callback;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = engines_.find(engine_id);
    if (it == engines_.end() || !it->second.sync_flag_enabled ||
        !it->second.is_ui_on_current_thread ||
        !it->second.notify_editing_state) {
      return false;
    }
    thread_check = it->second.is_ui_on_current_thread;
    notify_callback = it->second.notify_editing_state;
  }

  if (!thread_check()) {
    return false;
  }
  return notify_callback(client_id, flags, selection_start, selection_end,
                         composing_start, composing_end);
}

uint8_t* AndroidSyncTextInputRegistry::ReserveResponse(void* response_context,
                                                       int32_t length) {
  if (response_context == nullptr || length < 0 ||
      length > kMaxSyncImePayloadBytes) {
    return nullptr;
  }
  auto* buffer = static_cast<SyncImeResponseBuffer*>(response_context);
  if (buffer->magic != SyncImeResponseBuffer::kMagic ||
      buffer->bytes == nullptr) {
    return nullptr;
  }
  buffer->bytes->resize(static_cast<size_t>(length));
  if (length == 0) {
    static uint8_t kEmptyResponseSentinel = 0;
    return &kEmptyResponseSentinel;
  }
  return buffer->bytes->data();
}

uint64_t AndroidSyncTextInputRegistry::RegisterTestHandlerForTesting(
    int64_t engine_id,
    TestDispatchCallback handler) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = engines_.find(engine_id);
  if (it == engines_.end()) {
    return 0;
  }
  const uint64_t token = next_token_++;
  it->second.dart_callback = nullptr;
  it->second.weak_isolate.reset();
  it->second.test_callback = std::move(handler);
  it->second.handler_token = token;
  return token;
}

void AndroidSyncTextInputRegistry::ResetForTesting() {
  std::lock_guard<std::mutex> lock(mutex_);
  engines_.clear();
}

}  // namespace flutter

extern "C" {

int32_t InternalFlutterAndroidSyncImeIsSupported(int64_t engine_id) {
  return flutter::AndroidSyncTextInputRegistry::Instance()
      .IsSupportedForRegistration(engine_id);
}

int32_t InternalFlutterAndroidSyncImeRegisterHandler(
    int64_t engine_id,
    flutter::SyncImeDispatchCallback callback) {
  return flutter::AndroidSyncTextInputRegistry::Instance().RegisterDartHandler(
      engine_id, callback);
}

uint8_t* InternalFlutterAndroidSyncImeReserveResponse(void* response_context,
                                                      int32_t length) {
  return flutter::AndroidSyncTextInputRegistry::ReserveResponse(
      response_context, length);
}

int32_t InternalFlutterAndroidSyncImeNotifyEditingState(
    int64_t engine_id,
    int32_t client_id,
    int32_t flags,
    int32_t selection_start,
    int32_t selection_end,
    int32_t composing_start,
    int32_t composing_end) {
  return flutter::AndroidSyncTextInputRegistry::Instance()
                 .NotifyPlatformEditingState(
                     engine_id, client_id, flags, selection_start,
                     selection_end, composing_start, composing_end)
             ? 1
             : 0;
}

}  // extern "C"
