# Platform view test refactor — migration tracker

Tracks the refactor for https://github.com/flutter/flutter/issues/182564:
write each platform-view functionality **once** and run it under every
composition mode it supports (HCPP / TLHC / HC / VD), instead of copy-pasting a
near-identical test per mode.

> **Delete this file when the migration is complete.**

## Design (landed)

- `lib/src/platform_view_mode.dart` — `PvMode` (vd/hc/tlhc/hcpp) + `PvContent`.
  The app reads its mode from `--dart-define=PV_MODE`.
- `lib/src/platform_view_factory.dart` — `platformViewForMode(mode, content:)`
  centralizes per-mode construction and maps `(mode, content)` to the right
  native view type (SurfaceView-backed for HCPP/VD).
- `test_driver/_luci_skia_gold_prelude.dart` — `goldenVariant` folds in
  `PV_MODE` so goldens are keyed per `(backend, mode)`; `currentMode` +
  `skipIfNoFrameworkCompositing` for per-mode scenario skipping.
- `dev/bots/suite_runners/run_android_engine_tests.dart` — mode-aware runner
  driven by the `_platformViewTests` capability matrix. HCPP only runs on the
  Vulkan shard. **Registry entries whose app file does not exist yet are skipped
  with a warning**, so the matrix can be filled in incrementally.

### CI shards (unchanged)

Backend stays the shard axis (`android_engine_vulkan_tests`,
`android_engine_opengles_tests`) because backend is emulator-bound while mode is
a flag. The vulkan shard runs all modes incl. HCPP; the opengles shard runs all
modes except HCPP. No `.ci.yaml` change required (watch the 60-min timeout — if
it's tight, split the vulkan shard into hcpp/legacy later; the runner is already
mode-aware).

### Capability matrix

| Functionality              | Modes              | Bucket          |
|----------------------------|--------------------|-----------------|
| gradient                   | hc, tlhc, hcpp     | core ✅ done    |
| hide_show_hide             | hc, tlhc, hcpp     | core ✅ done    |
| tap_color_change           | hc, tlhc, hcpp     | core ✅ done    |
| transform                  | tlhc, hcpp         | core ⏳         |
| clippath                   | tlhc, hcpp         | core ⏳         |
| opacity                    | tlhc, hcpp         | core ⏳         |
| clear_hidden               | tlhc, hcpp         | core ⏳         |
| overlay_layer_cleared      | tlhc, hcpp         | core ⏳         |
| overlapping                | tlhc, hcpp         | core ⏳         |
| rtl_mirror                 | tlhc, hcpp         | core ⏳         |
| upgrade_legacy_pv_types    | hcpp (flag-only)   | hcpp_specific ⏳|
| cliprect_surfaceview       | hcpp               | hcpp_specific ⏳|
| hc_errors_with_hcpp        | hcpp               | hcpp_specific ⏳|
| tlhc_fallback_to_hc_errors | hcpp               | hcpp_specific ⏳|
| virtual_display gradient   | vd                 | legacy_specific ⏳|

Framework-compositing functionality (clip/opacity/transform/overlay) excludes HC
because HC composites in the native view hierarchy where those effects can't be
applied — that's why those rows are `{tlhc, hcpp}` rather than including hc.

## Remaining migrations (mechanical — follow the `core/gradient` pattern)

For each, the inline `_HybridComposition…` widget is replaced with
`platformViewForMode(PvMode.fromDartDefine(), content: PvContent.box)` (most use
the `box` view); the surrounding scaffold (ClipPath/Opacity/Transform/…) is kept.
Drivers: import `../_luci_skia_gold_prelude.dart`, set a `core_<name>` golden
prefix, and gate any "HCPP supported" assertion on `currentMode == PvMode.hcpp`.

