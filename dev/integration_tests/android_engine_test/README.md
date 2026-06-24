# android_engine_test

This directory contains a sample app and tests that demonstrate how to use the
(experimental) _native_ Flutter Driver API to drive Flutter apps that run on
Android devices or emulators, interact with and capture screenshots of the app,
and compare the screenshots against golden images.

> [!CAUTION]
> This test suite is a _very_ end-to-end suite that is testing a combination of
> the graphics backend, the Android embedder, the Flutter framework, and Flutter
> tools, and only useful when the documentation and naming stays up to date and
> is clearly actionable.
>
> Please take extra care when updating the test suite to also update the README.

## How it runs on CI (LUCI)

See [`dev/bots/suite_runners/run_android_engine_tests.dart`](../../bots/suite_runners/run_android_engine_tests.dart), but tl;dr:

```sh
# TIP: If golden-files do not exist locally, this command will fail locally.
SHARD=android_engine_vulkan_tests bin/cache/dart-sdk/bin/dart dev/bots/test.dart
SHARD=android_engine_opengles_tests bin/cache/dart-sdk/bin/dart dev/bots/test.dart
```

The shard axis is the **Impeller backend** (`vulkan` / `opengles`), because the
backend is tied to the emulator image, while the platform-view composition mode
is just a flag. The Vulkan shard runs every mode (including HCPP); the OpenGLES
shard runs every mode except HCPP (HCPP requires Vulkan + API 34).

## Platform view modes

Platform view _functionality_ is written once and run under each composition
mode that supports it, rather than being copy-pasted per mode. See
[Android-Platform-Views.md](../../../docs/platforms/android/Android-Platform-Views.md)
for what the modes are.

- `lib/core/` — mode-agnostic functionality. Each app builds its platform view
  via `platformViewForMode()` and is run under HC / TLHC / HCPP. Scenarios that
  don't apply to a mode are skipped in the driver (e.g. HC can't apply
  clip/opacity/transform, and its screenshots are unstable on CI).
- `lib/hcpp_specific/` — tests that only make sense with HCPP (legacy-type
  upgrade, and the "HC/TLHC-fallback errors when HCPP is enabled" negative
  tests). Run under HCPP only.
- `lib/legacy_specific/` — Virtual Display smoke test. VD cannot be
  force-selected (it's only an engine fallback), so this is best-effort.

The mode is chosen by the suite runner and passed to the app via
`--dart-define=PV_MODE=<mode>` and to the driver via the `PV_MODE` environment
variable. Goldens are keyed per `(backend, mode)` via the Skia Gold name prefix
(`android_engine_test.<backend>.<mode>`), see
[`test_driver/_luci_skia_gold_prelude.dart`](test_driver/_luci_skia_gold_prelude.dart).

The capability matrix (which functionality runs under which modes) lives in
`_platformViewTests` in the suite runner.

## Running the apps and tests

Each `lib/**/*_main.dart` file is a standalone Flutter app that you can run on an
Android device or emulator. For platform-view apps, pass the mode you want
(defaults to TLHC if omitted):

```sh
# Run a core functionality app under a specific mode.
flutter run --dart-define=PV_MODE=tlhc lib/core/gradient_main.dart
flutter run --dart-define=PV_MODE=hcpp --enable-hcpp lib/core/gradient_main.dart

# Drive it (a local golden baseline is required first; see below).
flutter drive --dart-define=PV_MODE=tlhc lib/core/gradient_main.dart
```

Non-platform-view apps (run once, no mode):

- `flutter_rendered_blue_rectangle` — smoke test that Flutter runs and the native
  driver can screenshot and compare to a golden.
- `external_texture/surface_producer_smiley_face` — exercises the
  `SurfaceProducer` API end-to-end.
- `external_texture/surface_texture_smiley_face` — exercises the `SurfaceTexture`
  API end-to-end.

## Generating golden baselines locally

```sh
# Checkout HEAD, i.e. *before* the changes you want to test.
UPDATE_GOLDENS=1 flutter drive --dart-define=PV_MODE=tlhc lib/core/gradient_main.dart

# Make your changes, then run against the baseline.
flutter drive --dart-define=PV_MODE=tlhc lib/core/gradient_main.dart
```

Note: local golden _files_ are not mode-suffixed, so running different modes
locally overwrites the same file. The per-mode distinction only exists in Skia
Gold (via the name prefix) on CI.

## Deflaking

Use `tool/deflake.dart <path/to/lib/main.dart>` to, in 1-command:

- Build an APK.
- Establish a baseline set of golden-files locally.
- Run N tests (by default, 10) in the same state, asserting the same output.

For example:

```sh
dart tool/deflake.dart lib/core/gradient_main.dart
```

For more options, see `dart tool/deflake.dart --help`.
