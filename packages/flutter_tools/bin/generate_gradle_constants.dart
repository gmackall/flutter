// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:io';

import 'package:flutter_tools/src/android/android_build_constants.dart';

/// Generates Kotlin constants for the Flutter Gradle Plugin from Dart SSOT.
String generateKotlinBuildConstants() {
  return '''
// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// GENERATED CODE - DO NOT MODIFY BY HAND.
// Generated from packages/flutter_tools/lib/src/android/android_build_constants.dart
// Run `dart run bin/generate_gradle_constants.dart` to update.

package com.flutter.gradle

object GeneratedAndroidBuildConstants {
    const val COMPILE_SDK_VERSION: Int = ${AndroidBuildConstants.compileSdkVersion}
    const val MIN_SDK_VERSION: Int = ${AndroidBuildConstants.minSdkVersion}
    const val TARGET_SDK_VERSION: Int = ${AndroidBuildConstants.targetSdkVersion}
    const val NDK_VERSION: String = "${AndroidBuildConstants.ndkVersion}"

    const val PROP_TARGET_PLATFORM: String = "${AndroidBuildConstants.propTargetPlatform}"
    const val PROP_TARGET: String = "${AndroidBuildConstants.propTarget}"
    const val PROP_LOCAL_ENGINE_REPO: String = "${AndroidBuildConstants.propLocalEngineRepo}"
    const val PROP_LOCAL_ENGINE_BUILD_MODE: String = "${AndroidBuildConstants.propLocalEngineBuildMode}"
    const val PROP_SHOULD_SHRINK_RESOURCES: String = "${AndroidBuildConstants.propShouldShrinkResources}"
    const val PROP_SPLIT_PER_ABI: String = "${AndroidBuildConstants.propSplitPerAbi}"
    const val PROP_IS_VERBOSE: String = "${AndroidBuildConstants.propVerbose}"
    const val PROP_DISABLE_ABI_FILTERING: String = "${AndroidBuildConstants.propDisableAbiFiltering}"

    const val PLATFORM_ARM32: String = "${AndroidBuildConstants.platformArm32}"
    const val PLATFORM_ARM64: String = "${AndroidBuildConstants.platformArm64}"
    const val PLATFORM_X86_64: String = "${AndroidBuildConstants.platformX86_64}"

    const val ARCH_ARM32: String = "${AndroidBuildConstants.archArm32}"
    const val ARCH_ARM64: String = "${AndroidBuildConstants.archArm64}"
    const val ARCH_X86_64: String = "${AndroidBuildConstants.archX86_64}"
}
''';
}

void main() {
  final String content = generateKotlinBuildConstants();
  final targetFile = File(
    'gradle/src/main/kotlin/com/flutter/gradle/GeneratedAndroidBuildConstants.kt',
  );
  if (!targetFile.parent.existsSync()) {
    targetFile.parent.createSync(recursive: true);
  }
  targetFile.writeAsStringSync(content);
  print('Successfully generated ${targetFile.path}');
}
