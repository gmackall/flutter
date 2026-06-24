// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:convert';

import 'package:android_driver_extensions/native_driver.dart';
import 'package:android_driver_extensions/skia_gold.dart';
import 'package:flutter_driver/flutter_driver.dart';
import 'package:test/test.dart';

import '../_luci_skia_gold_prelude.dart';

/// Mode-agnostic "tap to change color" functionality (native hit testing), run
/// under each supported [PvMode]. Replaces the previously duplicated
/// `platform_view_tap_color_change` (TLHC) and `hcpp/tap_color_change` tests.
///
/// For local debugging:
///
/// ```sh
/// UPDATE_GOLDENS=1 flutter drive lib/core/tap_color_change_main.dart
/// flutter drive lib/core/tap_color_change_main.dart
/// ```
void main() async {
  const goldenPrefix = 'core_tap_color_change';

  late final FlutterDriver flutterDriver;
  late final NativeDriver nativeDriver;

  // HC screenshots are currently unstable on CI (always black); skip the golden
  // scenarios there. See https://github.com/flutter/flutter/issues/165032.
  final Object? skipScreenshots = currentMode == PvMode.hc
      ? 'HC screenshots are unstable on CI (https://github.com/flutter/flutter/issues/165032).'
      : false;

  setUpAll(() async {
    if (isLuci) {
      await enableSkiaGoldComparator(namePrefix: 'android_engine_test$goldenVariant');
    }
    flutterDriver = await FlutterDriver.connect();
    nativeDriver = await AndroidNativeDriver.connect(flutterDriver);
    await nativeDriver.configureForScreenshotTesting();
    await flutterDriver.waitUntilFirstFrameRasterized();
  });

  tearDownAll(() async {
    await nativeDriver.close();
    await flutterDriver.close();
  });

  test(
    'verify that HCPP is supported and enabled',
    () async {
      final response = json.decode(await flutterDriver.requestData('')) as Map<String, Object?>;
      expect(response['supported'], true);
    },
    timeout: Timeout.none,
    // Runtime backstop: only meaningful when we asked for HCPP.
    skip: currentMode == PvMode.hcpp ? false : 'Only relevant under HCPP.',
  );

  test('should screenshot a rectangle that becomes blue after a tap', () async {
    await expectLater(
      nativeDriver.screenshot(),
      matchesGoldenFile('$goldenPrefix.initial.png'),
    );

    await nativeDriver.tap(const ByNativeAccessibilityLabel('Change color'));
    await expectLater(
      nativeDriver.screenshot(),
      matchesGoldenFile('$goldenPrefix.tapped.png'),
    );
  }, timeout: Timeout.none, skip: skipScreenshots);
}
