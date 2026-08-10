// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:file/memory.dart';
import 'package:file_testing/file_testing.dart';
import 'package:flutter_tools/src/android/plugin_aar.dart';
import 'package:flutter_tools/src/android/plugin_aar_builder.dart';
import 'package:flutter_tools/src/base/file_system.dart';
import 'package:flutter_tools/src/base/logger.dart';
import 'package:flutter_tools/src/base/process.dart';
import 'package:flutter_tools/src/convert.dart';
import 'package:flutter_tools/src/platform_plugins.dart';
import 'package:flutter_tools/src/plugins.dart';

import '../../src/common.dart';
import '../../src/fake_process_manager.dart';

const _inputs = PluginAarBuildInputs(
  agpVersion: '8.11.1',
  gradleVersion: '8.13',
  kotlinVersion: '2.2.20',
  javaVersion: '17',
  engineVersion: '1.0.0-abc',
  flutterGradlePluginVersion: 'fgpdigest',
);

Plugin _plugin(
  FileSystem fileSystem,
  String name, {
  String? version = '1.0.0',
  PluginSource source = PluginSource.hosted,
  List<String> dependencies = const <String>[],
  bool migrated = true,
}) {
  final String path = fileSystem.path.join('/packages', name);
  fileSystem.directory(path).childDirectory('android')
    ..createSync(recursive: true)
    ..childFile('gradle.properties').writeAsStringSync(
      migrated ? 'flutter.plugin.migrated=true' : 'flutter.plugin.migrated=false',
    );
  return Plugin(
    name: name,
    path: path,
    platforms: const <String, PluginPlatform>{},
    defaultPackagePlatforms: const <String, String>{},
    pluginDartClassPlatforms: const <String, DartPluginClassAndFilePair>{},
    dependencies: dependencies,
    isDirectDependency: true,
    isDevDependency: false,
    version: version,
    source: source,
  );
}

