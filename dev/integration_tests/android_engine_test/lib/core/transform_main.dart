// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:convert';
import 'dart:math' as math;

import 'package:android_driver_extensions/extension.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_driver/driver_extension.dart';

import '../src/allow_list_devices.dart';
import '../src/platform_view_factory.dart';
import '../src/platform_view_mode.dart';

/// Applies framework-side transforms (rotate/scale/flip/translate) to a platform
/// view, under the composition mode selected by `--dart-define=PV_MODE`. Run
/// under TLHC + HCPP (HC composites in the native hierarchy and can't transform
/// the view). Asserted by `test_driver/core/transform_test.dart`.
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

final class MainApp extends StatefulWidget {
  const MainApp({super.key});

  @override
  State<MainApp> createState() => _MainAppState();
}

class _MainAppState extends State<MainApp> {
  double angle = 0;
  double scale = 1.0;
  Offset translation = Offset.zero;
  bool flippedX = false;

  void _incrementAngle() {
    setState(() {
      angle += 0.5;
    });
  }

  void _incrementScale() {
    setState(() {
      scale += 0.1;
    });
  }

  void _decrementScale() {
    setState(() {
      scale -= 0.1;
    });
  }

  void _incrementTranslation() {
    setState(() {
      translation = translation.translate(10, 0);
    });
  }

  void _decrementTranslation() {
    setState(() {
      translation = translation.translate(-10, 0);
    });
  }

  void _toggleFlip() {
    setState(() {
      flippedX = !flippedX;
    });
  }

  @override
  Widget build(BuildContext context) {
    final transformMatrix = Matrix4.identity()
      ..translate(translation.dx, translation.dy)
      ..scale(scale)
      ..rotateZ(angle * math.pi);

    final Widget transformedView = Transform.flip(
      flipX: flippedX,
      child: Transform(
        transform: transformMatrix,
        alignment: Alignment.center,
        child: Stack(
          alignment: Alignment.center,
          children: <Widget>[
            const SizedBox(width: 300, height: 500, child: ColoredBox(color: Colors.green)),
            SizedBox(
              width: 200,
              height: 400,
              child: platformViewForMode(
                PvMode.fromDartDefine(),
                content: PvContent.blueOrangeGradient,
                hitTestBehavior: PlatformViewHitTestBehavior.transparent,
              ),
            ),
          ],
        ),
      ),
    );

    final Widget widget = Column(
      children: <Widget>[
        Wrap(
          alignment: WrapAlignment.center,
          children: <Widget>[
            TextButton(
              onPressed: _incrementAngle,
              key: const ValueKey<String>('Rotate'),
              child: const Text('Rotate'),
            ),
            TextButton(
              onPressed: _incrementScale,
              key: const ValueKey<String>('Scale Up'),
              child: const Text('Scale Up'),
            ),
            TextButton(
              onPressed: _decrementScale,
              key: const ValueKey<String>('Scale Down'),
              child: const Text('Scale Down'),
            ),
            TextButton(
              onPressed: _incrementTranslation,
              key: const ValueKey<String>('Translate Right'),
              child: const Text('Translate Right'),
            ),
            TextButton(
              onPressed: _decrementTranslation,
              key: const ValueKey<String>('Translate Left'),
              child: const Text('Translate Left'),
            ),
            TextButton(
              onPressed: _toggleFlip,
              key: const ValueKey<String>('Flip X'),
              child: const Text('Flip X'),
            ),
          ],
        ),
        transformedView,
      ],
    );

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(body: Center(child: widget)),
    );
  }
}
