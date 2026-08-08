// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import '../base/common.dart';
import '../base/file_system.dart';
import '../base/logger.dart';
import '../base/process.dart';
import '../convert.dart';
import '../plugins.dart';
import 'plugin_aar.dart';

/// Builds Flutter plugins into AARs and publishes them into a local Maven
/// repository for the app build to consume.
///
/// This runs from the tool, before Gradle is invoked for the app, rather than
/// from a Gradle settings plugin. Driving it here means plugin builds can run
/// in dependency order without blocking settings evaluation, the app build's
/// configuration cache stays usable (a settings plugin that spawns nested
/// builds is skipped entirely on a configuration cache hit, which would
/// silently link stale AARs), and every plugin build reuses the app's own
/// Gradle wrapper instead of resolving a distribution of its own.
class PluginAarBuilder {
  PluginAarBuilder({
    required FileSystem fileSystem,
    required Logger logger,
    required ProcessUtils processUtils,
  }) : _fileSystem = fileSystem,
       _logger = logger,
       _processUtils = processUtils;

  final FileSystem _fileSystem;
  final Logger _logger;
  final ProcessUtils _processUtils;

  /// Prepares the local plugin repository for an app build.
  ///
  /// Returns the plan describing which plugins are consumed as AARs and which
  /// are built from source, and writes that plan into [localRepository] for
  /// the Gradle side to read.
  Future<AndroidPluginBuildPlan> prepare({
    required List<Plugin> plugins,
    required PluginAarBuildInputs buildInputs,
    required String gradleExecutable,
    required String flutterSdkPath,
    required Directory localRepository,
    required Directory cacheRoot,
    required Set<String> buildModes,
    bool aarBuildsEnabled = true,
    Set<String> forceAarPluginNames = const <String>{},
    bool offline = false,
  }) async {
    final AndroidPluginBuildPlan plan = computeAndroidPluginBuildPlan(
      plugins: plugins,
      fileSystem: _fileSystem,
      aarBuildsEnabled: aarBuildsEnabled,
      forceAarPluginNames: forceAarPluginNames,
    );

    plan.demotionDiagnostics.forEach(_logger.printTrace);

    final cache = PluginAarCache(rootDirectory: cacheRoot, inputs: buildInputs);
    localRepository.createSync(recursive: true);

    if (plan.aarPlugins.isNotEmpty) {
      cache.writeBuildInputsManifest();

      // Debug and release are separate coordinates, so only build the variants
      // this invocation actually needs. A release-only CI build never pays for
      // the debug AARs.
      final variants = <bool>{
        for (final String mode in buildModes) mode == 'debug',
      };

      // Plugin build files declare sibling plugins without a version, since the version is
      // whatever pub resolved for this app and the plugin author cannot know it. Pass the
      // resolved versions in so the plugin build can fill them in.
      final String siblingVersions = plan.aarPlugins
          .map((AarPlugin aar) => '${aar.plugin.name}=${aar.mavenVersion}')
          .join(',');

      for (final AarPlugin aar in _inDependencyOrder(plan.aarPlugins)) {
        for (final debug in variants) {
          if (cache.contains(aar, debug: debug)) {
            _logger.printTrace(
              'Reusing cached ${debug ? 'debug' : 'release'} AAR for '
              '${aar.plugin.name} ${aar.mavenVersion}.',
            );
            continue;
          }
          await _buildOne(
            aar: aar,
            debug: debug,
            cache: cache,
            gradleExecutable: gradleExecutable,
            flutterSdkPath: flutterSdkPath,
            siblingVersions: siblingVersions,
            offline: offline,
          );
        }
      }

      _materialize(cache: cache, plan: plan, variants: variants, into: localRepository);
    }

    final List<String> extraRepositories = _collectExtraRepositories(localRepository);
    localRepository
        .childFile('flutter_plugin_aar_plan.json')
        .writeAsStringSync(
          const JsonEncoder.withIndent('  ').convert(
            plan.toJson(repositoryPath: localRepository.path, repositories: extraRepositories),
          ),
        );

    return plan;
  }

  /// Orders [plugins] so that a plugin is built after everything it depends
  /// on, since an inter-plugin dependency is resolved from the same cache
  /// directory the dependency was just published into.
  List<AarPlugin> _inDependencyOrder(List<AarPlugin> plugins) {
    final byName = <String, AarPlugin>{
      for (final AarPlugin aar in plugins) aar.plugin.name: aar,
    };
    final ordered = <AarPlugin>[];
    final visited = <String>{};
    final visiting = <String>{};

    void visit(AarPlugin aar) {
      final String name = aar.plugin.name;
      if (visited.contains(name)) {
        return;
      }
      if (!visiting.add(name)) {
        // The plugin dependency graph comes from pub, which already rejects
        // dependency cycles, so this is defensive only.
        throwToolExit('Circular dependency between Flutter plugins involving "$name".');
      }
      for (final String dependency in aar.plugin.dependencies) {
        final AarPlugin? next = byName[dependency];
        if (next != null) {
          visit(next);
        }
      }
      visiting.remove(name);
      visited.add(name);
      ordered.add(aar);
    }

    plugins.forEach(visit);
    return ordered;
  }

