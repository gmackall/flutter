// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// This test exercises dynamic libraries added to a flutter app or package.
// It covers:
//  * `flutter run`, including hot reload and hot restart
//  * `flutter test`
//  * `flutter build`

@Timeout(Duration(minutes: 10))
library;

import 'dart:io';

import 'package:code_assets/code_assets.dart';
import 'package:file/file.dart';
import 'package:file_testing/file_testing.dart';
import 'package:flutter_tools/src/base/logger.dart';
import 'package:flutter_tools/src/base/os.dart';

import '../../src/common.dart';
import '../test_utils.dart' show fileSystem, flutterBin, platform;
import '../transition_test_utils.dart';
import 'native_assets_test_utils.dart';

final String hostOs = platform.operatingSystem;

final buildSubcommands = <String>[hostOs, if (hostOs == 'macos') 'ios', 'apk'];

/// The build modes to target for each flutter command that supports passing
/// a build mode.
///
/// The flow of compiling kernel as well as bundling dylibs can differ based on
/// build mode, so we should cover this.
const buildModes = <String>['debug', 'profile', 'release'];

void main() {
  if (!platform.isMacOS && !platform.isLinux && !platform.isWindows) {
    // TODO(dacoharkes): Implement Fuchsia. https://github.com/flutter/flutter/issues/129757
    return;
  }

  for (final String buildSubcommand in buildSubcommands) {
    for (final String buildMode in buildModes) {
      testWithoutContext(
        'flutter build $buildSubcommand with native assets $buildMode',
        () async {
          await inTempDir((Directory tempDirectory) async {
            final Directory packageDirectory = await createTestProject(packageName, tempDirectory);
            final Directory exampleDirectory = packageDirectory.childDirectory('example');

            final ProcessResult result = processManager.runSync(<String>[
              flutterBin,
              'build',
              buildSubcommand,
              '--$buildMode',
              if (buildSubcommand == 'ios') '--no-codesign',
            ], workingDirectory: exampleDirectory.path);
            if (result.exitCode != 0) {
              throw Exception(
                'flutter build failed: ${result.exitCode}\n${result.stderr}\n${result.stdout}',
              );
            }

            switch (buildSubcommand) {
              case 'macos':
                expectDylibIsBundledMacOS(exampleDirectory, buildMode);
                _expectDylibIsCodeSignedMacOS(exampleDirectory, buildMode);
              case 'ios':
                _expectDylibIsBundledIos(exampleDirectory, buildMode);
              case 'linux':
                expectDylibIsBundledLinux(exampleDirectory, buildMode);
              case 'windows':
                expectDylibIsBundledWindows(exampleDirectory, buildMode);
              case 'apk':
                _expectDylibIsBundledAndroid(exampleDirectory, buildMode);
            }
            expectCCompilerIsConfigured(exampleDirectory);
          });
        },
        tags: <String>['flutter-build-apk'],
      );
    }
  }

  // Regression test for https://github.com/flutter/flutter/issues/186810.
  //
  // With native assets, `libapp.so` was dropped from the app bundle's merged
  // native libraries: it reached the Flutter Gradle Plugin's `jniLibs`
  // intermediate directory but never made it into AGP's
  // `mergeReleaseNativeLibs`. As a result AGP never stripped it nor emitted its
  // debug symbols, so the bundle was missing both `base/lib/<abi>/libapp.so` and
  // the `BUNDLE-METADATA/.../libapp.so.sym` that `flutter build appbundle`
  // verifies, failing the build with a misleading "failed to strip debug
  // symbols from native libraries" error.
  //
  // This exercises the appbundle + native-assets cell of the coverage matrix:
  // `dev/devicelab/bin/tasks/gradle_plugin_bundle_test.dart` covers appbundle
  // *without* native assets, and the tests above cover native assets via APK
  // only.
  testWithoutContext(
    'flutter build appbundle bundles libapp.so and its debug symbols with native assets',
    () async {
      await inTempDir((Directory tempDirectory) async {
        final Directory packageDirectory = await createTestProject(packageName, tempDirectory);
        final Directory exampleDirectory = packageDirectory.childDirectory('example');

        final ProcessResult result = processManager.runSync(<String>[
          flutterBin,
          'build',
          'appbundle',
          '--release',
        ], workingDirectory: exampleDirectory.path);
        expect(
          result.exitCode,
          0,
          reason: 'flutter build appbundle --release failed:\n${result.stdout}\n${result.stderr}',
        );

        final File appBundle = exampleDirectory
            .childDirectory('build')
            .childDirectory('app')
            .childDirectory('outputs')
            .childDirectory('bundle')
            .childDirectory('release')
            .childFile('app-release.aab');
        expect(appBundle, exists);

        final osUtils = OperatingSystemUtils(
          fileSystem: fileSystem,
          logger: BufferLogger.test(),
          platform: platform,
          processManager: processManager,
        );
        final Directory unzipped = exampleDirectory.childDirectory('aab-unzipped')..createSync();
        osUtils.unzip(appBundle, unzipped);

        final Directory baseLib = unzipped.childDirectory('base').childDirectory('lib');
        final Directory debugSymbols = unzipped
            .childDirectory('BUNDLE-METADATA')
            .childDirectory('com.android.tools.build.debugsymbols');

        for (final arch in <String>['arm64-v8a', 'armeabi-v7a', 'x86_64']) {
          final Directory archLib = baseLib.childDirectory(arch);
          // The Dart AOT snapshot, the engine, and the native asset must all be
          // packaged side by side.
          expect(archLib.childFile('libapp.so'), exists);
          expect(archLib.childFile('libflutter.so'), exists);
          expect(archLib.childFile(OS.android.dylibFileName(packageName)), exists);
          // AGP must strip libapp.so and extract its debug symbols. A missing
          // libapp.so.sym is what surfaces as "failed to strip debug symbols".
          expect(debugSymbols.childDirectory(arch).childFile('libapp.so.sym'), exists);
          expect(debugSymbols.childDirectory(arch).childFile('libflutter.so.sym'), exists);
        }
      });
    },
    tags: <String>['flutter-build-apk'],
  );
}