void main() {
  late FileSystem fileSystem;
  late BufferLogger logger;

  setUp(() {
    fileSystem = MemoryFileSystem.test();
    logger = BufferLogger.test();
  });

  PluginAarBuilder builderWith(FakeProcessManager processManager) => PluginAarBuilder(
    fileSystem: fileSystem,
    logger: logger,
    processUtils: ProcessUtils(logger: logger, processManager: processManager),
  );

  /// Builds a FakeCommand that behaves like a real plugin publish: it writes the
  /// artifacts into whatever staging directory the builder passed on the command
  /// line, at the coordinate path the cache expects.
  FakeCommand publishCommand({
    required String pluginName,
    required String version,
    required bool debug,
    List<String> repositoryUrls = const <String>[],
    int exitCode = 0,
  }) {
    return FakeCommand(
      command: <Pattern>[
        'gradlew',
        '-p',
        '/packages/$pluginName/android',
        'publishFlutterPluginPublicationToFlutterLocalRepository',
        'flutterPluginRepositoriesManifest',
        '-Pflutter.sdk=/flutter',
        '-Pflutter.plugin.name=$pluginName',
        '-Pflutter.plugin.version=$version',
        '-Pflutter.plugin.variant=${debug ? 'debug' : 'release'}',
        '-Pflutter.plugin.groupId=${debug ? 'dev.flutter.plugins.debug' : 'dev.flutter.plugins'}',
        RegExp(r'^-Pflutter\.plugin\.publishRepo=.+'),
        RegExp(r'^-Pflutter\.plugin\.resolveRepo=.+'),
        '-Pflutter.plugin.allAbis=true',
        RegExp(r'^-Pflutter\.plugin\.siblingVersions=.+'),
        '-q',
      ],
      exitCode: exitCode,
      onRun: (List<String> command) {
        if (exitCode != 0) {
          return;
        }
        final String stagingRoot = command
            .firstWhere((String argument) => argument.startsWith('-Pflutter.plugin.publishRepo='))
            .split('=')
            .last;
        final group = debug ? 'dev.flutter.plugins.debug' : 'dev.flutter.plugins';
        final Directory destination = fileSystem.directory(
          fileSystem.path.joinAll(<String>[stagingRoot, ...group.split('.'), pluginName, version]),
        )..createSync(recursive: true);
        destination.childFile('$pluginName-$version.aar').writeAsStringSync('aar');
        destination.childFile('$pluginName-$version.pom').writeAsStringSync('pom');
        destination.childFile('$pluginName-$version-repositories.json').writeAsStringSync(
          json.encode(<String, Object?>{
            'repositories': <Object?>[
              for (final String url in repositoryUrls) <String, Object?>{'url': url},
            ],
          }),
        );
      },
    );
  }

  Future<AndroidPluginBuildPlan> run(
    PluginAarBuilder builder, {
    required List<Plugin> plugins,
    Set<String> buildModes = const <String>{'release'},
    bool aarBuildsEnabled = true,
    Future<PluginAarBuildInputs> Function()? resolveBuildInputs,
  }) {
    return builder.prepare(
      plugins: plugins,
      resolveBuildInputs: resolveBuildInputs ?? () async => _inputs,
      gradleExecutable: 'gradlew',
      flutterSdkPath: '/flutter',
      localRepository: fileSystem.directory('/app/build/flutter_plugins_aar_repo'),
      cacheRoot: fileSystem.directory('/home/.flutter/plugin-aar-cache'),
      buildModes: buildModes,
      aarBuildsEnabled: aarBuildsEnabled,
    );
  }

  group('materialization', () {
    testWithoutContext('publishes into the cache and copies into the project repo', () async {
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(pluginName: 'a', version: '1.0.0', debug: false),
      ]);

      await run(builderWith(processManager), plugins: <Plugin>[_plugin(fileSystem, 'a')]);

      // The artifact must land at the real Maven coordinate path. Reconstructing
      // the repository root by walking a fixed number of parents up from the
      // artifact directory is wrong, because the group id expands to several
      // path segments and the debug group has one more than the release group.
      expect(
        fileSystem.file(
          '/home/.flutter/plugin-aar-cache/${_inputs.digest}/dev/flutter/plugins/a/1.0.0/a-1.0.0.aar',
        ),
        exists,
      );
      expect(
        fileSystem.file(
          '/app/build/flutter_plugins_aar_repo/dev/flutter/plugins/a/1.0.0/a-1.0.0.aar',
        ),
        exists,
      );
      expect(processManager, hasNoRemainingExpectations);
    });

    testWithoutContext('the debug group has its own coordinate path', () async {
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(pluginName: 'a', version: '1.0.0', debug: true),
      ]);

      await run(
        builderWith(processManager),
        plugins: <Plugin>[_plugin(fileSystem, 'a')],
        buildModes: <String>{'debug'},
      );

      expect(
        fileSystem.file(
          '/app/build/flutter_plugins_aar_repo/dev/flutter/plugins/debug/a/1.0.0/a-1.0.0.aar',
        ),
        exists,
      );
      expect(processManager, hasNoRemainingExpectations);
    });

    testWithoutContext('a second build reuses the cache without invoking Gradle', () async {
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(pluginName: 'a', version: '1.0.0', debug: false),
      ]);
      final plugins = <Plugin>[_plugin(fileSystem, 'a')];

      await run(builderWith(processManager), plugins: plugins);
      expect(processManager, hasNoRemainingExpectations);

      // No further commands are queued, so a second run that tried to build
      // would fail rather than silently rebuild.
      final empty = FakeProcessManager.empty();
      await run(builderWith(empty), plugins: plugins);

      expect(
        fileSystem.file(
          '/app/build/flutter_plugins_aar_repo/dev/flutter/plugins/a/1.0.0/a-1.0.0.aar',
        ),
        exists,
      );
    });

    testWithoutContext('stale artifacts from an earlier version are not left behind', () async {
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(pluginName: 'a', version: '2.0.0', debug: false),
      ]);
      final Directory stale = fileSystem.directory(
        '/app/build/flutter_plugins_aar_repo/dev/flutter/plugins/a/2.0.0',
      )..createSync(recursive: true);
      stale.childFile('leftover.aar').writeAsStringSync('old');

      await run(
        builderWith(processManager),
        plugins: <Plugin>[_plugin(fileSystem, 'a', version: '2.0.0')],
      );

      expect(stale.childFile('leftover.aar'), isNot(exists));
      expect(stale.childFile('a-2.0.0.aar'), exists);
    });
  });

  group('build ordering', () {
    testWithoutContext('a plugin is built after the sibling it depends on', () async {
      // `consumer` is listed first but must be built second, since its build
      // resolves `leaf` out of the repository `leaf`'s build just published to.
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(pluginName: 'leaf', version: '1.0.0', debug: false),
        publishCommand(pluginName: 'consumer', version: '1.0.0', debug: false),
      ]);

      await run(
        builderWith(processManager),
        plugins: <Plugin>[
          _plugin(fileSystem, 'consumer', dependencies: <String>['leaf']),
          _plugin(fileSystem, 'leaf'),
        ],
      );

      expect(processManager, hasNoRemainingExpectations);
    });
  });

  group('the plan file', () {
    testWithoutContext('is written even when every plugin is built from source', () async {
      await run(
        builderWith(FakeProcessManager.empty()),
        plugins: <Plugin>[_plugin(fileSystem, 'a', migrated: false)],
      );

      final File planFile = fileSystem.file(
        '/app/build/flutter_plugins_aar_repo/flutter_plugin_aar_plan.json',
      );
      // A stale plan from an earlier build with different flags must never be
      // what Gradle reads.
      expect(planFile, exists);
      final plan = json.decode(planFile.readAsStringSync()) as Map<String, Object?>;
      expect(plan['aar'], isEmpty);
      expect((plan['subprojects']! as List<Object?>).single, containsPair('name', 'a'));
    });

    testWithoutContext('carries the repositories the plugin builds reported', () async {
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(
          pluginName: 'a',
          version: '1.0.0',
          debug: false,
          repositoryUrls: <String>['https://maven.myco.com/repo'],
        ),
      ]);

      await run(builderWith(processManager), plugins: <Plugin>[_plugin(fileSystem, 'a')]);

      final plan =
          json.decode(
                fileSystem
                    .file('/app/build/flutter_plugins_aar_repo/flutter_plugin_aar_plan.json')
                    .readAsStringSync(),
              )
              as Map<String, Object?>;
      expect(plan['extraRepositories'], <String>['https://maven.myco.com/repo']);
    });
  });

  group('cost avoidance', () {
    testWithoutContext('build inputs are not resolved when nothing is eligible', () async {
      // Resolving the inputs costs a Gradle invocation of its own. Projects with
      // no eligible plugins — which is nearly all of them — must not pay it.
      var resolved = false;

      await run(
        builderWith(FakeProcessManager.empty()),
        plugins: <Plugin>[_plugin(fileSystem, 'a', migrated: false)],
        resolveBuildInputs: () async {
          resolved = true;
          return _inputs;
        },
      );

      expect(resolved, isFalse);
    });

    testWithoutContext('build inputs are resolved once something is eligible', () async {
      var resolved = false;
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(pluginName: 'a', version: '1.0.0', debug: false),
      ]);

      await run(
        builderWith(processManager),
        plugins: <Plugin>[_plugin(fileSystem, 'a')],
        resolveBuildInputs: () async {
          resolved = true;
          return _inputs;
        },
      );

      expect(resolved, isTrue);
    });
  });

  group('failure handling', () {
    testWithoutContext('a failed plugin build reports the plugin and the escape hatch', () async {
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(pluginName: 'a', version: '1.0.0', debug: false, exitCode: 1),
      ]);

      await expectLater(
        run(builderWith(processManager), plugins: <Plugin>[_plugin(fileSystem, 'a')]),
        throwsToolExit(message: 'Failed to build the release AAR for plugin "a"'),
      );
    });

    testWithoutContext('a build that succeeds but publishes nothing fails loudly', () async {
      // A silently empty publish would otherwise leave the app resolving against
      // a repository with no artifact in it.
      final processManager = FakeProcessManager.list(<FakeCommand>[
        FakeCommand(
          command: <Pattern>[
            'gradlew',
            '-p',
            '/packages/a/android',
            'publishFlutterPluginPublicationToFlutterLocalRepository',
            'flutterPluginRepositoriesManifest',
            '-Pflutter.sdk=/flutter',
            '-Pflutter.plugin.name=a',
            '-Pflutter.plugin.version=1.0.0',
            '-Pflutter.plugin.variant=release',
            '-Pflutter.plugin.groupId=dev.flutter.plugins',
            RegExp(r'^-Pflutter\.plugin\.publishRepo=.+'),
            RegExp(r'^-Pflutter\.plugin\.resolveRepo=.+'),
            '-Pflutter.plugin.allAbis=true',
            RegExp(r'^-Pflutter\.plugin\.siblingVersions=.+'),
            '-q',
          ],
        ),
      ]);

      await expectLater(
        run(builderWith(processManager), plugins: <Plugin>[_plugin(fileSystem, 'a')]),
        throwsToolExit(message: 'but the plugin build produced nothing there'),
      );
    });
  });

  group('staging', () {
    testWithoutContext('no staging directories are left behind', () async {
      final processManager = FakeProcessManager.list(<FakeCommand>[
        publishCommand(pluginName: 'a', version: '1.0.0', debug: false),
      ]);

      await run(builderWith(processManager), plugins: <Plugin>[_plugin(fileSystem, 'a')]);

      final Iterable<String> leftovers = fileSystem
          .directory('/home/.flutter/plugin-aar-cache')
          .listSync()
          .map((FileSystemEntity entity) => entity.basename)
          .where((String name) => name.startsWith('.staging-'));
      expect(leftovers, isEmpty);
    });
  });
}
