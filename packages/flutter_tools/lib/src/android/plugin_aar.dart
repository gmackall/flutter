// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:crypto/crypto.dart';
import 'package:meta/meta.dart';

import '../base/file_system.dart';
import '../convert.dart';
import '../plugins.dart';

/// The Maven group used for the release variant of a plugin AAR built by the
/// Flutter tool.
const kFlutterPluginGroupId = 'dev.flutter.plugins';

/// The Maven group used for the debug variant of a plugin AAR.
///
/// Debug and release AARs are published as separate coordinates rather than as
/// two variants of one module. Attribute based variant matching would make an
/// app's custom build types (anything that is not literally `debug`, `profile`
/// or `release`) fail to resolve, because the published module has no matching
/// variant and `matchingFallbacks` cannot reach across the boundary. Flutter
/// already knows the build *mode* of every build type, including custom ones,
/// so selecting a coordinate by mode is both simpler and strictly more general.
const kFlutterPluginDebugGroupId = 'dev.flutter.plugins.debug';

/// The version of the build plan schema written to disk.
///
/// The Gradle side refuses to consume a plan whose version it does not
/// recognize and falls back to building every plugin as a subproject, so that
/// a tool/SDK mismatch degrades to the legacy behavior instead of failing.
const kAndroidPluginBuildPlanVersion = 1;

/// Why a plugin is built from source as a Gradle subproject instead of being
/// consumed as a prebuilt AAR.
enum PluginDemotionReason {
  /// AAR builds were turned off for this build (`--no-plugin-aar`).
  disabled,

  /// The plugin has not opted in via `flutter.plugin.migrated=true`.
  notMigrated,

  /// The plugin was resolved from a mutable source (a `path:` dependency), so
  /// the developer may be editing it and its output cannot be cached.
  mutableSource,

  /// The plugin declares no version, so no Maven coordinate can be formed.
  noVersion,

  /// The plugin builds native code. Native plugins are out of scope until
  /// unstripped debug symbols can be published alongside the AAR.
  nativeCode,

  /// The plugin transitively depends on a plugin that is itself built from
  /// source, and therefore cannot be built in isolation.
  dependsOnDemoted;

  /// A human readable explanation, used in build diagnostics.
  String describe(String pluginName, {String? culprit}) {
    switch (this) {
      case PluginDemotionReason.disabled:
        return 'plugin AAR builds are disabled';
      case PluginDemotionReason.notMigrated:
        return 'it has not set flutter.plugin.migrated=true';
      case PluginDemotionReason.mutableSource:
        return 'it is a path dependency, so it may be edited locally';
      case PluginDemotionReason.noVersion:
        return 'its pubspec.yaml declares no version';
      case PluginDemotionReason.nativeCode:
        return 'it builds native code, which is not yet supported for AAR builds';
      case PluginDemotionReason.dependsOnDemoted:
        return 'it depends on ${culprit ?? 'another plugin'}, which is built from source';
    }
  }
}

/// A plugin that will be built from source as a Gradle subproject.
@immutable
class DemotedPlugin {
  const DemotedPlugin({required this.plugin, required this.reason, this.culprit});

  final Plugin plugin;
  final PluginDemotionReason reason;

  /// For [PluginDemotionReason.dependsOnDemoted], the name of the demoted
  /// plugin that caused this one to be demoted.
  final String? culprit;

  String get explanation => reason.describe(plugin.name, culprit: culprit);
}

/// A plugin that will be built once into an AAR and consumed from a local
/// Maven repository.
@immutable
class AarPlugin {
  const AarPlugin({required this.plugin, required this.mavenVersion});

  final Plugin plugin;

  /// The plugin's pub version mapped into a Maven safe version string.
  final String mavenVersion;

  /// The coordinate of the release AAR, also used for profile builds.
  String get releaseCoordinate => '$kFlutterPluginGroupId:${plugin.name}:$mavenVersion';

  /// The coordinate of the debug AAR.
  String get debugCoordinate => '$kFlutterPluginDebugGroupId:${plugin.name}:$mavenVersion';

  /// The coordinate for a given Flutter build mode.
  ///
  /// Profile deliberately consumes the release AAR: the plugin compiles
  /// against the embedding as `compileOnly`, so which embedding variant it saw
  /// at compile time has no runtime effect, and profile builds want optimized
  /// plugin code.
  String coordinateForBuildMode(String buildMode) =>
      buildMode == 'debug' ? debugCoordinate : releaseCoordinate;
}

