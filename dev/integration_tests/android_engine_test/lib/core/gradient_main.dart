// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:android_driver_extensions/extension.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_driver/driver_extension.dart';

import '../src/allow_list_devices.dart';
import '../src/platform_view_factory.dart';
import '../src/platform_view_mode.dart';
import '_shared.dart';

/// Renders a blue -> orange gradient platform view under the composition mode
/// selected by `--dart-define=PV_MODE`.
///
/// The mode-agnostic functionality (render, rotate, hide overlay) is asserted by
/// `test_driver/core/gradient_test.dart`, which is run under every supported
/// mode by `dev/bots/suite_runners/run_android_engine_tests.dart`.
void main() async {
  ensureAndroidDevice();
  enableFlutterDriverExtension(commands: <CommandExtension>[nativeDriverCommands]);

  // Run on full screen.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
  runApp(
    MainApp(
      platformView: platformViewForMode(
        PvMode.fromDartDefine(),
        content: PvContent.blueOrangeGradient,
      ),
    ),
  );
}
