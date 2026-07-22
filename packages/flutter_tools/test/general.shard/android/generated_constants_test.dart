// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter_tools/src/android/android_build_constants.dart';
import 'package:flutter_tools/src/base/file_system.dart';
import 'package:flutter_tools/src/globals.dart' as globals;

import '../../../bin/generate_gradle_constants.dart';
import '../../src/common.dart';

void main() {
  test('GeneratedAndroidBuildConstants.kt is up to date with AndroidBuildConstants', () {
    final String generatedContent = generateKotlinBuildConstants();

    final File ktFile = globals.fs.file(
      globals.fs.path.join(
        'gradle',
        'src',
        'main',
        'kotlin',
        'com',
        'flutter',
        'gradle',
        'GeneratedAndroidBuildConstants.kt',
      ),
    );

    expect(
      ktFile.existsSync(),
      isTrue,
      reason:
          'GeneratedAndroidBuildConstants.kt does not exist. Run `dart run bin/generate_gradle_constants.dart`.',
    );

    expect(
      ktFile.readAsStringSync().replaceAll('\r\n', '\n'),
      equals(generatedContent.replaceAll('\r\n', '\n')),
      reason:
          'GeneratedAndroidBuildConstants.kt is out of date with AndroidBuildConstants. '
          'Run `dart run bin/generate_gradle_constants.dart` to update it.',
    );
  });

  test('AndroidBuildConstants values match expectations', () {
    expect(AndroidBuildConstants.compileSdkVersion, equals(36));
    expect(AndroidBuildConstants.minSdkVersion, equals(24));
    expect(AndroidBuildConstants.targetSdkVersion, equals(36));
    expect(AndroidBuildConstants.ndkVersion, equals('28.2.13676358'));
    expect(AndroidBuildConstants.propTargetPlatform, equals('target-platform'));
  });
}
