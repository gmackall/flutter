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

/// Multiple platform views interleaved with Flutter overlays, under the
/// composition mode selected by `--dart-define=PV_MODE`. Run under TLHC + HCPP.
/// Asserted by `test_driver/core/overlapping_test.dart`.
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
  runApp(const MainApp());
}

// This should appear as the yellow line over a blue box. The
// green box should not be visible unless the platform view has not loaded yet.
final class MainApp extends StatefulWidget {
  const MainApp({super.key});

  @override
  State<MainApp> createState() => _MainAppState();
}

class _MainAppState extends State<MainApp> {
  Widget _boxPlatformView() {
    return SizedBox.square(
      dimension: 200,
      child: platformViewForMode(PvMode.fromDartDefine(), content: PvContent.box),
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Stack(
        children: <Widget>[
          Positioned.directional(
            top: 100,
            textDirection: TextDirection.ltr,
            child: _boxPlatformView(),
          ),
          Positioned.directional(
            top: 200,
            textDirection: TextDirection.ltr,
            child: const SizedBox(width: 800, height: 200, child: ColoredBox(color: Colors.yellow)),
          ),
          Positioned.directional(
            top: 300,
            textDirection: TextDirection.ltr,
            child: _boxPlatformView(),
          ),
          Positioned.directional(
            top: 400,
            textDirection: TextDirection.ltr,
            child: const SizedBox(width: 800, height: 200, child: ColoredBox(color: Colors.red)),
          ),
          Positioned.directional(
            top: 500,
            textDirection: TextDirection.ltr,
            child: _boxPlatformView(),
          ),
          Positioned.directional(
            top: 600,
            textDirection: TextDirection.ltr,
            child: const SizedBox(width: 800, height: 200, child: ColoredBox(color: Colors.orange)),
          ),
        ],
      ),
    );
  }
}
