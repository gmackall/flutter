// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/// Single source of truth for Android build constants shared between the
/// Flutter tool (Dart) and the Flutter Gradle Plugin (Kotlin).
///
/// Run `dart run packages/flutter_tools/bin/generate_gradle_constants.dart`
/// to update the generated Kotlin constants in the Flutter Gradle Plugin.
abstract class AndroidBuildConstants {
  // Default Android SDK Versions
  static const int compileSdkVersion = 36;
  static const int minSdkVersion = 24;
  static const int targetSdkVersion = 36;
  static const String ndkVersion = '28.2.13676358';

  // Gradle Property Keys (-P)
  static const String propTargetPlatform = 'target-platform';
  static const String propTarget = 'target';
  static const String propLocalEngineRepo = 'local-engine-repo';
  static const String propLocalEngineBuildMode = 'local-engine-build-mode';
  static const String propShouldShrinkResources = 'shrink';
  static const String propSplitPerAbi = 'split-per-abi';
  static const String propVerbose = 'verbose';
  static const String propDisableAbiFiltering = 'disable-abi-filtering';

  // Target Platforms
  static const String platformArm32 = 'android-arm';
  static const String platformArm64 = 'android-arm64';
  static const String platformX86_64 = 'android-x64';

  // ABIs
  static const String archArm32 = 'armeabi-v7a';
  static const String archArm64 = 'arm64-v8a';
  static const String archX86_64 = 'x86_64';
}
