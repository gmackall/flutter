// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:convert';

import 'package:android_driver_extensions/extension.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_driver/driver_extension.dart';

import '../src/allow_list_devices.dart';

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
    const ScrollableMainApp(
      platformView: _HybridCompositionAndroidPlatformView(viewType: 'box_platform_view'),
    ),
  );
}

final class _HybridCompositionAndroidPlatformView extends StatelessWidget {
  const _HybridCompositionAndroidPlatformView({required this.viewType});

  final String viewType;

  @override
  Widget build(BuildContext context) {
    return PlatformViewLink(
      viewType: viewType,
      surfaceFactory: (BuildContext context, PlatformViewController controller) {
        return AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        );
      },
      onCreatePlatformView: (PlatformViewCreationParams params) {
        return PlatformViewsService.initHybridAndroidView(
            id: params.id,
            viewType: viewType,
            layoutDirection: TextDirection.ltr,
            creationParamsCodec: const StandardMessageCodec(),
          )
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..create();
      },
    );
  }
}

class ScrollableMainApp extends StatefulWidget {
  const ScrollableMainApp({super.key, required this.platformView});

  final Widget platformView;

  @override
  State<ScrollableMainApp> createState() => _ScrollableMainAppState();
}

class _ScrollableMainAppState extends State<ScrollableMainApp> {
  bool showPlatformView = true;

  void _togglePlatformView() {
    setState(() {
      showPlatformView = !showPlatformView;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        appBar: AppBar(title: const Text('HCPP Scroll Test')),
        body: ListView(
          children: <Widget>[
            Container(height: 100, color: Colors.cyan, child: const Center(child: Text('Above'))),
            TextButton(
              key: const ValueKey<String>('AddOverlay'),
              onPressed: _togglePlatformView,
              child: const SizedBox(
                width: 190,
                height: 100,
                child: ColoredBox(color: Colors.green, child: Center(child: Text('Toggle (Add)'))),
              ),
            ),
            if (showPlatformView)
              SizedBox(height: 300, child: widget.platformView),
            TextButton(
              key: const ValueKey<String>('RemoveOverlay'),
              onPressed: _togglePlatformView,
              child: const SizedBox(
                 width: double.infinity,
                 height: 50,
                 child: ColoredBox(color: Colors.yellow, child: Center(child: Text('Toggle (Remove)'))),
              ),
            ),
            Container(
              height: 1000,
              color: Colors.purple,
              child: const Center(child: Text('Below (Scroll me)')),
            ),
          ],
        ),
      ),
    );
  }
}
