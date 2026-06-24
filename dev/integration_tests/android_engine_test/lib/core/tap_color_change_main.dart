// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:convert';

import 'package:android_driver_extensions/extension.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_driver/driver_extension.dart';

import '../src/allow_list_devices.dart';
import '../src/platform_view_factory.dart';
import '../src/platform_view_mode.dart';

/// A platform view rectangle that turns blue when tapped natively, under the
/// composition mode selected by `--dart-define=PV_MODE`. Asserted by
/// `test_driver/core/tap_color_change_test.dart`.
///
/// The driver extension reports whether HCPP is supported so the driver can use
/// it as a runtime backstop when running under [PvMode.hcpp].
void main() async {
  ensureAndroidDevice();
  enableFlutterDriverExtension(
    handler: (String? command) async {
      return json.encode(<String, Object?>{
        'supported': await HybridAndroidViewController.checkIfSupported(),
      });
    },
    commands: <CommandExtension>[nativeDriverCommands],
  );

  // Run on full screen.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
  runApp(
    MaterialApp(
      debugShowCheckedModeBanner: false,
      home: platformViewForMode(
        PvMode.fromDartDefine(),
        content: PvContent.changingColorButton,
      ),
    ),
  );
}