  Future<void> _buildOne({
    required AarPlugin aar,
    required bool debug,
    required PluginAarCache cache,
    required String gradleExecutable,
    required String flutterSdkPath,
    required String siblingVersions,
    required bool offline,
  }) async {
    final variant = debug ? 'debug' : 'release';
    final Directory pluginAndroidDirectory = _fileSystem
        .directory(aar.plugin.path)
        .childDirectory('android');
    if (!pluginAndroidDirectory.existsSync()) {
      throwToolExit(
        'Plugin ${aar.plugin.name} is marked as migrated but has no android directory at '
        '${pluginAndroidDirectory.path}.',
      );
    }

    final Status status = _logger.startProgress(
      'Building $variant AAR for ${aar.plugin.name} ${aar.mavenVersion}...',
    );

    // The plugin build writes into the shared cache root directly. Every
    // plugin for a given toolchain configuration publishes into the same
    // keyed repository, so an inter-plugin dependency resolves from the
    // artifact its own build just published.
    final Directory publishRoot = cache.directoryFor(aar, debug: debug).parent.parent.parent;

    final command = <String>[
      gradleExecutable,
      '-p',
      pluginAndroidDirectory.path,
      'publishFlutterPluginPublicationToFlutterLocalRepository',
      '-Pflutter.sdk=$flutterSdkPath',
      '-Pflutter.plugin.name=${aar.plugin.name}',
      '-Pflutter.plugin.version=${aar.mavenVersion}',
      '-Pflutter.plugin.variant=$variant',
      '-Pflutter.plugin.groupId=${debug ? kFlutterPluginDebugGroupId : kFlutterPluginGroupId}',
      '-Pflutter.plugin.publishRepo=${publishRoot.path}',
      // Resolve inter-plugin dependencies from the same repository.
      '-Pflutter.plugin.resolveRepo=${publishRoot.path}',
      // Plugin AARs always carry every ABI; the app strips down to the ABIs it
      // wants. Forwarding the app's --target-platform here would produce a
      // partial AAR cached under a key that claims to be complete.
      '-Pflutter.plugin.allAbis=true',
      if (siblingVersions.isNotEmpty) '-Pflutter.plugin.siblingVersions=$siblingVersions',
    ];
    if (offline) {
      command.add('--offline');
    }
    if (_logger.isVerbose) {
      command.add('--info');
    } else {
      command.add('-q');
    }

    final int exitCode = await _processUtils.stream(
      command,
      trace: true,
      mapFunction: (String line) => line,
    );
    status.stop();

    if (exitCode != 0) {
      throwToolExit(
        'Failed to build the $variant AAR for plugin "${aar.plugin.name}" '
        '(${pluginAndroidDirectory.path}).\n'
        'Re-run with --verbose for the full Gradle output, or pass --no-plugin-aar to build '
        'this plugin from source instead.',
      );
    }
  }

  /// Copies the built artifacts out of the shared cache and into the
  /// project-local repository, so Gradle only ever sees a plain Maven
  /// repository containing exactly this build's plugins.
  void _materialize({
    required PluginAarCache cache,
    required AndroidPluginBuildPlan plan,
    required Set<bool> variants,
    required Directory into,
  }) {
    for (final AarPlugin aar in plan.aarPlugins) {
      for (final debug in variants) {
        final Directory source = cache.directoryFor(aar, debug: debug);
        if (!source.existsSync()) {
          throwToolExit(
            'Expected a ${debug ? 'debug' : 'release'} AAR for ${aar.plugin.name} at '
            '${source.path}, but the plugin build produced nothing there.',
          );
        }
        final String group = debug ? kFlutterPluginDebugGroupId : kFlutterPluginGroupId;
        final Directory destination = into
            .childDirectory(group.replaceAll('.', _fileSystem.path.separator))
            .childDirectory(aar.plugin.name)
            .childDirectory(aar.mavenVersion);
        destination.createSync(recursive: true);
        for (final File file in source.listSync().whereType<File>()) {
          file.copySync(destination.childFile(file.basename).path);
        }
      }
    }
  }

  /// Gathers the Maven repositories that plugin builds reported needing.
  ///
  /// A plugin's `implementation` dependencies become dependencies of the app
  /// once the plugin is an AAR, so any non-default repository the plugin
  /// resolved them from has to be declared by the app too. Each plugin build
  /// writes the repositories it used next to its artifacts; the app's settings
  /// plugin adds them. Credentials are deliberately never carried across — if
  /// an injected repository needs authentication the app owner has to supply
  /// it, and they get an error naming the repository rather than an
  /// unresolvable dependency.
  List<String> _collectExtraRepositories(Directory repository) {
    final urls = <String>{};
    if (!repository.existsSync()) {
      return <String>[];
    }
    for (final FileSystemEntity entity in repository.listSync(recursive: true)) {
      if (entity is! File || !entity.basename.endsWith('-repositories.json')) {
        continue;
      }
      try {
        final Object? decoded = json.decode(entity.readAsStringSync());
        if (decoded is! Map<String, Object?>) {
          continue;
        }
        final Object? repositories = decoded['repositories'];
        if (repositories is! List<Object?>) {
          continue;
        }
        for (final Object? entry in repositories) {
          if (entry is Map<String, Object?> && entry['url'] is String) {
            urls.add(entry['url']! as String);
          }
        }
      } on FormatException catch (error) {
        _logger.printTrace('Ignoring malformed ${entity.path}: $error');
      }
    }
    return urls.toList()..sort();
  }
}
