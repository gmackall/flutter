// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:convert';

import 'package:android_driver_extensions/native_driver.dart';
import 'package:android_driver_extensions/skia_gold.dart';
import 'package:flutter_driver/flutter_driver.dart';
import 'package:test/test.dart';

import '../_luci_skia_gold_prelude.dart';

/// Hiding one of two platform views clears its texture, run under TLHC + HCPP.
///
/// For local debugging:
///
/// ```sh
/// UPDATE_GOLDENS=1 flutter drive lib/core/clear_hidden_main.dart
/// flutter drive lib/core/clear_hidden_main.dart
/// ```
void main() async {
  const goldenPrefix = 'core_clear_hidden';

  late final FlutterDriver flutterDriver;
  late final NativeDriver nativeDriver;

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
    skip: currentMode == PvMode.hcpp ? false : 'Only relevant under HCPP.',
  );

  test('should start with texture, and toggle to no texture', () async {
    await expectLater(nativeDriver.screenshot(), matchesGoldenFile('$goldenPrefix.two_boxes.png'));
    await flutterDriver.tap(find.byValueKey('ToggleRightView'));
    await expectLater(
      nativeDriver.screenshot(),
      matchesGoldenFile('$goldenPrefix.only_one_box.png'),
    );
  }, timeout: Timeout.none);
}
