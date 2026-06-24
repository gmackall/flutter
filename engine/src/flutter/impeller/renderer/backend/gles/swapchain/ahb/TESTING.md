# OpenGL ES AHB swapchain — testing tracker (flutter/flutter#164252)

> **Temporary working doc** for the `try_opengles_ahb` branch. Tracks what each
> commit needs for verification while it's built/tested commit-by-commit on a
> Mac + connected Android device. Remove before opening the PR.

## Environment

Implementation happens on Windows (which can't build Android-*target* binaries),
so build + run happen on a Mac with a connected Android device.

Common commands:

```sh
# Compile-check everything implemented so far (compiles the gles + egl code):
et build -c android_debug_arm64 //flutter/impeller

# Build the on-device gtest executable:
et build -c android_debug_arm64 //flutter/impeller/toolkit/android:unittests

# Run the building-block functional tests on the connected device:
adb push engine/src/out/android_debug_arm64/impeller_toolkit_android_unittests /data/local/tmp/
adb shell /data/local/tmp/impeller_toolkit_android_unittests --gtest_filter='GLESAHBFenceTest.*'
#   (adjust the out/ path to your variant; or use testing/run_tests.py --type android)
```

A test reporting `SKIPPED` means the device/emulator lacks a required capability
(GLES context, hardware buffers, or the native-fence EGL extensions) — not a
failure, but it means that path wasn't actually exercised.

## Per-commit status

| Commit (subject) | What to verify | How |
|---|---|---|
| `[Impeller] Add egl::Fence native fence sync wrapper` (piece 1) | Compiles. Functional fence behavior. | `//flutter/impeller` build; run `GLESAHBFenceTest.CanExportAndImportNativeFence` |
| `[Impeller] Add AHBTextureSourceGLES ...` (piece 2) | Compiles. AHB→EGLImage→FBO render technique. | `//flutter/impeller` build; run `GLESAHBFenceTest.CanRenderIntoHardwareBuffer` |
| `[Impeller] Add on-device tests ...` | Both functional tests PASS (not SKIPPED) on the device. | build `:unittests` + run `GLESAHBFenceTest.*` |
| `[Impeller] Add AHBTexturePoolGLES ...` (piece 3) | Compiles only (no caller/test yet). | `//flutter/impeller` build |
| `[Impeller] Add AHBSwapchainGLES ...` (piece 4) | Compiles only. Not exercised until integration. | `//flutter/impeller` build |
| `[Impeller] Wire AHBSwapchainGLES into the Android GLES surface (inert)` (piece 5) | Compiles; **default GLES rendering is unchanged** (swapchain gated off). Smoke-test a normal GLES-backend app still renders. | build the engine; run any app on the GLES backend |
| _(piece 6: enable the gate — pending)_ | Real app renders via the swapchain; HCPP platform views work on a non-Vulkan device. | run a Flutter app forcing the GLES backend + HCPP; visual check |

## Notes / known gaps (intentional, pre-PR)

- Single-sample only; MSAA is a follow-up.
- Depth/stencil is allocated per drawable; caching (Vulkan's `SwapchainTransientsVK`
  equivalent) is a follow-up.
- `AHBTextureSourceGLES` (the class) and `AHBTexturePoolGLES` / `AHBSwapchainGLES`
  have no standalone gtests — they need a full `ContextGLES`. They get real
  coverage once piece 5 runs them in an app. The fence + AHB-render *technique*
  they rely on is covered by `GLESAHBFenceTest.*`.
