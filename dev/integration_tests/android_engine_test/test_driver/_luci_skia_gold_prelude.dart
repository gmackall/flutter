// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:io' as io;

import 'package:android_engine_test/src/platform_view_mode.dart';

export 'package:android_engine_test/src/platform_view_mode.dart' show PvContent, PvMode;

/// Whether the current environment is LUCI.
bool get isLuci => io.Platform.environment['LUCI_CI'] == 'True';

/// The Impeller backend the suite runner selected for this run, or `null`.
///
/// One of `vulkan` or `opengles` on CI; unset for ad-hoc local runs.
String? get _impellerBackend {
  final String? value = io.Platform.environment['ANDROID_ENGINE_TEST_GOLDEN_VARIANT'];
  return (value == null || value.isEmpty) ? null : value;
}

/// The [PvMode] the suite runner selected for this run.
///
/// The runner passes `PV_MODE` to the *driver* process (this code) via the
/// environment and to the *app* via `--dart-define`. Defaults to [PvMode.tlhc]
/// for ad-hoc local runs (`flutter drive lib/core/..._main.dart`).
PvMode get currentMode {
  final String? value = io.Platform.environment['PV_MODE'];
  return (value == null || value.isEmpty) ? PvMode.tlhc : PvMode.parse(value);
}

/// The golden suffix for the current engine configuration.
///
/// Combines the Impeller backend and the platform view mode so each
/// `(backend, mode)` pair has its own golden image in Skia Gold, e.g.
/// `.vulkan.hcpp` or `.opengles.tlhc`. Empty for ad-hoc local runs with neither
/// variable set.
String get goldenVariant {
  final String? mode = io.Platform.environment['PV_MODE'];
  final parts = <String>[
    if (_impellerBackend != null) _impellerBackend!,
    if (mode != null && mode.isNotEmpty) mode,
  ];
  return parts.isEmpty ? '' : '.${parts.join('.')}';
}

/// Whether the current [PvMode] can apply framework-side compositing effects
/// (clipping, opacity, arbitrary transforms) to the platform view.
///
/// Only the texture-backed modes can: under [PvMode.hc] the view lives in the
/// native view hierarchy, and [PvMode.vd] does not support these scenarios in
/// this suite. Use this to `skip` such scenarios in shared `core/` tests:
///
/// ```dart
/// test('should clip', () { ... }, skip: skipIfNoFrameworkCompositing);
/// ```
Object? get skipIfNoFrameworkCompositing => switch (currentMode) {
  PvMode.tlhc || PvMode.hcpp => false,
  PvMode.hc => 'Not applicable: HC composites in the native view hierarchy.',
  PvMode.vd => 'Not applicable in Virtual Display for this suite.',
};