/// The decision, for every Android plugin in a build, of whether it is
/// consumed as a prebuilt AAR or built from source as a subproject.
@immutable
class AndroidPluginBuildPlan {
  const AndroidPluginBuildPlan({required this.aarPlugins, required this.demotedPlugins});

  /// Plugins built once into an AAR and resolved from the local Maven repo.
  final List<AarPlugin> aarPlugins;

  /// Plugins built from source as Gradle subprojects, in the legacy model.
  final List<DemotedPlugin> demotedPlugins;

  bool get isEmpty => aarPlugins.isEmpty && demotedPlugins.isEmpty;

  /// Lines describing why each plugin was built from source.
  ///
  /// Surfaced at `--verbose`, and whenever a plugin that opted in to AAR
  /// builds was demoted anyway — otherwise a single unmigrated straggler can
  /// silently drag a large dependency graph back to source builds and the user
  /// has no way to see why nothing got faster.
  Iterable<String> get demotionDiagnostics => demotedPlugins
      .where((DemotedPlugin demoted) => demoted.reason != PluginDemotionReason.notMigrated)
      .map(
        (DemotedPlugin demoted) =>
            'Plugin ${demoted.plugin.name} is built from source because ${demoted.explanation}.',
      );

  /// Serializes the plan for the Gradle side.
  ///
  /// The settings plugin includes every entry in `subprojects` as a Gradle
  /// subproject, and the Flutter Gradle Plugin adds a dependency on the
  /// coordinate matching the build mode for every entry in `aar`. When this
  /// file is absent — for example when Gradle is invoked directly rather than
  /// through the Flutter tool — the Gradle side falls back to building every
  /// plugin as a subproject, which is the legacy behavior.
  Map<String, Object?> toJson({required String repositoryPath, List<String> repositories = const <String>[]}) {
    return <String, Object?>{
      'version': kAndroidPluginBuildPlanVersion,
      'repository': repositoryPath,
      'extraRepositories': repositories,
      'aar': <Object?>[
        for (final AarPlugin aar in aarPlugins)
          <String, Object?>{
            'name': aar.plugin.name,
            'groupId': kFlutterPluginGroupId,
            'debugGroupId': kFlutterPluginDebugGroupId,
            'version': aar.mavenVersion,
            'dev_dependency': aar.plugin.isDevDependency,
          },
      ],
      'subprojects': <Object?>[
        for (final DemotedPlugin demoted in demotedPlugins)
          <String, Object?>{
            'name': demoted.plugin.name,
            'path': demoted.plugin.path,
            'dev_dependency': demoted.plugin.isDevDependency,
            'reason': demoted.reason.name,
            if (demoted.culprit != null) 'culprit': demoted.culprit,
          },
      ],
    };
  }
}

/// Maps a pub version onto a Maven version string.
///
/// Pub build metadata (`1.2.3+4`) is rewritten to `1.2.3-4`, because Gradle
/// parses a trailing `+` in a version selector as a dynamic version and the
/// ambiguity is not worth carrying into published coordinates.
@visibleForTesting
String mavenVersionForPubVersion(String pubVersion) => pubVersion.replaceAll('+', '-');

