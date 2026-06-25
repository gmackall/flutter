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

| Commit (subject) | Run | Pass criteria | Status |
|---|---|---|---|
| `Add egl::Fence native fence sync wrapper` (piece 1) | **A** | compiles | ✅ builds + functionally verified via **B** |
| `Add AHBTextureSourceGLES ...` (piece 2) | **A** | compiles | ✅ builds + technique verified via **B** |
| `Add on-device tests ...` | **A**, then **B** | both `GLESAHBFenceTest.*` print `[ PASSED ]` (not `[ SKIPPED ]`) | ✅ 2/2 PASSED on device |
| `Add AHBTexturePoolGLES ...` (piece 3) | **A** | compiles (no caller/test yet) | ✅ builds |
| `Add AHBSwapchainGLES ...` (piece 4) | **A** | compiles (not exercised yet) | ✅ builds |
| `Wire AHBSwapchainGLES into the Android GLES surface (inert)` (piece 5) | **A** (+ optional **C**) | compiles; default rendering unchanged — see note below | ✅ builds |
| `Enable HCPP + AHB swapchain on the OpenGL ES backend` (piece 6) | **D** (run-and-inspect) | app renders + HCPP platform view composites correctly on the GLES backend | ✅ renders correctly + better perf than fallback; scrolling clean |
| `Fix EGL_BAD_ACCESS: skip EGL window surface in GLES HCPP mode` | **D**, focus on rotate + background/foreground | (did not fix it — see below) | ⚠️ hygiene only; real fix pending |

### Device-testing findings so far

- ✅ Renders correctly on the GLES backend (confirmed via logcat
  `Using the Impeller rendering backend (OpenGLES).`), HCPP engaged, better perf
  than the legacy fallback, scrolling smooth.
- ⚠️ `EGL Error: Bad Access` on rotate + background/foreground — **root cause
  identified, fix still pending.** Thread-id diagnostics showed it is a
  **cross-thread EGL context** issue, not a surface issue: the raster thread
  holds the *onscreen* GL context current, and a second (non-raster) thread
  tries to make that same context current → `EGL_BAD_ACCESS`. The second thread
  fails and gives up while the raster thread renders on, so there is **no visual
  impact**, but it should not ship. The earlier "skip EGL window surface" change
  is reasonable hygiene (the window surface is vestigial in HCPP mode) but did
  **not** address this — the contended resource is the context, not the surface.

### E. EGL_BAD_ACCESS thread diagnostic (temporary — revert before PR)

A temporary diagnostic is in the tree to identify the offending thread. It logs
the **thread name** at every onscreen-context make/clear, plus a marker when the
snapshot path runs:

```sh
# Reproduce with HCPP on, then watch logcat:
adb logcat | grep "AHB-DIAG"
```

Reproduce the error (rotate, or background/foreground, or trigger whatever made
it fire — IME show/hide seemed to). Then look at the `AHB-DIAG` lines **around**
an `EGL Error: Bad Access`:

- The line with `OnscreenContextMakeCurrent result=0` is the failing call — the
  bracketed **thread name** is the culprit (thread B). Expected raster thread is
  `io.flutter.raster`; if B is something else (e.g. `io.flutter.io`,
  `io.flutter.ui`, a binder/`hwbinder` thread) that's the smoking gun.
- If an `AHB-DIAG [...] CreateSnapshotSurface` line appears just before the
  failure on that same thread, it confirms the **snapshot path** is the cause
  (the fix is then to route snapshots through the resource context, not the
  raster thread's onscreen context).
- If the failure happens with **no** `CreateSnapshotSurface` line, B reached the
  onscreen context another way (e.g. a snapshot via the main surface) — the
  thread name still tells us which subsystem.

Paste the `AHB-DIAG` + `Bad Access` lines and we can pinpoint the fix. (All of
this logging is marked `TODO(flutter#164252): TEMPORARY ... revert before PR`.)

### Piece 5 note (no functional change to verify)

Piece 5 is gated off (`ShouldUseSurfaceControlSwapchain()` returns `false`), so
there is **no behavior change and nothing new to functionally test** — a
successful build (**A**, or the full engine via **C**) is the whole check.
Optionally, run any existing Flutter app on the GLES backend and confirm it still
renders normally; there's no pass/fail command for that, it's a visual smoke test.

### D. Piece 6 — run-and-inspect (no pass/fail command)

Piece 6 turns the swapchain on, so verification is **visual**, not a command.

1. Build the engine (**C**) and point a Flutter app at this local engine
   (`--local-engine=android_debug_arm64 --local-engine-host=host_debug` — adjust
   to your host build dir).
2. Force the **OpenGL ES** backend **and** enable **HCPP + surface control** for
   the app. Either add these to the app's `AndroidManifest.xml` under
   `<application>`:

   ```xml
   <meta-data android:name="io.flutter.embedding.android.ImpellerBackend" android:value="opengles" />
   <meta-data android:name="io.flutter.embedding.android.EnableHcpp" android:value="true" />
   ```

   …or pass the equivalent engine switches if your run setup allows it:
   `--impeller-backend=opengles --enable-hcpp-and-surface-control`.
3. Run the app on a device (a non-Vulkan device/emulator is the real target, but
   any device works since the backend is forced), ideally one whose UI **shows a
   platform view** (e.g. a `WebView`, `GoogleMap`, or any `AndroidView`).
4. **Inspect visually:**
   - The Flutter UI renders normally (the main layer now goes through the AHB /
     SurfaceControl swapchain) — no black screen, flicker, tearing, or stale
     frames.
   - The platform view composites in the correct **z-order** with Flutter
     content drawn over/under it, and stays aligned while scrolling/animating.
   - Logcat shows no `VALIDATION_LOG` spam about failing to create the swapchain,
     set surface contents, or import/export fences.

   To confirm it actually took the new path (not a silent fallback): on the Dart
   side `FlutterJNI.IsSurfaceControlEnabled()` should return `true`, or check
   logcat for the OpenGL ES backend being selected.

If the device lacks the native-fence EGL extensions or SurfaceControl, the gate
returns false and the app falls back to the normal EGL path + non-HCPP platform
views (still correct, just not exercising this work) — so pick a device where
command **B**'s tests `PASSED`.

---

## Notes / known gaps (intentional, pre-PR)

- ✅ MSAA implemented (4x, matching the Vulkan AHB swapchain's default), gated off
  per-device when offscreen MSAA isn't supported. **Needs visual verification** —
  compare edge/text antialiasing against the non-HCPP GLES path. Watch logcat for
  allocation failures of the multisample color/depth-stencil textures.
- ✅ Depth/stencil (and the MSAA color) are now memoized for the swapchain's
  lifetime instead of allocated per drawable (the OpenGL ES analog of Vulkan's
  `SwapchainTransientsVK`).
- ⚠️ Open: the cross-thread `EGL_BAD_ACCESS` (see Device-testing findings) — root
  cause identified, fix pending. Benign (no visual impact).
- `AHBTextureSourceGLES` / `AHBTexturePoolGLES` / `AHBSwapchainGLES` have no
  standalone gtests — they need a full `ContextGLES`. They get real coverage once
  piece 6 runs them in an app. The fence + AHB-render *technique* they rely on is
  covered by `GLESAHBFenceTest.*` (command **B**).
```
