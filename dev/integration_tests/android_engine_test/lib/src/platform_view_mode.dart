// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/// The Android platform view composition modes exercised by this test suite.
///
/// A single "functionality" test (e.g. "render a gradient", "rotate", "clip")
/// is written once and run under each [PvMode] that supports it, instead of
/// being copy-pasted per mode. See `README.md` and
/// `dev/bots/suite_runners/run_android_engine_tests.dart` for how the modes map
/// onto CI shards.
///
/// See also:
/// <https://github.com/flutter/flutter/blob/main/docs/platforms/android/Android-Platform-Views.md>
enum PvMode {
  /// Virtual Display.
  ///
  /// The legacy fallback path. There is **no public API to force Virtual
  /// Display** — the engine only selects it as a fallback when Texture Layer
  /// Hybrid Composition is unsupported for a view. The factory therefore cannot
  /// guarantee this mode; it is only used by best-effort legacy smoke tests.
  vd,

  /// Hybrid Composition.
  ///
  /// The platform view is composited in the native Android view hierarchy
  /// (via [PlatformViewsService.initExpensiveAndroidView]). Because Flutter does
  /// not render the view into a texture, framework-side effects such as
  /// clipping, opacity, and arbitrary transforms cannot be applied to it; tests
  /// covering those scenarios are skipped under this mode.
  hc,

  /// Texture Layer Hybrid Composition.
  ///
  /// The default texture-backed path (via the [AndroidView] widget /
  /// [PlatformViewsService.initAndroidView]). The view is rendered into a
  /// texture, so framework-side clip/opacity/transform effects apply.
  tlhc,

  /// Hybrid Composition++ (SurfaceControl-backed).
  ///
  /// Uses [PlatformViewsService.initHybridAndroidView]. Only supported on
  /// devices running Vulkan on API 34+, so this mode is only exercised on the
  /// Vulkan CI shard.
  hcpp;

  /// Parses a [PvMode] from its [name] (e.g. `'hcpp'`).
  ///
  /// Throws an [ArgumentError] for an unknown value rather than silently
  /// defaulting, so a typo in the runner or a `--dart-define` fails loudly.
  static PvMode parse(String value) {
    for (final PvMode mode in PvMode.values) {
      if (mode.name == value) {
        return mode;
      }
    }
    throw ArgumentError.value(value, 'value', 'Not a known PvMode');
  }

  /// The mode the *app* was compiled for, read from the
  /// `--dart-define=PV_MODE=<name>` passed by the suite runner.
  ///
  /// Defaults to [PvMode.tlhc] so the apps remain runnable by hand
  /// (`flutter run lib/core/..._main.dart`) without supplying the define.
  static PvMode fromDartDefine() {
    const String name = String.fromEnvironment('PV_MODE', defaultValue: 'tlhc');
    return parse(name);
  }
}

/// The logical content rendered by a platform view, independent of [PvMode].
///
/// The concrete native view factory used for a given content depends on the
/// mode (see `platform_view_factory.dart`): SurfaceView-backed modes ([PvMode.hcpp],
/// [PvMode.vd]) require a `SurfaceView` factory, while texture-backed modes
/// ([PvMode.tlhc], [PvMode.hc]) use a plain `View` factory. Content that has no
/// SurfaceView variant registered on the native side cannot run in
/// SurfaceView-backed modes.
enum PvContent {
  /// A blue -> orange gradient. Registered on the native side in **both**
  /// `View` and `SurfaceView` variants, so it can run in every [PvMode].
  blueOrangeGradient,

  /// A solid box (plain `View` only).
  box,

  /// A button that changes color when tapped natively (plain `View` only).
  changingColorButton,
}
