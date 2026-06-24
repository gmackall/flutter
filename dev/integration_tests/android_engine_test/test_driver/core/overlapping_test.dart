// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:convert';

import 'package:android_driver_extensions/native_driver.dart';
import 'package:android_driver_extensions/skia_gold.dart';
import 'package:flutter_driver/flutter_driver.dart';
import 'package:test/test.dart';

import '../_luci_skia_gold_prelude.dart';

/// Multiple platform views interleaved with Flutter overlays, run under
/// TLHC + HCPP.
///
/// For local debugging:
///
/// ```sh
/// UPDATE_GOLDENS=1 flutter drive lib/core/overlapping_main.dart
/// flutter drive lib/core/overlapping_main.dart
/// ```
void main() async {
  const goldenPrefix = 'core_overlapping';

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

  test('should screenshot multiple platform views with overlays', () async {
    await expectLater(
      nativeDriver.screenshot(),
      matchesGoldenFile('$goldenPrefix.multiple_overlays.png'),
    );
  }, timeout: Timeout.none);
}
