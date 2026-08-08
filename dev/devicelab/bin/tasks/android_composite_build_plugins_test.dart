// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:convert';
import 'dart:io';

import 'package:flutter_devicelab/framework/framework.dart';
import 'package:flutter_devicelab/framework/task_result.dart';
import 'package:flutter_devicelab/framework/utils.dart';
import 'package:path/path.dart' as path;

/// Exercises building Flutter plugins as isolated AARs.
///
/// The fixture under dev/integration_tests/composite_flutter_plugin_test contains:
///   * host_app                  - an app depending on all three plugins below.
///   * sample_plugin             - opts in to AAR builds.
///   * sample_consuming_plugin   - opts in, and depends on sample_plugin
///                                 (validates plugin -> plugin across AAR boundaries).
///   * unmigrated_sample_plugin  - has not opted in, so it is built from source
///                                 (validates that both models coexist in one app).
///
/// The plugins are consumed from the fixture by relative `path:` dependencies, which normally
/// forces them to be built from source — a path dependency means the developer may be editing the
/// plugin. `--plugin-aar` opts the two migrated plugins back in so the AAR path is exercised.
const List<String> _buildModes = <String>['debug', 'profile', 'release'];

/// Plugins that opt in to AAR builds, and so must appear in the plan's `aar` list.
const List<String> _expectedAarPlugins = <String>['sample_plugin', 'sample_consuming_plugin'];

/// Plugins that must still be built from source.
const List<String> _expectedSourcePlugins = <String>['unmigrated_sample_plugin'];

Future<void> main() async {
  await task(() async {
    section('Find Java');
    final String? javaHome = await findJavaHome();
    if (javaHome == null) {
      return TaskResult.failure('Could not find Java');
    }
    print('\nUsing JAVA_HOME=$javaHome');

    final Directory tempDir = Directory.systemTemp.createTempSync('flutter_plugin_aar.');
    try {
      // Copy the whole fixture so the relative `path:` dependencies between the host app and the
      // sibling plugins are preserved.
      final source = Directory(
        path.join(
          flutterDirectory.path,
          'dev',
          'integration_tests',
          'composite_flutter_plugin_test',
        ),
      );
      final projectRoot = Directory(path.join(tempDir.path, 'composite_flutter_plugin_test'));
      recursiveCopy(source, projectRoot);

      final hostApp = Directory(path.join(projectRoot.path, 'host_app'));

      section('flutter pub get');
      await inDirectory(hostApp, () async {
        await flutter('pub', options: <String>['get']);
      });

      for (final String mode in _buildModes) {
        section('Build --$mode with plugin AAR builds enabled');
        await inDirectory(hostApp, () async {
          await flutter(
            'build',
            options: <String>['apk', '--$mode', '--plugin-aar=${_expectedAarPlugins.join(',')}'],
          );
        });

        final apk = File(
          path.join(hostApp.path, 'build', 'app', 'outputs', 'flutter-apk', 'app-$mode.apk'),
        );
        if (!exists(apk)) {
          return TaskResult.failure('Expected APK ${apk.path} was not produced building --$mode.');
        }

        // The APK existing is necessary but not sufficient: a bug in the demotion logic would
        // silently build everything from source and still produce a working APK, which is exactly
        // the failure mode that makes this feature look like it works while doing nothing. Assert
        // on the plan the tool wrote.
        section('Verify the build plan for --$mode');
        final planFile = File(
          path.join(
            projectRoot.path,
            'host_app',
            'build',
            'flutter_plugins_aar_repo',
            'flutter_plugin_aar_plan.json',
          ),
        );
        if (!exists(planFile)) {
          return TaskResult.failure('No plugin AAR plan was written at ${planFile.path}.');
        }
        final plan = json.decode(planFile.readAsStringSync()) as Map<String, Object?>;

        final aarNames = <String>[
          for (final Object? entry in plan['aar']! as List<Object?>)
            (entry! as Map<String, Object?>)['name']! as String,
        ];
        final sourceNames = <String>[
          for (final Object? entry in plan['subprojects']! as List<Object?>)
            (entry! as Map<String, Object?>)['name']! as String,
        ];

        for (final String expected in _expectedAarPlugins) {
          if (!aarNames.contains(expected)) {
            return TaskResult.failure(
              'Plugin $expected should have been built as an AAR in --$mode, but the plan lists '
              'AAR plugins $aarNames and source-built plugins $sourceNames.',
            );
          }
        }
        for (final String expected in _expectedSourcePlugins) {
          if (!sourceNames.contains(expected)) {
            return TaskResult.failure(
              'Plugin $expected has not opted in to AAR builds and should have been built from '
              'source in --$mode, but the plan lists AAR plugins $aarNames.',
            );
          }
        }

        // Debug consumes a different coordinate than profile and release.
        final expectedGroup = mode == 'debug' ? 'dev.flutter.plugins.debug' : 'dev.flutter.plugins';
        final repoDirectory = Directory(
          path.join(
            planFile.parent.path,
            ...expectedGroup.split('.'),
            'sample_plugin',
          ),
        );
        if (!exists(repoDirectory)) {
          return TaskResult.failure(
            'Expected a $expectedGroup AAR for sample_plugin in the local plugin repository at '
            '${repoDirectory.path} when building --$mode.',
          );
        }
      }

      section('Verify a source build of the same app still works');
      await inDirectory(hostApp, () async {
        await flutter('clean');
        await flutter('build', options: <String>['apk', '--debug', '--no-plugin-aar']);
      });
      final fallbackApk = File(
        path.join(hostApp.path, 'build', 'app', 'outputs', 'flutter-apk', 'app-debug.apk'),
      );
      if (!exists(fallbackApk)) {
        return TaskResult.failure('--no-plugin-aar did not produce ${fallbackApk.path}.');
      }

      return TaskResult.success(null);
    } finally {
      rmTree(tempDir);
    }
  });
}
