// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:convert';

import 'package:android_driver_extensions/extension.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_driver/driver_extension.dart';

import '../src/allow_list_devices.dart';
import '../src/platform_view_factory.dart';
import '../src/platform_view_mode.dart';

/// Applies framework-side opacity to a platform view, under the composition mode
/// selected by `--dart-define=PV_MODE`. Run under TLHC + HCPP. Asserted by
/// `test_driver/core/opacity_test.dart`.
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
  runApp(const _OpacityWrappedMainApp());
}

final class _OpacityWrappedMainApp extends StatefulWidget {
  const _OpacityWrappedMainApp();

  @override
  State<_OpacityWrappedMainApp> createState() {
    return _OpacityWrappedMainAppState();
  }
}

class _OpacityWrappedMainAppState extends State<_OpacityWrappedMainApp> {
  double opacity = 0.3;

  void _toggleOpacity() {
    setState(() {
      if (opacity == 1) {
        opacity = 0.3;
      } else {
        opacity = 1;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Opacity(
        opacity: opacity,
        child: ColoredBox(
          color: Colors.white,
          child: Stack(
            alignment: Alignment.center,
            children: <Widget>[
              TextButton(
                key: const ValueKey<String>('ToggleOpacity'),
                onPressed: _toggleOpacity,
                child: const SizedBox.square(
                  dimension: 300,
                  child: ColoredBox(color: Colors.green),
                ),
              ),
              SizedBox.square(
                dimension: 200,
                child: platformViewForMode(
                  PvMode.fromDartDefine(),
                  content: PvContent.changingColorButton,
                  hitTestBehavior: PlatformViewHitTestBehavior.transparent,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