### core/ (run under tlhc + hcpp)
- `lib/hcpp/platform_view_transform_main.dart` → `lib/core/transform_main.dart`
- `lib/hcpp/platform_view_clippath_main.dart` → `lib/core/clippath_main.dart`
- `lib/hcpp/platform_view_opacity_main.dart` → `lib/core/opacity_main.dart`
- `lib/hcpp/platform_view_clear_hidden.dart` → `lib/core/clear_hidden_main.dart`
- `lib/hcpp/platform_view_overlay_layer_cleared.dart` → `lib/core/overlay_layer_cleared_main.dart`
- `lib/hcpp/platform_view_overlapping_main.dart` → `lib/core/overlapping_main.dart`
- `lib/hcpp/rtl_mirror_main.dart` → `lib/core/rtl_mirror_main.dart`
- (+ matching `test_driver/hcpp/*` → `test_driver/core/*_test.dart`)

### hcpp_specific/ (run under hcpp only — mostly a move + import-path fixup)
- `lib/hcpp/upgrade_legacy_pv_types_main.dart` → `lib/hcpp_specific/upgrade_legacy_pv_types_main.dart` (registry: `hcppViaFlagOnly: true`)
- `lib/hcpp/platform_view_cliprect_surfaceview_main.dart` → `lib/hcpp_specific/cliprect_surfaceview_main.dart`
- `lib/hcpp/hc_errors_with_hcpp_enabled.dart` → `lib/hcpp_specific/hc_errors_with_hcpp_enabled_main.dart`
- `lib/hcpp/tlhc_with_fallback_to_hc_errors_with_hcpp_enabled.dart` → `lib/hcpp_specific/tlhc_with_fallback_to_hc_errors_main.dart`
- (+ matching `test_driver/hcpp/*` → `test_driver/hcpp_specific/*_test.dart`)

### legacy_specific/ (run under vd only)
- `lib/platform_view/virtual_display_platform_view_main.dart` →
  `lib/legacy_specific/virtual_display_gradient_main.dart`
  (body becomes `platformViewForMode(PvMode.vd, content: PvContent.blueOrangeGradient)`)
- `test_driver/platform_view/virtual_display_platform_view_main_test.dart` →
  `test_driver/legacy_specific/virtual_display_gradient_test.dart`
  (golden prefix `legacy_vd_gradient`)

## Old files to DELETE after the above land (superseded)

- `lib/platform_view/` (all) + `test_driver/platform_view/` (all)
  — gradient/hide_show_hide covered by `core/`; VD moved to `legacy_specific/`.
- `lib/hcpp/` (all) + `test_driver/hcpp/` (all) — moved to `core/` + `hcpp_specific/`.
- `lib/platform_view_tap_color_change_main.dart` +
  `test_driver/platform_view_tap_color_change_main_test.dart` — covered by `core/tap_color_change`.
- After deleting, prune the legacy dir names from `_isPlatformViewMain()` in the
  runner.

## Native (Kotlin) — no changes required

`box` and `changing_color_button` are plain `View`s (HCPP upgrades plain views,
per the existing tests); the gradient already has both `View` and `SurfaceView`
factories. The planned matrix needs no new factories in `MainActivity.kt`.

## Verification (must run on a device/emulator — not doable in CI sandbox)

1. `flutter analyze` in this package (and `dart analyze dev/bots`).
2. Regenerate goldens per backend (each run covers all of that backend's modes):
   ```sh
   SHARD=android_engine_vulkan_tests   UPDATE_GOLDENS=1 bin/cache/dart-sdk/bin/dart dev/bots/test.dart
   SHARD=android_engine_opengles_tests UPDATE_GOLDENS=1 bin/cache/dart-sdk/bin/dart dev/bots/test.dart
   ```
   Per-`(backend, mode)` goldens are distinguished by the Skia Gold `namePrefix`
   (`android_engine_test.<backend>.<mode>`). Note: *local* golden files are not
   mode-suffixed, so different modes overwrite the same local file — that's fine;
   Skia Gold holds the per-mode images on CI.
3. Re-run without `UPDATE_GOLDENS` to confirm green, then triage new images in
   Skia Gold.

## README

Update `README.md` (and remove `lib/hcpp/README.md`) to describe the
core/hcpp_specific/legacy_specific layout and the `PV_MODE` mechanism.
