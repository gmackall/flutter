# Platform view test refactor — verification checklist

The code migration for https://github.com/flutter/flutter/issues/182564 is
complete: every platform-view functionality is written once and run across the
modes it supports (see `_platformViewTests` in
`dev/bots/suite_runners/run_android_engine_tests.dart`).

> **Delete this file once the verification below passes and the goldens land.**

## Layout

- `lib/src/platform_view_mode.dart` — `PvMode` / `PvContent`.
- `lib/src/platform_view_factory.dart` — `platformViewForMode()`.
- `lib/core/` — mode-agnostic functionality (run under HC/TLHC/HCPP; framework-
  compositing ones under TLHC/HCPP only).
- `lib/hcpp_specific/` — HCPP-only tests.
- `lib/legacy_specific/` — Virtual Display smoke test.
- `test_driver/` mirrors `lib/` (driver = `<app_basename>_test.dart`).

## Verification (needs a device/emulator — not done by the author)

1. `flutter analyze` in this package, and `dart analyze` in `dev/bots`.
2. Regenerate goldens per backend (each run covers all of that backend's modes):
   ```sh
   SHARD=android_engine_vulkan_tests   UPDATE_GOLDENS=1 bin/cache/dart-sdk/bin/dart dev/bots/test.dart
   SHARD=android_engine_opengles_tests UPDATE_GOLDENS=1 bin/cache/dart-sdk/bin/dart dev/bots/test.dart
   ```
3. Re-run without `UPDATE_GOLDENS` to confirm green, then triage the new images
   in Skia Gold.

## Things to sanity-check during verification

- Core functionality now uses different *content* than some originals (e.g. the
  old `hcpp/platform_view_main` screenshotted a `box`; `core/gradient` uses the
  gradient). Goldens are new regardless.
- HC runs only the non-screenshot, non-compositing core scenarios (the rest are
  skipped via `skipIfNoFrameworkCompositing` / the `#165032` screenshot skip).
- HCPP content mapping picks the SurfaceView-backed gradient; box and
  changing_color_button have no SurfaceView variant but are plain Views that HCPP
  upgrades (as in the original tests).
- The Vulkan shard timeout (60 min) now does more `flutter drive` runs; if it's
  tight, split the Vulkan shard into hcpp/legacy lanes (the runner is already
  mode-aware).
