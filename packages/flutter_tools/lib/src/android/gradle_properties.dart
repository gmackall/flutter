// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// This file is the source of truth for these property names. The Kotlin copy
// the Flutter Gradle Plugin reads is generated from it by:
//
//   dart --enable-asserts dev/tools/gen_constants/bin/gen_constants.dart
//
// Add or change a constant here and re-run that script; a CI check fails if
// regenerating produces a diff. Doc comments are copied to the generated Kotlin,
// so keep them about the constants themselves. Only String, int, and bool are
// supported; the generator fails on anything else rather than skipping it.

/// The names of the Gradle properties the tool passes to the Flutter Gradle
/// Plugin as `-P<name>=<value>`.
class GradleProperties {
  /// Whether the Flutter Gradle Plugin should log verbosely.
  static const String verbose = 'verbose';

  /// The path of the Dart entrypoint to compile.
  static const String target = 'target';

  /// A comma separated list of the `--target-platform` values to build for.
  static const String targetPlatform = 'target-platform';

  /// The path of the local Maven repository containing a local engine build.
  static const String localEngineRepo = 'local-engine-repo';

  /// The build mode (`debug`, `profile`, `release`) of the local engine build.
  static const String localEngineBuildMode = 'local-engine-build-mode';

  /// Whether resource shrinking is enabled.
  static const String shrink = 'shrink';

  /// Whether to generate a separate APK for each ABI.
  static const String splitPerAbi = 'split-per-abi';
}
