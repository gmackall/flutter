// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:ffi';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import '_android_sync_text_input_bridge.dart';

typedef _SyncImeDispatchNative = Int32 Function(
  Pointer<Uint8> requestData,
  Int32 requestLength,
  Pointer<Void> responseContext,
);

typedef _IsSupportedNative = Int32 Function(Int64 engineId);
typedef _IsSupportedDart = int Function(int engineId);

typedef _RegisterHandlerNative = Int32 Function(
  Int64 engineId,
  Pointer<NativeFunction<_SyncImeDispatchNative>> callback,
);
typedef _RegisterHandlerDart = int Function(
  int engineId,
  Pointer<NativeFunction<_SyncImeDispatchNative>> callback,
);

typedef _ReserveResponseNative = Pointer<Uint8> Function(Pointer<Void> ctx, Int32 length);
typedef _ReserveResponseDart = Pointer<Uint8> Function(Pointer<Void> ctx, int length);

typedef _NotifyEditingStateNative = Int32 Function(
  Int64 engineId,
  Int32 clientId,
  Int32 flags,
  Int32 selectionStart,
  Int32 selectionEnd,
  Int32 composingStart,
  Int32 composingEnd,
);
typedef _NotifyEditingStateDart = int Function(
  int engineId,
  int clientId,
  int flags,
  int selectionStart,
  int selectionEnd,
  int composingStart,
  int composingEnd,
);

/// Creates the IO-platform [AndroidSyncTextInputBridge].
AndroidSyncTextInputBridge createPlatformAndroidSyncTextInputBridge() =>
    _FfiAndroidSyncTextInputBridge();

final class _FfiAndroidSyncTextInputBridge implements AndroidSyncTextInputBridge {
  bool _symbolsResolved = false;
  bool _symbolsAvailable = false;

  late _IsSupportedDart _isSupported;
  late _RegisterHandlerDart _registerHandler;
  late _ReserveResponseDart _reserveResponse;
  late _NotifyEditingStateDart _notifyEditingState;

  NativeCallable<_SyncImeDispatchNative>? _callable;
  AndroidSyncImeRequestHandler? _handler;
  int? _registeredEngineId;

  bool _ensureSymbols() {
    if (_symbolsResolved) {
      return _symbolsAvailable;
    }
    _symbolsResolved = true;
    if (!Platform.isAndroid) {
      return false;
    }
    try {
      final process = DynamicLibrary.process();
      _isSupported = process.lookupFunction<_IsSupportedNative, _IsSupportedDart>(
        'InternalFlutterAndroidSyncImeIsSupported',
      );
      _registerHandler = process.lookupFunction<_RegisterHandlerNative, _RegisterHandlerDart>(
        'InternalFlutterAndroidSyncImeRegisterHandler',
      );
      _reserveResponse = process.lookupFunction<_ReserveResponseNative, _ReserveResponseDart>(
        'InternalFlutterAndroidSyncImeReserveResponse',
      );
      _notifyEditingState = process
          .lookupFunction<_NotifyEditingStateNative, _NotifyEditingStateDart>(
            'InternalFlutterAndroidSyncImeNotifyEditingState',
          );
      _symbolsAvailable = true;
    } on ArgumentError {
      _symbolsAvailable = false;
    } on UnsupportedError {
      _symbolsAvailable = false;
    }
    return _symbolsAvailable;
  }

  int _onNativeRequest(
    Pointer<Uint8> requestData,
    int requestLength,
    Pointer<Void> responseContext,
  ) {
    final AndroidSyncImeRequestHandler? handler = _handler;
    if (handler == null ||
        !_symbolsAvailable ||
        requestLength < 0 ||
        (requestLength > 0 && requestData == nullptr)) {
      return 0;
    }
    final Uint8List requestView = requestLength == 0
        ? Uint8List(0)
        : requestData.asTypedList(requestLength);
    final Uint8List? responseBytes = handler(requestView);
    if (responseBytes == null) {
      return 0;
    }
    final Pointer<Uint8> outPtr = _reserveResponse(responseContext, responseBytes.length);
    if (outPtr == nullptr) {
      return 0;
    }
    if (responseBytes.isNotEmpty) {
      outPtr.asTypedList(responseBytes.length).setAll(0, responseBytes);
    }
    return 1;
  }

  @override
  int registerHandler(AndroidSyncImeRequestHandler handler) {
    if (!_ensureSymbols()) {
      return AndroidSyncImeSupportStatus.unsupported;
    }
    final int? engineId = ui.PlatformDispatcher.instance.engineId;
    if (engineId == null) {
      return AndroidSyncImeSupportStatus.unsupported;
    }

    final int support = _isSupported(engineId);
    if (support != AndroidSyncImeSupportStatus.supported) {
      return support;
    }

    _handler = handler;
    final NativeCallable<_SyncImeDispatchNative> callable =
        _callable ??
        NativeCallable<_SyncImeDispatchNative>.isolateLocal(
          _onNativeRequest,
          exceptionalReturn: -1,
        );
    final int status = _registerHandler(engineId, callable.nativeFunction);
    if (status == AndroidSyncImeSupportStatus.supported) {
      _callable = callable;
      _registeredEngineId = engineId;
    } else {
      if (_callable == null) {
        callable.close();
      }
      _handler = null;
    }
    return status;
  }

  @override
  void unregisterHandler() {
    final int? engineId = _registeredEngineId;
    if (engineId != null && _symbolsAvailable) {
      _registerHandler(engineId, nullptr);
    }
    _registeredEngineId = null;
    _handler = null;
    _callable?.close();
    _callable = null;
  }

  @override
  bool notifyEditingState({
    required int clientId,
    required int flags,
    required int selectionStart,
    required int selectionEnd,
    required int composingStart,
    required int composingEnd,
  }) {
    final int? engineId = _registeredEngineId;
    if (engineId == null || !_symbolsAvailable) {
      return false;
    }
    return _notifyEditingState(
          engineId,
          clientId,
          flags,
          selectionStart,
          selectionEnd,
          composingStart,
          composingEnd,
        ) !=
        0;
  }
}
