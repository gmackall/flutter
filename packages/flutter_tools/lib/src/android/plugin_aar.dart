// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:crypto/crypto.dart';
import 'package:meta/meta.dart';

import '../base/file_system.dart';
import '../base/io.dart';
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

/// The build inputs that come from the app side and are shared by every plugin
/// AAR built for it.
///
/// The app supplies the Gradle distribution (its own wrapper runs the plugin
/// builds), the JVM, the engine the plugin compiles against, and the Flutter
/// Gradle Plugin logic that configures the build. Everything else that shapes
/// the output belongs to the plugin, not the app — see [PluginToolchain].
///
/// Notably absent is the target platform: plugin AARs are always built for
/// every ABI and the app strips down to the ABIs it wants, so that changing
/// `--target-platform` does not invalidate cached plugin builds — and, more
/// importantly, so a single-ABI AAR is never cached under a key that claims to
/// be complete.
@immutable
class PluginAarBuildInputs {
  const PluginAarBuildInputs({
    required this.gradleVersion,
    required this.javaVersion,
    required this.engineVersion,
    required this.flutterGradlePluginVersion,
  });

  final String gradleVersion;
  final String javaVersion;
  final String engineVersion;

  /// Identifies the Flutter Gradle Plugin build logic and the SDK values it
  /// vends, so that changing SDK invalidates cached plugin AARs.
  final String flutterGradlePluginVersion;

  Map<String, Object?> toJson() => <String, Object?>{
    'gradleVersion': gradleVersion,
    'javaVersion': javaVersion,
    'engineVersion': engineVersion,
    'flutterGradlePluginVersion': flutterGradlePluginVersion,
  };
}

/// The Android build tool versions a plugin's own build files select.
///
/// A migrated plugin pins its own AGP and Kotlin versions — decoupling them
/// from the app's is the entire point of building the plugin in isolation — so
/// these are properties of the plugin, not of the app consuming it. Keying a
/// cache entry on the *app's* AGP would rebuild an identical AAR once per
/// distinct app toolchain on the machine, which is exactly the duplication the
/// shared cache exists to remove.
///
/// These values are *predicted* by parsing the plugin's build files, and the
/// plugin build reports back what it actually resolved. See
/// [PluginAarCache.verifyToolchain].
@immutable
class PluginToolchain {
  const PluginToolchain({required this.agpVersion, required this.kotlinVersion});

  factory PluginToolchain.fromJson(Map<String, Object?> json) => PluginToolchain(
    agpVersion: json['agpVersion'] as String? ?? 'unknown',
    kotlinVersion: json['kotlinVersion'] as String? ?? 'unknown',
  );

  final String agpVersion;
  final String kotlinVersion;

  Map<String, Object?> toJson() => <String, Object?>{
    'agpVersion': agpVersion,
    'kotlinVersion': kotlinVersion,
  };

  @override
  bool operator ==(Object other) =>
      other is PluginToolchain &&
      other.agpVersion == agpVersion &&
      other.kotlinVersion == kotlinVersion;

  @override
  int get hashCode => Object.hash(agpVersion, kotlinVersion);

  @override
  String toString() => 'AGP $agpVersion, Kotlin $kotlinVersion';
}

/// The full identity of a cached plugin AAR: the app-side inputs plus the
/// plugin's own toolchain.
@immutable
class PluginAarKey {
  const PluginAarKey({required this.inputs, required this.toolchain});

  final PluginAarBuildInputs inputs;
  final PluginToolchain toolchain;

  Map<String, Object?> toJson() => <String, Object?>{
    ...inputs.toJson(),
    ...toolchain.toJson(),
  };

  /// A stable digest over every input, used as a cache directory name.
  String get digest {
    final Map<String, Object?> json = toJson();
    final List<String> keys = json.keys.toList()..sort();
    final String canonical = keys.map((String key) => '$key=${json[key]}').join('\n');
    return sha256.convert(utf8.encode(canonical)).toString().substring(0, 32);
  }
}

/// A content addressed, cross-project cache of built plugin AARs.
///
/// Entries are keyed per plugin, on the plugin's immutable name and version
/// plus a [PluginAarKey] combining the app-side inputs with the plugin's own
/// toolchain. Keying per plugin rather than per build is what lets one machine
/// compile a given plugin version once and reuse it from every project,
/// including projects whose own AGP or Kotlin versions differ.
///
/// The cache is deliberately *not* laid out as a Maven repository. Plugin
/// builds resolve their siblings from the project-local repository, which the
/// tool populates in dependency order, so the cache is free to key each plugin
/// independently.
class PluginAarCache {
  PluginAarCache({required this.rootDirectory, required this.inputs});

  /// The default cap on the total size of the cache, beyond which the least
  /// recently used entries are evicted.
  static const int defaultMaxSizeBytes = 10 * 1024 * 1024 * 1024;

  /// The name of the manifest recording what actually produced an entry.
  static const toolchainManifestName = 'flutter-plugin-toolchain.json';

