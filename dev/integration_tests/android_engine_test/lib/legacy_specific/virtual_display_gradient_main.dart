// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:android_driver_extensions/extension.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_driver/driver_extension.dart';

import '../core/_shared.dart';
import '../src/allow_list_devices.dart';
import '../src/platform_view_factory.dart';
import '../src/platform_view_mode.dart';

/// Virtual Display gradient smoke test.
///
/// [PvMode.vd] cannot be force-selected (the engine only falls back to Virtual
/// Display when TLHC is unsupported for a view), so this is a best-effort legacy
/// smoke test rather than a guaranteed-VD test. It uses the SurfaceView-backed
/// gradient view, which is the closest approximation. Asserted by
/// `test_driver/legacy_specific/virtual_display_gradient_main_test.dart`.
///
/// See https://github.com/flutter/flutter/blob/main/docs/platforms/android/Android-Platform-Views.md.
void main() async {
  ensureAndroidDevice();
  enableFlutterDriverExtension(commands: <CommandExtension>[nativeDriverCommands]);

  // Run on full screen.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
  runApp(
    MainApp(
      platformView: platformViewForMode(PvMode.vd, content: PvContent.blueOrangeGradient),
    ),
  );
}
