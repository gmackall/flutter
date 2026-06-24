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

/// A platform view with a toggled overlay texture layer, under the composition
/// mode selected by `--dart-define=PV_MODE`. Run under TLHC + HCPP. Asserted by
/// `test_driver/core/overlay_layer_cleared_test.dart`.
void main() {
  ensureAndroidDevice();
  enableFlutterDriverExtension(
    handler: (String? command) async {
      return json.encode(<String, Object?>{
        'supported': await HybridAndroidViewController.checkIfSupported(),
      });
    },
    commands: <CommandExtension>[nativeDriverCommands],
  );

  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  bool _showTexture = true;

  void _toggleTexture() {
    setState(() {
      _showTexture = !_showTexture;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('HCPP Platform View Bug Demo')),
        body: Column(
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: ElevatedButton(
                key: const ValueKey<String>('ToggleTexture'),
                onPressed: _toggleTexture,
                child: Text(_showTexture ? 'Hide Texture' : 'Show Texture'),
              ),
            ),
            Expanded(
              child: Center(
                child: Stack(
                  children: <Widget>[
                    Center(
                      child: SizedBox.square(
                        dimension: 300,
                        child: platformViewForMode(
                          PvMode.fromDartDefine(),
                          content: PvContent.changingColorButton,
                          hitTestBehavior: PlatformViewHitTestBehavior.transparent,
                        ),
                      ),
                    ),

                    if (_showTexture)
                      const Center(
                        child: SizedBox.square(
                          dimension: 275,
                          child: Opacity(
                            opacity: 0.5,
                            child: Texture(
                              // Intentionally use an unknown texture ID: this
                              // results a black rectangle which is good enough
                              // for our purposes.
                              textureId: 1,
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