  /// The root of the shared cache, typically `~/.flutter/plugin-aar-cache`.
  final Directory rootDirectory;

  final PluginAarBuildInputs inputs;

  /// The directory holding the built artifacts for [plugin].
  ///
  /// [toolchain] is the plugin's predicted AGP and Kotlin versions, which form
  /// part of the key so that a plugin whose own toolchain changes gets its own
  /// entry rather than silently reusing one built with different tools.
  Directory directoryFor(
    AarPlugin plugin, {
    required bool debug,
    required PluginToolchain toolchain,
  }) {
    return rootDirectory
        .childDirectory(plugin.plugin.name)
        .childDirectory(plugin.mavenVersion)
        .childDirectory(PluginAarKey(inputs: inputs, toolchain: toolchain).digest)
        .childDirectory(debug ? 'debug' : 'release');
  }

  /// The path of [plugin] relative to a Maven repository root.
  ///
  /// Note the group id expands to several path segments, which is why callers
  /// must never try to recover a repository root by walking back up a fixed
  /// number of parents from an artifact directory.
  String relativeArtifactPath(AarPlugin plugin, {required bool debug}) {
    final String group = debug ? kFlutterPluginDebugGroupId : kFlutterPluginGroupId;
    return rootDirectory.fileSystem.path.joinAll(<String>[
      ...group.split('.'),
      plugin.plugin.name,
      plugin.mavenVersion,
    ]);
  }

  /// Whether a usable AAR for [plugin] is already cached.
  bool contains(AarPlugin plugin, {required bool debug, required PluginToolchain toolchain}) {
    final Directory directory = directoryFor(plugin, debug: debug, toolchain: toolchain);
    if (!directory.existsSync()) {
      return false;
    }
    return directory
        .listSync()
        .whereType<File>()
        .any((File file) => file.basename.endsWith('.aar'));
  }

  /// The toolchain a cached entry reports having actually been built with, or
  /// null when the entry predates the manifest or is unreadable.
  PluginToolchain? recordedToolchain(
    AarPlugin plugin, {
    required bool debug,
    required PluginToolchain toolchain,
  }) {
    final File manifest = directoryFor(
      plugin,
      debug: debug,
      toolchain: toolchain,
    ).childFile(toolchainManifestName);
    if (!manifest.existsSync()) {
      return null;
    }
    try {
      final Object? decoded = json.decode(manifest.readAsStringSync());
      if (decoded is! Map<String, Object?>) {
        return null;
      }
      return PluginToolchain.fromJson(decoded);
    } on FormatException {
      return null;
    }
  }

  /// Whether a cache entry was built with the toolchain its key claims.
  ///
  /// The key is built from versions *predicted* by parsing the plugin's build
  /// files, which cannot account for a plugin that selects its AGP or Kotlin
  /// version dynamically — from an environment variable, an injected property,
  /// or a resolution strategy. The plugin build reports back what it actually
  /// resolved, and a mismatch means the prediction is unreliable for this
  /// plugin, so the entry must not be trusted.
  ///
  /// Returns null when the entry carries no manifest, which callers should
  /// treat the same as a mismatch rather than as a pass.
  bool? verifyToolchain(
    AarPlugin plugin, {
    required bool debug,
    required PluginToolchain toolchain,
  }) {
    final PluginToolchain? recorded = recordedToolchain(
      plugin,
      debug: debug,
      toolchain: toolchain,
    );
    if (recorded == null) {
      return null;
    }
    return recorded == toolchain;
  }