/// Computes which plugins are consumed as AARs and which are built from source.
///
/// The demotion set is closed transitively: a plugin that depends on a plugin
/// built from source must also be built from source, because its AAR build
/// runs in isolation and has no way to resolve a source-built dependency.
/// Closing the set rather than reporting an error makes migration order
/// independent — leaf plugins benefit immediately and the benefit spreads
/// outward as the ecosystem migrates, with no cross-author coordination.
AndroidPluginBuildPlan computeAndroidPluginBuildPlan({
  required List<Plugin> plugins,
  required FileSystem fileSystem,
  bool aarBuildsEnabled = true,
  Set<String> forceAarPluginNames = const <String>{},
}) {
  final pluginsByName = <String, Plugin>{for (final Plugin plugin in plugins) plugin.name: plugin};
  final demotions = <String, DemotedPlugin>{};

  void demote(Plugin plugin, PluginDemotionReason reason, {String? culprit}) {
    demotions.putIfAbsent(
      plugin.name,
      () => DemotedPlugin(plugin: plugin, reason: reason, culprit: culprit),
    );
  }

  // Pass one: plugins that cannot be built in isolation on their own merits.
  for (final plugin in plugins) {
    if (!aarBuildsEnabled) {
      demote(plugin, PluginDemotionReason.disabled);
      continue;
    }
    // A path dependency is the signal that the developer is editing the
    // plugin, which is exactly when a cached prebuilt AAR is wrong. An
    // explicit opt back in covers monorepos full of path plugins that are
    // never actually edited.
    if (!plugin.source.isImmutable && !forceAarPluginNames.contains(plugin.name)) {
      demote(plugin, PluginDemotionReason.mutableSource);
      continue;
    }
    if (plugin.version == null) {
      demote(plugin, PluginDemotionReason.noVersion);
      continue;
    }
    if (!pluginOptedIntoAarBuilds(plugin, fileSystem)) {
      demote(plugin, PluginDemotionReason.notMigrated);
      continue;
    }
    if (pluginBuildsNativeCode(plugin, fileSystem)) {
      demote(plugin, PluginDemotionReason.nativeCode);
      continue;
    }
  }

  // Pass two: close the demotion set over the plugin dependency graph. Iterate
  // to a fixed point so that a chain A -> B -> C demotes all of A, B and C
  // regardless of the order `plugins` happens to be in.
  var changed = true;
  while (changed) {
    changed = false;
    for (final plugin in plugins) {
      if (demotions.containsKey(plugin.name)) {
        continue;
      }
      for (final String dependencyName in plugin.dependencies) {
        if (!pluginsByName.containsKey(dependencyName)) {
          // Not a plugin, so not part of the Android dependency graph.
          continue;
        }
        if (demotions.containsKey(dependencyName)) {
          demote(plugin, PluginDemotionReason.dependsOnDemoted, culprit: dependencyName);
          changed = true;
          break;
        }
      }
    }
  }

  final aarPlugins = <AarPlugin>[];
  final demotedPlugins = <DemotedPlugin>[];
  for (final plugin in plugins) {
    final DemotedPlugin? demoted = demotions[plugin.name];
    if (demoted != null) {
      demotedPlugins.add(demoted);
    } else {
      aarPlugins.add(
        AarPlugin(plugin: plugin, mavenVersion: mavenVersionForPubVersion(plugin.version!)),
      );
    }
  }
  return AndroidPluginBuildPlan(aarPlugins: aarPlugins, demotedPlugins: demotedPlugins);
}

/// Whether [plugin] has opted in to being built as an isolated AAR by setting
/// `flutter.plugin.migrated=true` in its `android/gradle.properties`.
@visibleForTesting
bool pluginOptedIntoAarBuilds(Plugin plugin, FileSystem fileSystem) {
  final File propertiesFile = fileSystem
      .directory(plugin.path)
      .childDirectory('android')
      .childFile('gradle.properties');
  if (!propertiesFile.existsSync()) {
    return false;
  }
  for (String line in propertiesFile.readAsLinesSync()) {
    line = line.trim();
    if (line.startsWith('#') || !line.contains('=')) {
      continue;
    }
    final int separator = line.indexOf('=');
    final String key = line.substring(0, separator).trim();
    final String value = line.substring(separator + 1).trim();
    if (key == 'flutter.plugin.migrated') {
      return value == 'true';
    }
  }
  return false;
}

/// Whether [plugin] compiles native code as part of its Android build.
///
/// Native plugins are excluded from AAR builds until unstripped `.so` symbols
/// can be published alongside the AAR and merged into the app's symbol bundle;
/// without that, `--split-debug-info` and Play Console symbol uploads would
/// silently lose plugin frames.
@visibleForTesting
bool pluginBuildsNativeCode(Plugin plugin, FileSystem fileSystem) {
  final Directory androidDirectory = fileSystem.directory(plugin.path).childDirectory('android');
  if (!androidDirectory.existsSync()) {
    return false;
  }
  if (androidDirectory.childFile('CMakeLists.txt').existsSync() ||
      androidDirectory.childDirectory('src').childDirectory('main').childDirectory('cpp').existsSync()) {
    return true;
  }
  for (final buildFileName in <String>['build.gradle', 'build.gradle.kts']) {
    final File buildFile = androidDirectory.childFile(buildFileName);
    if (buildFile.existsSync() && buildFile.readAsStringSync().contains('externalNativeBuild')) {
      return true;
    }
  }
  return false;
}

