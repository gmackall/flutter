// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:file/memory.dart';
import 'package:flutter_tools/src/android/plugin_aar.dart';
import 'package:flutter_tools/src/base/file_system.dart';
import 'package:flutter_tools/src/cache.dart';
import 'package:flutter_tools/src/flutter_plugins.dart';
import 'package:flutter_tools/src/platform_plugins.dart';
import 'package:flutter_tools/src/plugins.dart';

import '../../src/common.dart';

/// Creates a plugin, optionally materializing an `android/gradle.properties`
/// that opts it in to AAR builds.
Plugin _plugin(
  FileSystem fileSystem,
  String name, {
  String? version = '1.0.0',
  PluginSource source = PluginSource.hosted,
  List<String> dependencies = const <String>[],
  bool migrated = true,
  bool nativeCode = false,
  bool isDevDependency = false,
}) {
  final String path = fileSystem.path.join('/packages', name);
  final Directory android = fileSystem.directory(path).childDirectory('android')
    ..createSync(recursive: true);
  android.childFile('gradle.properties').writeAsStringSync(
    <String>[
      'android.useAndroidX=true',
      if (migrated) 'flutter.plugin.migrated=true' else 'flutter.plugin.migrated=false',
    ].join('\n'),
  );
  if (nativeCode) {
    android.childFile('build.gradle').writeAsStringSync('''
android {
  defaultConfig {
    externalNativeBuild { cmake { cppFlags "" } }
  }
}
''');
  }
  return Plugin(
    name: name,
    path: path,
    platforms: const <String, PluginPlatform>{},
    defaultPackagePlatforms: const <String, String>{},
    pluginDartClassPlatforms: const <String, DartPluginClassAndFilePair>{},
    dependencies: dependencies,
    isDirectDependency: true,
    isDevDependency: isDevDependency,
    version: version,
    source: source,
  );
}

