// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';

import 'binary_messenger.dart';

/// The default implementation of [BinaryMessenger].
///
/// This messenger sends messages from the app-side to the platform-side and
/// dispatches incoming messages from the platform-side to the appropriate
/// handler.
class DefaultBinaryMessenger extends BinaryMessenger {
  /// Creates the default [BinaryMessenger] singleton.
  const DefaultBinaryMessenger();

  @override
  Future<void> handlePlatformMessage(
    String channel,
    ByteData? message,
    ui.PlatformMessageResponseCallback? callback,
  ) async {
    ui.channelBuffers.push(channel, message, (ByteData? data) => callback?.call(data));
  }

  @override
  Future<ByteData?> send(String channel, ByteData? message) {
    final completer = Completer<ByteData?>();
    // ui.PlatformDispatcher.instance is accessed directly instead of using
    // ServicesBinding.instance.platformDispatcher because this method might be
    // invoked before any binding is initialized. This issue was reported in
    // #27541. It is not ideal to statically access
    // ui.PlatformDispatcher.instance because the PlatformDispatcher may be
    // dependency injected elsewhere with a different instance. However, static
    // access at this location seems to be the least bad option.
    // TODO(ianh): Use ServicesBinding.instance once we have better diagnostics
    // on that getter.
    ui.PlatformDispatcher.instance.sendPlatformMessage(channel, message, (ByteData? reply) {
      try {
        completer.complete(reply);
      } catch (exception, stack) {
        FlutterError.reportError(
          FlutterErrorDetails(
            exception: exception,
            stack: stack,
            library: 'services library',
            context: ErrorDescription('during a platform message response callback'),
          ),
        );
      }
    });
    return completer.future;
  }

  /// Sends a one-way binary message without allocating a response [Completer]
  /// or callback closure.
  void sendOneWay(String channel, ByteData? message) {
    ui.PlatformDispatcher.instance.sendPlatformMessage(channel, message, null);
  }

  @override
  void setMessageHandler(String channel, MessageHandler? handler) {
    if (handler == null) {
      ui.channelBuffers.clearListener(channel);
    } else {
      ui.channelBuffers.setListener(channel, (
        ByteData? data,
        ui.PlatformMessageResponseCallback callback,
      ) async {
        ByteData? response;
        try {
          response = await handler(data);
        } catch (exception, stack) {
          FlutterError.reportError(
            FlutterErrorDetails(
              exception: exception,
              stack: stack,
              library: 'services library',
              context: ErrorDescription('during a platform message callback'),
            ),
          );
        } finally {
          callback(response);
        }
      });
    }
  }
}