/// The inputs that determine the contents of a plugin's AAR.
///
/// Everything that can change the produced bytes belongs here, except what is
/// already implied by the cache entry's own coordinate:
///
///  * The plugin's `compileSdk`, `minSdk`, `targetSdk` and NDK version are
///    declared in the plugin's own build files, which are fixed for a given
///    immutable plugin version.
///  * The values vended by the `flutter` Gradle extension are fixed by
///    [flutterGradlePluginVersion].
///
/// Notably absent is the target platform: plugin AARs are always built for
/// every ABI and the app strips down to the ABIs it wants, so that changing
/// `--target-platform` does not invalidate cached plugin builds — and, more
/// importantly, so a single-ABI AAR is never cached under a key that claims to
/// be complete.
@immutable
class PluginAarBuildInputs {
  const PluginAarBuildInputs({
    required this.agpVersion,
    required this.gradleVersion,
    required this.kotlinVersion,
    required this.javaVersion,
    required this.engineVersion,
    required this.flutterGradlePluginVersion,
  });

  final String agpVersion;
  final String gradleVersion;
  final String kotlinVersion;
  final String javaVersion;
  final String engineVersion;

  /// Identifies the Flutter Gradle Plugin build logic and the SDK values it
  /// vends, so that changing SDK invalidates cached plugin AARs.
  final String flutterGradlePluginVersion;

  Map<String, Object?> toJson() => <String, Object?>{
    'agpVersion': agpVersion,
    'gradleVersion': gradleVersion,
    'kotlinVersion': kotlinVersion,
    'javaVersion': javaVersion,
    'engineVersion': engineVersion,
    'flutterGradlePluginVersion': flutterGradlePluginVersion,
  };

  /// A stable digest over every input, used as the cache directory name.
  String get digest {
    final Map<String, Object?> json = toJson();
    final List<String> keys = json.keys.toList()..sort();
    final String canonical = keys.map((String key) => '$key=${json[key]}').join('\n');
    return sha256.convert(utf8.encode(canonical)).toString().substring(0, 32);
  }
}

/// A content addressed, cross-project cache of built plugin AARs.
///
/// The cache is keyed on [PluginAarBuildInputs] and the plugin's immutable
/// name and version, so a given plugin version is compiled at most once per
/// machine per toolchain configuration, rather than once per clean build per
/// project as in the source-built model.
class PluginAarCache {
  PluginAarCache({required this.rootDirectory, required this.inputs});

  /// The root of the shared cache, typically `~/.flutter/plugin-aar-cache`.
  final Directory rootDirectory;

  final PluginAarBuildInputs inputs;

  Directory get _keyedRoot => rootDirectory.childDirectory(inputs.digest);

  /// The directory holding the published artifacts for [plugin], laid out as a
  /// Maven repository so that it can be materialized by copying.
  Directory directoryFor(AarPlugin plugin, {required bool debug}) {
    final String group = debug ? kFlutterPluginDebugGroupId : kFlutterPluginGroupId;
    return _keyedRoot
        .childDirectory(group.replaceAll('.', fileSystemSeparator))
        .childDirectory(plugin.plugin.name)
        .childDirectory(plugin.mavenVersion);
  }

  String get fileSystemSeparator => rootDirectory.fileSystem.path.separator;

  /// Whether a built AAR for [plugin] is already present.
  bool contains(AarPlugin plugin, {required bool debug}) {
    final Directory directory = directoryFor(plugin, debug: debug);
    if (!directory.existsSync()) {
      return false;
    }
    return directory
        .listSync()
        .whereType<File>()
        .any((File file) => file.basename.endsWith('.aar'));
  }

  /// Records the inputs that produced this cache entry, so that a stale or
  /// surprising cache hit can be diagnosed without recomputing the digest.
  void writeBuildInputsManifest() {
    final File manifest = _keyedRoot.childFile('build-inputs.json');
    if (manifest.existsSync()) {
      return;
    }
    manifest.parent.createSync(recursive: true);
    manifest.writeAsStringSync(const JsonEncoder.withIndent('  ').convert(inputs.toJson()));
  }
}
