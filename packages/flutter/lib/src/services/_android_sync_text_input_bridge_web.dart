// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import '_android_sync_text_input_bridge.dart';

/// Creates the web-platform no-op [AndroidSyncTextInputBridge].
AndroidSyncTextInputBridge createPlatformAndroidSyncTextInputBridge() =>
    const _WebAndroidSyncTextInputBridge();

final class _WebAndroidSyncTextInputBridge implements AndroidSyncTextInputBridge {
  const _WebAndroidSyncTextInputBridge();

  @override
  int registerHandler(AndroidSyncImeRequestHandler handler) =>
      AndroidSyncImeSupportStatus.unsupported;

  @override
  void unregisterHandler() {}

  @override
  bool notifyEditingState({
    required int clientId,
    required int flags,
    required int selectionStart,
    required int selectionEnd,
    required int composingStart,
    required int composingEnd,
  }) => false;
}
