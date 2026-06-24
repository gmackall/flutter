// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:file/file.dart';
import 'package:file/local.dart';
import 'package:glob/glob.dart';
import 'package:glob/list_local_fs.dart';
import 'package:path/path.dart' as path;

import '../run_command.dart';
import '../utils.dart';

/// To run this test locally:
///
/// 1. Connect an Android device or emulator.
/// 2. Run `dart pub get` in dev/bots
/// 3. Run the following command from the root of the Flutter repository:
///
/// ```sh
/// # Generate a baseline of local golden files.
/// SHARD=android_engine_vulkan_tests UPDATE_GOLDENS=1 bin/cache/dart-sdk/bin/dart dev/bots/test.dart
/// ```
///
/// 4. Then, re-run the command against the baseline images:
///
/// ```sh
/// SHARD=android_engine_vulkan_tests bin/cache/dart-sdk/bin/dart dev/bots/test.dart
/// ```
///
/// If you are trying to debug a commit, you will want to run step (3) first,
/// then apply the commit (or flag), and then run step (4). If you are trying
/// to determine flakiness in the *same* state, or want better debugging, see
/// `dev/integration_tests/android_engine_test/README.md`.
///
/// ## Platform view modes
///
/// Platform view functionality tests live under `lib/core`, `lib/hcpp_specific`,
/// and `lib/legacy_specific`. Each "core" functionality is written once and run
/// under every [PvMode] that supports it (see [_platformViewTests]). The mode is
/// passed to the app via `--dart-define=PV_MODE` and to the driver via the
/// `PV_MODE` environment variable, so goldens are keyed per `(backend, mode)`.
///
/// HCPP requires Vulkan + API 34, so it is only exercised on the Vulkan shard.
Future<void> runAndroidEngineTests({required ImpellerBackend impellerBackend}) async {
  print('Running Flutter Driver Android tests (backend=$impellerBackend)');

  final String androidEngineTestPath = path.join('dev', 'integration_tests', 'android_engine_test');
  final List<FileSystemEntity> allMains = Glob(
    '$androidEngineTestPath/lib/**_main.dart',
  ).listSync();

  final File androidManifestXml = const LocalFileSystem().file(
    path.join(androidEngineTestPath, 'android', 'app', 'src', 'main', 'AndroidManifest.xml'),
  );
  final String androidManifestContents = androidManifestXml.readAsStringSync();

  try {
    // Replace whatever the current backend is with the specified backend.
    final impellerBackendMetadata = RegExp(_impellerBackendMetadata(value: '.*'));
    androidManifestXml.writeAsStringSync(
      androidManifestContents.replaceFirst(
        impellerBackendMetadata,
        _impellerBackendMetadata(value: impellerBackend.name),
      ),
    );

    // Runs a single `flutter drive` for [relativeMainPath]. When [mode] is set,
    // the composition mode is forwarded to both the app (`--dart-define`) and
    // the driver (environment), and goldens are tagged with it via the driver's
    // `goldenVariant`.
    Future<void> runTest(
      String relativeMainPath, {
      PvMode? mode,
      bool useHCPPFlag = false,
    }) async {
      final CommandResult result = await runCommand(
        'flutter',
        <String>[
          'drive',
          relativeMainPath,
          // There are no reason to enable development flags for this test.
          // Disable them to work around flakiness issues, and in general just
          // make less things start up unnecessarily.
          '--no-dds',
          '--no-enable-dart-profiling',
          if (mode != null) '--dart-define=PV_MODE=${mode.name}',
          if (useHCPPFlag) '--enable-hcpp',
          '--test-arguments=test',
          '--test-arguments=--reporter=expanded',
        ],
        workingDirectory: androidEngineTestPath,
        environment: <String, String>{
          'ANDROID_ENGINE_TEST_GOLDEN_VARIANT': impellerBackend.name,
          if (mode != null) 'PV_MODE': mode.name,
        },
      );
      final String? stdout = result.flattenedStdout;
      if (stdout == null) {
        foundError(<String>['No stdout produced.']);
        return;
      }

      // TODO(matanlurey): Enable once `flutter drive` retains error logs.
      // https://github.com/flutter/flutter/issues/162087.
    }

    // Whether the app for [test] has been migrated/exists yet. Lets the registry
    // describe the full matrix while migration is in progress; missing apps are
    // skipped with a warning rather than failing the shard.
    bool exists(_PvTest test) {
      final bool found = allMains.any(
        (FileSystemEntity f) =>
            path.equals(f.path, path.join(androidEngineTestPath, test.mainPath)),
      );
      if (!found) {
        print('WARNING: skipping ${test.mainPath} (file not found).');
      }
      return found;
    }

    // 1. Non-platform-view apps (external textures, blue rectangle, etc.) run
    //    once, with no mode.
    for (final FileSystemEntity file in allMains) {
      if (_isPlatformViewMain(file.path, androidEngineTestPath)) {
        continue;
      }
      await runTest(path.relative(file.path, from: androidEngineTestPath));
    }

    // 2. Platform-view tests under the non-HCPP modes (manifest HCPP disabled).
    for (final _PvTest test in _platformViewTests) {
      if (!exists(test)) {
        continue;
      }
      for (final PvMode mode in test.modes) {
        if (mode == PvMode.hcpp) {
          continue; // Handled in the Vulkan-only HCPP phases below.
        }
        await runTest(test.mainPath, mode: mode);
      }
    }

    // HCPP requires Vulkan + API 34, so only exercise it on the Vulkan shard.
    if (impellerBackend == ImpellerBackend.vulkan) {
      // 3. HCPP tests that exercise the `--enable-hcpp` *flag* (not the
      //    manifest) must run while the manifest is still disabled.
      for (final _PvTest test in _platformViewTests) {
        if (!test.modes.contains(PvMode.hcpp) || !test.hcppViaFlagOnly) {
          continue;
        }
        if (!exists(test)) {
          continue;
        }
        await runTest(test.mainPath, mode: PvMode.hcpp, useHCPPFlag: true);
      }

      // 4. Remaining HCPP tests run with the manifest flag enabled.
      androidManifestXml.writeAsStringSync(
        androidManifestXml.readAsStringSync().replaceFirst(
          kHcppMetadataDisabled,
          kHcppMetadataEnabled,
        ),
      );
      for (final _PvTest test in _platformViewTests) {
        if (!test.modes.contains(PvMode.hcpp) || test.hcppViaFlagOnly) {
          continue;
        }
        if (!exists(test)) {
          continue;
        }
        await runTest(test.mainPath, mode: PvMode.hcpp);
      }
    }
  } finally {
    // Restore original contents.
    androidManifestXml.writeAsStringSync(androidManifestContents);
  }
}

