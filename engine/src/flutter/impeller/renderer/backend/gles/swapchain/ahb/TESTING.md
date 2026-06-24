# OpenGL ES AHB swapchain — testing tracker (flutter/flutter#164252)

> **Temporary working doc** for the `try_opengles_ahb` branch. Tracks what each
> commit needs for verification while it's built/tested commit-by-commit on a
> Mac + connected Android device. Remove before opening the PR.

Implementation happens on Windows (which can't build Android-*target* binaries),
so build + run happen on a Mac with a connected Android device. Run all commands
from the **repo root**, with `et` and `adb` on your `PATH`.

---

## Copy-paste commands

### A. Compile-check everything implemented so far

Compiles the new `egl` + `gles` code (this is the main "did it build" gate for
every commit below):

```sh
et build -c android_debug_arm64 //flutter/impeller
```

### B. Build + run the on-device functional tests

```sh
et build -c android_debug_arm64 //flutter/impeller/toolkit/android:unittests
adb push engine/src/out/android_debug_arm64/impeller_toolkit_android_unittests /data/local/tmp/
adb shell /data/local/tmp/impeller_toolkit_android_unittests --gtest_filter='GLESAHBFenceTest.*'
```

What to look for in the output: both `GLESAHBFenceTest.CanExportAndImportNativeFence`
and `GLESAHBFenceTest.CanRenderIntoHardwareBuffer` should print **`[ PASSED ]`**.
If they print `[ SKIPPED ]`, the device/emulator lacks a required capability
(GLES context, hardware buffers, or the `EGL_ANDROID_native_fence_sync` /
`EGL_KHR_fence_sync` extensions) — the code path was *not* actually exercised, so
try a different device.

### C. Build the whole engine (broader compile check)

```sh
et build -c android_debug_arm64
```

---

## Per-commit checklist

Each row says exactly what to run. "Build-only" means: if command **A** succeeds,
the commit is verified — there is nothing to run or inspect.

| Commit (subject) | Run | Pass criteria |
|---|---|---|
| `Add egl::Fence native fence sync wrapper` (piece 1) | **A** | compiles |
| `Add AHBTextureSourceGLES ...` (piece 2) | **A** | compiles |
| `Add on-device tests ...` | **A**, then **B** | both `GLESAHBFenceTest.*` print `[ PASSED ]` (not `[ SKIPPED ]`) |
| `Add AHBTexturePoolGLES ...` (piece 3) | **A** | compiles (no caller/test yet) |
| `Add AHBSwapchainGLES ...` (piece 4) | **A** | compiles (not exercised yet) |
| `Wire AHBSwapchainGLES into the Android GLES surface (inert)` (piece 5) | **A** (+ optional **C**) | compiles; default rendering unchanged — see note below |
| _(piece 6: enable the gate — not yet implemented)_ | **run-and-inspect** (no simple command) | see below |

### Piece 5 note (no functional change to verify)

Piece 5 is gated off (`ShouldUseSurfaceControlSwapchain()` returns `false`), so
there is **no behavior change and nothing new to functionally test** — a
successful build (**A**, or the full engine via **C**) is the whole check.
Optionally, run any existing Flutter app on the GLES backend and confirm it still
renders normally; there's no pass/fail command for that, it's a visual smoke test.

### Piece 6 will be run-and-inspect

Once piece 6 turns the swapchain on, verification is **not** a simple pass/fail
command — it's: build the engine (**C**), run a Flutter app that (a) is forced
onto the OpenGL ES backend and (b) shows an HCPP platform view, then **visually
confirm** the UI and the platform view composite correctly (no black frames,
flicker, or z-order glitches). Exact run flags / manifest settings to force the
GLES backend + enable surface control will be added here when piece 6 lands.

---

## Notes / known gaps (intentional, pre-PR)

- Single-sample only; MSAA is a follow-up.
- Depth/stencil is allocated per drawable; caching (Vulkan's `SwapchainTransientsVK`
  equivalent) is a follow-up.
- `AHBTextureSourceGLES` / `AHBTexturePoolGLES` / `AHBSwapchainGLES` have no
  standalone gtests — they need a full `ContextGLES`. They get real coverage once
  piece 6 runs them in an app. The fence + AHB-render *technique* they rely on is
  covered by `GLESAHBFenceTest.*` (command **B**).
```