void _expectDylibIsCodeSignedMacOS(Directory appDirectory, String buildMode) {
  final Directory appBundle = appDirectory.childDirectory(
    'build/$hostOs/Build/Products/${buildMode.upperCaseFirst()}/$exampleAppName.app',
  );
  final Directory frameworksFolder = appBundle.childDirectory('Contents/Frameworks');
  expect(frameworksFolder, exists);
  const String frameworkName = packageName;
  final Directory frameworkDir = frameworksFolder.childDirectory('$frameworkName.framework');
  final ProcessResult codesign = processManager.runSync(<String>[
    'codesign',
    '-dv',
    frameworkDir.absolute.path,
  ]);
  expect(codesign.exitCode, 0);

  // Expect adhoc signature, but not linker-signed (which would mean no code-signing happened after linking).
  final List<String> lines = codesign.stderr.toString().split('\n');
  final bool isLinkerSigned = lines.any((String line) => line.contains('linker-signed'));
  final bool isAdhoc = lines.any((String line) => line.contains('Signature=adhoc'));
  expect(isAdhoc, isTrue);
  expect(isLinkerSigned, isFalse);
}

void _expectDylibIsBundledIos(Directory appDirectory, String buildMode) {
  final Directory productsDirectory = appDirectory.childDirectory(
    'build/ios/${buildMode.upperCaseFirst()}-iphoneos/',
  );
  final Directory appBundle = productsDirectory.childDirectory('Runner.app');
  expect(appBundle, exists);
  final Directory frameworksFolder = appBundle.childDirectory('Frameworks');
  expect(frameworksFolder, exists);
  const String frameworkName = packageName;
  final File dylib = frameworksFolder
      .childDirectory('$frameworkName.framework')
      .childFile(frameworkName);
  expect(dylib, exists);
  final stripped = buildMode != 'debug';
  expectDylibIsStripped(dylib, stripped: stripped);
  if (stripped) {
    final Directory dsymDir = productsDirectory.childDirectory('$frameworkName.framework.dsym');
    expect(dsymDir, exists);
  }
  final String infoPlist = frameworksFolder
      .childDirectory('$frameworkName.framework')
      .childFile('Info.plist')
      .readAsStringSync();
  expect(infoPlist, '''
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleExecutable</key>
	<string>package_with_native_assets</string>
	<key>CFBundleIdentifier</key>
	<string>io.flutter.flutter.native-assets.package-with-native-assets</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>package_with_native_assets</string>
	<key>CFBundlePackageType</key>
	<string>FMWK</string>
	<key>CFBundleShortVersionString</key>
	<string>1.0</string>
	<key>CFBundleSignature</key>
	<string>????</string>
	<key>CFBundleVersion</key>
	<string>1.0</string>
	<key>MinimumOSVersion</key>
	<string>13.0</string>
</dict>
</plist>''');
}

void _expectDylibIsBundledAndroid(Directory appDirectory, String buildMode) {
  final File apk = appDirectory
      .childDirectory('build')
      .childDirectory('app')
      .childDirectory('outputs')
      .childDirectory('flutter-apk')
      .childFile('app-$buildMode.apk');
  expect(apk, exists);
  final osUtils = OperatingSystemUtils(
    fileSystem: fileSystem,
    logger: BufferLogger.test(),
    platform: platform,
    processManager: processManager,
  );
  final Directory apkUnzipped = appDirectory.childDirectory('apk-unzipped');
  apkUnzipped.createSync();
  osUtils.unzip(apk, apkUnzipped);
  final Directory lib = apkUnzipped.childDirectory('lib');
  for (final arch in <String>['arm64-v8a', 'armeabi-v7a', 'x86_64']) {
    final Directory archDir = lib.childDirectory(arch);
    expect(archDir, exists);
    // The dylibs should be next to the flutter and app so.
    expect(archDir.childFile('libflutter.so'), exists);
    if (buildMode != 'debug') {
      expect(archDir.childFile('libapp.so'), exists);
    }
    final File dylib = archDir.childFile(OS.android.dylibFileName(packageName));
    expect(dylib, exists);
  }
}