/// Whether [mainPath] is one of the mode-parameterized platform view apps (and
/// therefore driven by [_platformViewTests] rather than the generic once-each
/// loop).
bool _isPlatformViewMain(String filePath, String androidEngineTestPath) {
  final String rel = path
      .relative(filePath, from: androidEngineTestPath)
      .replaceAll(r'\', '/');
  const List<String> platformViewDirs = <String>[
    'lib/core/',
    'lib/hcpp_specific/',
    'lib/legacy_specific/',
  ];
  return platformViewDirs.any(rel.startsWith);
}

/// The platform view composition modes. Mirrors `PvMode` in the test app's
/// `lib/src/platform_view_mode.dart`; kept in sync by name.
enum PvMode { vd, hc, tlhc, hcpp }

/// A platform view functionality test and the [PvMode]s it should run under.
class _PvTest {
  const _PvTest(this.mainPath, this.modes, {this.hcppViaFlagOnly = false});

  /// Path to the app entrypoint, relative to the test package root.
  final String mainPath;

  /// The modes this functionality is run under. HCPP entries only run on the
  /// Vulkan shard.
  final Set<PvMode> modes;

  /// When true, the HCPP run uses the `--enable-hcpp` flag with the manifest
  /// *disabled* (to validate the flag path / legacy-type upgrade), instead of
  /// the manifest meta-data.
  final bool hcppViaFlagOnly;
}

/// The capability matrix.
///
/// * `core/` functionality that is purely mode-agnostic runs under
///   {hc, tlhc, hcpp}. Scenarios that don't apply to a mode are skipped inside
///   the driver (e.g. HC screenshots).
/// * `core/` functionality that relies on framework-side compositing
///   (clip/opacity/transform/overlay) runs under {tlhc, hcpp} only — HC
///   composites in the native hierarchy where these can't be applied.
/// * `hcpp_specific/` tests run under {hcpp} only.
/// * `legacy_specific/` tests run under {vd} only.
const List<_PvTest> _platformViewTests = <_PvTest>[
  // Mode-agnostic core.
  _PvTest('lib/core/gradient_main.dart', <PvMode>{PvMode.hc, PvMode.tlhc, PvMode.hcpp}),
  _PvTest('lib/core/hide_show_hide_main.dart', <PvMode>{PvMode.hc, PvMode.tlhc, PvMode.hcpp}),
  _PvTest('lib/core/tap_color_change_main.dart', <PvMode>{PvMode.hc, PvMode.tlhc, PvMode.hcpp}),

  // Core requiring framework-side compositing (not applicable to HC).
  _PvTest('lib/core/transform_main.dart', <PvMode>{PvMode.tlhc, PvMode.hcpp}),
  _PvTest('lib/core/clippath_main.dart', <PvMode>{PvMode.tlhc, PvMode.hcpp}),
  _PvTest('lib/core/opacity_main.dart', <PvMode>{PvMode.tlhc, PvMode.hcpp}),
  _PvTest('lib/core/clear_hidden_main.dart', <PvMode>{PvMode.tlhc, PvMode.hcpp}),
  _PvTest('lib/core/overlay_layer_cleared_main.dart', <PvMode>{PvMode.tlhc, PvMode.hcpp}),
  _PvTest('lib/core/overlapping_main.dart', <PvMode>{PvMode.tlhc, PvMode.hcpp}),
  _PvTest('lib/core/rtl_mirror_main.dart', <PvMode>{PvMode.tlhc, PvMode.hcpp}),

  // HCPP-specific.
  _PvTest(
    'lib/hcpp_specific/upgrade_legacy_pv_types_main.dart',
    <PvMode>{PvMode.hcpp},
    hcppViaFlagOnly: true,
  ),
  _PvTest('lib/hcpp_specific/cliprect_surfaceview_main.dart', <PvMode>{PvMode.hcpp}),
  _PvTest('lib/hcpp_specific/hc_errors_with_hcpp_enabled_main.dart', <PvMode>{PvMode.hcpp}),
  _PvTest(
    'lib/hcpp_specific/tlhc_with_fallback_to_hc_errors_main.dart',
    <PvMode>{PvMode.hcpp},
  ),

  // Legacy-specific (Virtual Display smoke test; VD cannot be force-selected, so
  // this is best-effort — see PvMode.vd).
  _PvTest('lib/legacy_specific/virtual_display_gradient_main.dart', <PvMode>{PvMode.vd}),
];

const String kHcppMetadataDisabled =
    '<meta-data android:name="io.flutter.embedding.android.EnableHcpp" android:value="false" />';
const String kHcppMetadataEnabled =
    '<meta-data android:name="io.flutter.embedding.android.EnableHcpp" android:value="true" />';

String _impellerBackendMetadata({required String value}) {
  return '<meta-data android:name="io.flutter.embedding.android.ImpellerBackend" android:value="$value" />';
}

enum ImpellerBackend { vulkan, opengles }
