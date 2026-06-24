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

/// Hides and shows a platform view repeatedly, under the composition mode
/// selected by `--dart-define=PV_MODE`. Asserted by
/// `test_driver/core/hide_show_hide_test.dart`.
void main() async {
  ensureAndroidDevice();
  enableFlutterDriverExtension(commands: <CommandExtension>[nativeDriverCommands]);

  // Run on full screen.
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
  runApp(
    MainApp(
      platformView: platformViewForMode(
        PvMode.fromDartDefine(),
        content: PvContent.changingColorButton,
      ),
    ),
  );
}

final class MainApp extends StatefulWidget {
  const MainApp({super.key, required this.platformView});

  final Widget platformView;

  @override
  State<MainApp> createState() => _MainAppState();
}

class _MainAppState extends State<MainApp> {
  bool _show = true;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: Column(
          children: <Widget>[
            Expanded(
              child: Visibility(
                visible: _show,
                maintainState: true,
                maintainSize: true,
                maintainAnimation: true,
                child: KeyedSubtree(
                  key: const ValueKey<String>('PlatformView'),
                  child: widget.platformView,
                ),
              ),
            ),
            ElevatedButton(
              key: const ValueKey<String>('TogglePlatformView'),
              onPressed: () {
                setState(() {
                  _show = !_show;
                });
              },
              child: Text(
                key: const ValueKey<String>('ToggleButtonText'),
                _show ? 'Hide Platform View' : 'Show Platform View',
              ),
            ),
          ],
        ),
      ),
    );
  }
}