void main() {
  late FileSystem fileSystem;

  setUp(() {
    fileSystem = MemoryFileSystem.test();
  });

  group('plugin source detection', () {
    // Pub lays its cache out as <cache>/hosted/<host>/<name>-<version> and
    // <cache>/git/<name>-<ref>, so classification is structural and does not
    // depend on locating the cache, which varies by platform and PUB_CACHE.
    testWithoutContext('classifies a hosted package', () {
      expect(
        pluginSourceForPackageRoot(
          '/home/u/.pub-cache/hosted/pub.dev/url_launcher_android-6.3.14',
          fileSystem,
        ),
        PluginSource.hosted,
      );
    });

    testWithoutContext('classifies a git package', () {
      expect(
        pluginSourceForPackageRoot('/home/u/.pub-cache/git/my_plugin-abc123', fileSystem),
        PluginSource.git,
      );
    });

    testWithoutContext('classifies a local path package', () {
      expect(pluginSourceForPackageRoot('/work/monorepo/my_plugin', fileSystem), PluginSource.path);
    });

    testWithoutContext('classifies a package vendored by the SDK', () {
      Cache.flutterRoot = '/opt/flutter';
      addTearDown(() => Cache.flutterRoot = null);

      expect(
        pluginSourceForPackageRoot('/opt/flutter/packages/flutter_tools', fileSystem),
        PluginSource.sdk,
      );
    });

    testWithoutContext('only hosted and git sources are cacheable', () {
      expect(PluginSource.hosted.isImmutable, isTrue);
      expect(PluginSource.git.isImmutable, isTrue);
      expect(PluginSource.path.isImmutable, isFalse);
      expect(PluginSource.sdk.isImmutable, isFalse);
    });

    testWithoutContext('an unknown source key degrades to the mutable default', () {
      expect(PluginSource.fromKey('something_new'), PluginSource.path);
      expect(PluginSource.fromKey(null), PluginSource.path);
    });
  });

  group('maven version mapping', () {
    testWithoutContext('passes a plain semver version through', () {
      expect(mavenVersionForPubVersion('6.3.14'), '6.3.14');
    });

    testWithoutContext('rewrites pub build metadata so Gradle cannot read it as dynamic', () {
      // Gradle parses a trailing `+` in a version selector as a dynamic
      // version, so `1.2.3+4` must not survive into a coordinate verbatim.
      expect(mavenVersionForPubVersion('1.2.3+4'), '1.2.3-4');
      expect(mavenVersionForPubVersion('0.4.0+1'), '0.4.0-1');
    });
  });

  group('coordinates', () {
    testWithoutContext('debug and release are distinct coordinates', () {
      final aar = AarPlugin(plugin: _plugin(fileSystem, 'url_launcher'), mavenVersion: '6.3.14');

      expect(aar.releaseCoordinate, 'dev.flutter.plugins:url_launcher:6.3.14');
      expect(aar.debugCoordinate, 'dev.flutter.plugins.debug:url_launcher:6.3.14');
    });

    testWithoutContext('profile resolves the release coordinate', () {
      final aar = AarPlugin(plugin: _plugin(fileSystem, 'url_launcher'), mavenVersion: '6.3.14');

      expect(aar.coordinateForBuildMode('debug'), aar.debugCoordinate);
      expect(aar.coordinateForBuildMode('profile'), aar.releaseCoordinate);
      expect(aar.coordinateForBuildMode('release'), aar.releaseCoordinate);
    });

    testWithoutContext('a custom build mode falls back to release rather than failing', () {
      final aar = AarPlugin(plugin: _plugin(fileSystem, 'url_launcher'), mavenVersion: '6.3.14');

      expect(aar.coordinateForBuildMode('staging'), aar.releaseCoordinate);
    });
  });

  group('build plan', () {
    testWithoutContext('an opted-in hosted plugin is consumed as an AAR', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a')],
        fileSystem: fileSystem,
      );

      expect(plan.aarPlugins.map((AarPlugin aar) => aar.plugin.name), <String>['a']);
      expect(plan.demotedPlugins, isEmpty);
    });

    testWithoutContext('a plugin that has not opted in is built from source', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a', migrated: false)],
        fileSystem: fileSystem,
      );

      expect(plan.aarPlugins, isEmpty);
      expect(plan.demotedPlugins.single.reason, PluginDemotionReason.notMigrated);
    });

    testWithoutContext('a path dependency is built from source even when opted in', () {
      // A path dependency is the signal that the developer may be editing the
      // plugin, which is exactly when a cached prebuilt AAR would be wrong.
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a', source: PluginSource.path)],
        fileSystem: fileSystem,
      );

      expect(plan.aarPlugins, isEmpty);
      expect(plan.demotedPlugins.single.reason, PluginDemotionReason.mutableSource);
    });

    testWithoutContext('a path dependency can be forced back into an AAR build', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a', source: PluginSource.path)],
        fileSystem: fileSystem,
        forceAarPluginNames: <String>{'a'},
      );

      expect(plan.aarPlugins.single.plugin.name, 'a');
    });

    testWithoutContext('a git dependency is immutable and may be an AAR', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a', source: PluginSource.git)],
        fileSystem: fileSystem,
      );

      expect(plan.aarPlugins.single.plugin.name, 'a');
    });

    testWithoutContext('a versionless plugin cannot form a coordinate', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a', version: null)],
        fileSystem: fileSystem,
      );

      expect(plan.demotedPlugins.single.reason, PluginDemotionReason.noVersion);
    });

    testWithoutContext('a native plugin is out of scope', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a', nativeCode: true)],
        fileSystem: fileSystem,
      );

      expect(plan.demotedPlugins.single.reason, PluginDemotionReason.nativeCode);
    });

    testWithoutContext('disabling AAR builds demotes everything', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a'), _plugin(fileSystem, 'b')],
        fileSystem: fileSystem,
        aarBuildsEnabled: false,
      );

      expect(plan.aarPlugins, isEmpty);
      expect(
        plan.demotedPlugins.map((DemotedPlugin demoted) => demoted.reason),
        everyElement(PluginDemotionReason.disabled),
      );
    });
  });

  group('transitive demotion', () {
    testWithoutContext('a dependent of a source-built plugin is demoted too', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[
          _plugin(fileSystem, 'consumer', dependencies: <String>['leaf']),
          _plugin(fileSystem, 'leaf', migrated: false),
        ],
        fileSystem: fileSystem,
      );

      expect(plan.aarPlugins, isEmpty);
      final DemotedPlugin consumer = plan.demotedPlugins.firstWhere(
        (DemotedPlugin demoted) => demoted.plugin.name == 'consumer',
      );
      expect(consumer.reason, PluginDemotionReason.dependsOnDemoted);
      expect(consumer.culprit, 'leaf');
    });

    testWithoutContext('demotion propagates along a chain regardless of list order', () {
      // `top` is listed before the plugin that ultimately demotes it, so a
      // single pass in list order would leave `top` incorrectly on the AAR
      // path. The closure has to reach a fixed point.
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[
          _plugin(fileSystem, 'top', dependencies: <String>['middle']),
          _plugin(fileSystem, 'middle', dependencies: <String>['leaf']),
          _plugin(fileSystem, 'leaf', migrated: false),
        ],
        fileSystem: fileSystem,
      );

      expect(plan.aarPlugins, isEmpty);
      expect(
        plan.demotedPlugins.map((DemotedPlugin demoted) => demoted.plugin.name),
        containsAll(<String>['top', 'middle', 'leaf']),
      );
    });

    testWithoutContext('an unrelated plugin keeps its AAR build', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[
          _plugin(fileSystem, 'consumer', dependencies: <String>['leaf']),
          _plugin(fileSystem, 'leaf', migrated: false),
          _plugin(fileSystem, 'unrelated'),
        ],
        fileSystem: fileSystem,
      );

      expect(plan.aarPlugins.map((AarPlugin aar) => aar.plugin.name), <String>['unrelated']);
    });

    testWithoutContext('a non-plugin dependency does not demote', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[_plugin(fileSystem, 'a', dependencies: <String>['collection', 'meta'])],
        fileSystem: fileSystem,
      );

      expect(plan.aarPlugins.single.plugin.name, 'a');
    });

    testWithoutContext('the straggler that caused a demotion is reported', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[
          _plugin(fileSystem, 'consumer', dependencies: <String>['leaf']),
          _plugin(fileSystem, 'leaf', migrated: false),
        ],
        fileSystem: fileSystem,
      );

      // A plugin that opted in but was demoted anyway must be explainable —
      // otherwise one unmigrated straggler silently drags a large graph back
      // to source builds with no way to see why.
      expect(
        plan.demotionDiagnostics,
        contains(
          'Plugin consumer is built from source because it depends on leaf, '
          'which is built from source.',
        ),
      );
    });
  });

  group('plan serialization', () {
    testWithoutContext('carries coordinates and subproject paths', () {
      final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
        plugins: <Plugin>[
          _plugin(fileSystem, 'aar_plugin', version: '2.1.0'),
          _plugin(fileSystem, 'source_plugin', migrated: false),
        ],
        fileSystem: fileSystem,
      );

      final Map<String, Object?> json = plan.toJson(repositoryPath: '/build/repo');

      expect(json['version'], kAndroidPluginBuildPlanVersion);
      expect(json['repository'], '/build/repo');

      final aar = (json['aar']! as List<Object?>).cast<Map<String, Object?>>();
      expect(aar.single['name'], 'aar_plugin');
      expect(aar.single['version'], '2.1.0');
      expect(aar.single['groupId'], kFlutterPluginGroupId);
      expect(aar.single['debugGroupId'], kFlutterPluginDebugGroupId);

      final subprojects = (json['subprojects']! as List<Object?>).cast<Map<String, Object?>>();
      expect(subprojects.single['name'], 'source_plugin');
      expect(subprojects.single['reason'], 'notMigrated');
    });
  });

  group('cache keying', () {
    PluginAarBuildInputs inputs({String agpVersion = '8.11.1', String minSdk = '24'}) {
      return PluginAarBuildInputs(
        agpVersion: agpVersion,
        gradleVersion: '8.13',
        kotlinVersion: '2.2.20',
        javaVersion: '17',
        engineVersion: '1.0.0-abc',
        compileSdk: '36',
        minSdk: minSdk,
        targetSdk: '36',
        ndkVersion: '27.0.12077973',
        flutterGradlePluginVersion: '1.0.0',
      );
    }

    testWithoutContext('identical inputs produce the same digest', () {
      expect(inputs().digest, inputs().digest);
    });

    testWithoutContext('a different AGP version produces a different digest', () {
      expect(inputs().digest, isNot(inputs(agpVersion: '9.0.1').digest));
    });

    testWithoutContext('a different minSdk produces a different digest', () {
      expect(inputs().digest, isNot(inputs(minSdk: '21').digest));
    });

    testWithoutContext('an entry is only a hit once an aar is present', () {
      final cache = PluginAarCache(
        rootDirectory: fileSystem.directory('/cache'),
        inputs: inputs(),
      );
      final aar = AarPlugin(plugin: _plugin(fileSystem, 'a'), mavenVersion: '1.0.0');

      expect(cache.contains(aar, debug: false), isFalse);

      cache.directoryFor(aar, debug: false)
        ..createSync(recursive: true)
        ..childFile('a-1.0.0.aar').writeAsStringSync('');

      expect(cache.contains(aar, debug: false), isTrue);
      // Debug and release are separate coordinates and separate cache entries.
      expect(cache.contains(aar, debug: true), isFalse);
    });
  });
}
