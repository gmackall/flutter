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

/// RTL hit-test mirroring repro (https://github.com/flutter/flutter/issues/182823),
/// under the composition mode selected by `--dart-define=PV_MODE`. Run under
/// TLHC + HCPP. Asserted by `test_driver/core/rtl_mirror_test.dart`.
bool redTapped = false;
bool blueTapped = false;

void main() async {
  ensureAndroidDevice();
  enableFlutterDriverExtension(
    handler: (String? command) async {
      if (command == 'red_tapped') {
        return redTapped.toString();
      }
      if (command == 'blue_tapped') {
        return blueTapped.toString();
      }
      return json.encode(<String, Object?>{
        'supported': await HybridAndroidViewController.checkIfSupported(),
        'devicePixelRatio': WidgetsBinding.instance.platformDispatcher.views.first.devicePixelRatio,
      });
    },
    commands: <CommandExtension>[nativeDriverCommands],
  );

  // Run on full screen.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
  runApp(const MainApp());
}

class MainApp extends StatelessWidget {
  const MainApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: Directionality(textDirection: TextDirection.rtl, child: RTLMirrorRepro()),
      ),
    );
  }
}

class RTLMirrorRepro extends StatefulWidget {
  const RTLMirrorRepro({super.key});

  @override
  State<RTLMirrorRepro> createState() => _RTLMirrorReproState();
}

class _RTLMirrorReproState extends State<RTLMirrorRepro> {
  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceAround,
      children: [
        // Blue Box (Flutter)
        // In RTL, Row adds children from Right to Left.
        // So this first child will be on the RIGHT.
        InkWell(
          key: const ValueKey('blue_box'),
          onTap: () {
            setState(() {
              blueTapped = true;
            });
          },
          child: Container(
            width: 150,
            height: 150,
            color: Colors.blue,
            child: const Center(
              child: Text('Blue Box', style: TextStyle(color: Colors.white)),
            ),
          ),
        ),
        // Red Box (Platform View 2)
        // This second child will be on the LEFT.
        SizedBox(
          width: 150,
          height: 150,
          child: Stack(
            children: [
              Positioned.fill(
                child: platformViewForMode(
                  PvMode.fromDartDefine(),
                  content: PvContent.blueOrangeGradient,
                ),
              ),
              Center(
                child: SizedBox(
                  width: 125,
                  height: 125,
                  child: InkWell(
                    key: const ValueKey('red_box_overlay'),
                    onTap: () {
                      setState(() {
                        redTapped = true;
                      });
                    },
                    child: Container(color: Colors.red),
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
