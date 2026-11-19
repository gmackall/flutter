// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:typed_data';

import '_android_sync_text_input_bridge_io.dart'
    if (dart.library.js_interop) '_android_sync_text_input_bridge_web.dart';

/// Result codes matching `SyncImeSupportStatus` in
/// `android_sync_text_input_bridge.h`.
abstract final class AndroidSyncImeSupportStatus {
  /// Feature is disabled, unsupported on this platform, or engine ID unknown.
  static const int unsupported = 0;

  /// Supported / registered on the merged platform/UI thread.
  static const int supported = 1;

  /// Temporarily unavailable (for example, `mergeAfterLaunch` has not yet
  /// finished merging the UI and platform threads). Callers may retry later.
  static const int retryLater = 2;
}

/// Callback invoked synchronously by the Android embedder with a binary IME
/// request frame. Returns the encoded response bytes, or `null` if unhandled.
typedef AndroidSyncImeRequestHandler = Uint8List? Function(Uint8List requestBytes);

/// Internal abstraction over the Android C/JNI synchronous text input FFI
/// bridge so Dart tests can inject a deterministic fake without `libflutter.so`.
abstract class AndroidSyncTextInputBridge {
  /// Registers [handler] for the current engine's root isolate and returns an
  /// [AndroidSyncImeSupportStatus] code.
  int registerHandler(AndroidSyncImeRequestHandler handler);

  /// Unregisters the previously registered handler, if any.
  void unregisterHandler();

  /// Synchronously notifies Android's `TextInputPlugin` of a programmatic
  /// editing state change for [clientId].
  ///
  /// Returns `true` iff the Android side accepted and handled the notification
  /// for the active client.
  bool notifyEditingState({
    required int clientId,
    required int flags,
    required int selectionStart,
    required int selectionEnd,
    required int composingStart,
    required int composingEnd,
  });
}

/// Creates the platform-specific [AndroidSyncTextInputBridge].
AndroidSyncTextInputBridge createAndroidSyncTextInputBridge() =>
    createPlatformAndroidSyncTextInputBridge();