  /// Records the app-side inputs that produced this cache entry, so that a
  /// stale or surprising cache hit can be diagnosed without recomputing digests.
  void writeBuildInputsManifest(AarPlugin plugin, {required PluginToolchain toolchain}) {
    final File manifest = rootDirectory
        .childDirectory(plugin.plugin.name)
        .childDirectory(plugin.mavenVersion)
        .childDirectory(PluginAarKey(inputs: inputs, toolchain: toolchain).digest)
        .childFile('build-inputs.json');
    if (manifest.existsSync()) {
      return;
    }
    manifest.parent.createSync(recursive: true);
    manifest.writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert(
        PluginAarKey(inputs: inputs, toolchain: toolchain).toJson(),
      ),
    );
  }

  /// A private directory for a build to publish into before its output is
  /// moved into the shared cache.
  ///
  /// Concurrent builds — a CI matrix running several `flutter build`
  /// invocations at once is the common case — must never let a reader observe
  /// a half written artifact directory. Each build publishes into its own
  /// staging directory and the finished artifact directory is moved into place
  /// in one step. The staging directory lives under [rootDirectory] so that the
  /// move stays within one filesystem and is therefore atomic.
  Directory createStagingDirectory() {
    final Directory staging = rootDirectory.childDirectory(
      '.staging-$pid-${DateTime.now().microsecondsSinceEpoch}',
    );
    staging.createSync(recursive: true);
    return staging;
  }

  /// Moves a finished artifact directory out of [staging] and into the cache.
  ///
  /// [toolchain] must be what the plugin build *actually* reported using, not
  /// what was predicted, so that an entry is always filed under the tools that
  /// really produced it. When the two differ the prediction was wrong, the
  /// predicted entry is left empty, and the next build takes the same miss —
  /// correct, if slow, and [verifyToolchain] is what surfaces it.
  ///
  /// Returns `true` if this build's output was the one installed. A `false`
  /// result means another build produced the same artifact first, which is not
  /// an error: the entry is keyed on inputs that fully determine the contents,
  /// so either copy is equally valid and the existing one is kept.
  bool installFromStaging(
    Directory staging,
    AarPlugin plugin, {
    required bool debug,
    required PluginToolchain toolchain,
    bool replace = false,
  }) {
    final Directory built = staging.childDirectory(relativeArtifactPath(plugin, debug: debug));
    if (!built.existsSync()) {
      return false;
    }
    built.childFile(toolchainManifestName).writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert(toolchain.toJson()),
    );
    final Directory destination = directoryFor(plugin, debug: debug, toolchain: toolchain);
    if (destination.existsSync()) {
      if (!replace) {
        return false;
      }
      destination.deleteSync(recursive: true);
    }
    destination.parent.createSync(recursive: true);
    try {
      built.renameSync(destination.path);
      return true;
    } on FileSystemException {
      // Another build won the race between the check above and the rename.
      return false;
    }
  }

  /// The name of the marker recording when an entry was last used for a build.
  ///
  /// Recency is tracked explicitly rather than read from filesystem access
  /// times, which are unreliable: `noatime` and `relatime` mounts are common on
  /// Linux, and under them an entry read on every single build would still look
  /// untouched and be evicted first.
  static const lastUsedMarkerName = '.last-used';

  /// Records that an entry was used for a build, for eviction ordering.
  void markUsed(AarPlugin plugin, DateTime now, {required PluginToolchain toolchain}) {
    final File marker = rootDirectory
        .childDirectory(plugin.plugin.name)
        .childDirectory(plugin.mavenVersion)
        .childDirectory(PluginAarKey(inputs: inputs, toolchain: toolchain).digest)
        .childFile(lastUsedMarkerName);
    marker.parent.createSync(recursive: true);
    marker.writeAsStringSync(now.toUtc().toIso8601String());
  }

  /// Every `<name>/<version>/<digest>` entry currently in the cache.
  Iterable<Directory> _entries() sync* {
    for (final FileSystemEntity name in rootDirectory.listSync()) {
      if (name is! Directory || name.basename.startsWith('.staging-')) {
        continue;
      }
      for (final FileSystemEntity version in name.listSync()) {
        if (version is! Directory) {
          continue;
        }
        for (final FileSystemEntity digest in version.listSync()) {
          if (digest is Directory) {
            yield digest;
          }
        }
      }
    }
  }

  /// When [entry] was last used, or the epoch when there is no usable marker,
  /// so entries written before markers existed are the first to be evicted.
  DateTime _readLastUsed(Directory entry) {
    final File marker = entry.childFile(lastUsedMarkerName);
    if (!marker.existsSync()) {
      return DateTime.fromMillisecondsSinceEpoch(0);
    }
    return DateTime.tryParse(marker.readAsStringSync().trim()) ??
        DateTime.fromMillisecondsSinceEpoch(0);
  }

  /// Evicts whole cache entries, least recently used first, until the cache is
  /// no larger than [maxSizeBytes].
  ///
  /// An entry is one plugin version built with one toolchain, so eviction drops
  /// exactly the combinations that stopped being useful — an old plugin
  /// version, or an old AGP — without disturbing the rest.
  void evictLeastRecentlyUsed({
    int maxSizeBytes = defaultMaxSizeBytes,
    Set<String> keep = const <String>{},
  }) {
    if (!rootDirectory.existsSync()) {
      return;
    }
    final entries = <(Directory, DateTime, int)>[];
    var totalBytes = 0;
    for (final Directory entity in _entries()) {
      var size = 0;
      DateTime lastUsed;
      try {
        for (final FileSystemEntity child in entity.listSync(recursive: true)) {
          if (child is File) {
            size += child.lengthSync();
          }
        }
        lastUsed = _readLastUsed(entity);
      } on FileSystemException {
        // The entry is being written or removed by another build; leave it be.
        continue;
      }
      totalBytes += size;
      entries.add((entity, lastUsed, size));
    }
    if (totalBytes <= maxSizeBytes) {
      return;
    }
    entries.sort(((Directory, DateTime, int) a, (Directory, DateTime, int) b) => a.$2.compareTo(b.$2));
    for (final (Directory directory, _, int size) in entries) {
      if (totalBytes <= maxSizeBytes) {
        return;
      }
      // Never evict an entry this build is using.
      if (keep.contains(directory.path)) {
        continue;
      }
      try {
        directory.deleteSync(recursive: true);
        totalBytes -= size;
      } on FileSystemException {
        // In use by a concurrent build; skip it.
      }
    }
  }
}
