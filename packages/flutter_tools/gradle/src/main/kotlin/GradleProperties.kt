// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
//
// DO NOT EDIT. Generated from
//   packages/flutter_tools/lib/src/android/gradle_properties.dart
// by dev/tools/gen_constants/bin/gen_constants.dart.
// Edit the Dart file and re-run the generator; a CI check enforces that doing
// so produces no diff.

package com.flutter.gradle

/**
 * The names of the Gradle properties the tool passes to the Flutter Gradle
 * Plugin as `-P<name>=<value>`.
 */
object GradleProperties {
    /** Whether the Flutter Gradle Plugin should log verbosely. */
    const val VERBOSE: String = "verbose"

    /** The path of the Dart entrypoint to compile. */
    const val TARGET: String = "target"

    /** A comma separated list of the `--target-platform` values to build for. */
    const val TARGET_PLATFORM: String = "target-platform"

    /** The path of the local Maven repository containing a local engine build. */
    const val LOCAL_ENGINE_REPO: String = "local-engine-repo"

    /** The build mode (`debug`, `profile`, `release`) of the local engine build. */
    const val LOCAL_ENGINE_BUILD_MODE: String = "local-engine-build-mode"

    /** Whether resource shrinking is enabled. */
    const val SHRINK: String = "shrink"

    /** Whether to generate a separate APK for each ABI. */
    const val SPLIT_PER_ABI: String = "split-per-abi"
}
