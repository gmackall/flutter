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
# Log pass

[        ] Installing build/app/outputs/flutter-apk/app-debug.apk...
[        ] executing: /Users/mackall/Library/Android/sdk/platform-tools/adb -s 34011FDH3001H9 install -t -r
/Users/mackall/development/packages/packages/webview_flutter/webview_flutter_android/example/build/app/outputs/flutter-apk/app-debug.apk
[+3834 ms] Performing Streamed Install
                    Success
[   +2 ms] Installing build/app/outputs/flutter-apk/app-debug.apk... (completed in 3.8s)
[   +1 ms] executing: /Users/mackall/Library/Android/sdk/platform-tools/adb -s 34011FDH3001H9 shell echo -n 85a8d6cecffe254b1cb19f293b434ce215634573 >
/data/local/tmp/sky.io.flutter.plugins.webviewflutterandroidexample.sha1
[  +38 ms] executing: /Users/mackall/Library/Android/sdk/platform-tools/adb -s 34011FDH3001H9 shell -x logcat -v time -t 1
[  +85 ms] --------- beginning of system
           06-25 09:58:29.038 V/AccessibilityManagerService( 1594): onUserStateChangedLocked for userId: 0, forceUpdate: false
[   +4 ms] executing: /Users/mackall/Library/Android/sdk/platform-tools/adb -s 34011FDH3001H9 shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x20000000 --ez
enable-dart-profiling true --ez enable-checked-mode true --ez verify-entry-points true io.flutter.plugins.webviewflutterandroidexample/io.flutter.embedding.android.FlutterActivity
[  +61 ms] Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x20000000
cmp=io.flutter.plugins.webviewflutterandroidexample/io.flutter.embedding.android.FlutterActivity (has extras) }
[        ] Waiting for VM Service port to be available...
[ +264 ms] I/FlutterActivityAndFragmentDelegate(21078): If you are attempting to set --enable-dart-profiling via Intent extras to launch a Flutter component outside of using the Flutter CLI, note
that support for setting engine flags on Android via Intent will soon be dropped; see https://github.com/flutter/flutter/issues/180686 for more information on this breaking change. To migrate, set
--enable-dart-profiling or any other flags specified via Intent extras on the command line instead or see https://github.com/flutter/flutter/blob/main/docs/engine/Flutter-Android-Engine-Flags.md for
alternative methods.
[   +5 ms] D/FlutterJNI(21078): Beginning load of flutter...
[  +27 ms] D/FlutterJNI(21078): flutter (null) was loaded normally!
[ +246 ms] I/flutter (21078): [IMPORTANT:flutter/shell/platform/android/android_context_gl_impeller.cc(118)] Using the Impeller rendering backend (OpenGLES).
[  +70 ms] VM Service URL on device: http://127.0.0.1:40819/QQLNqyWXyX0=/
[        ] executing: /Users/mackall/Library/Android/sdk/platform-tools/adb -s 34011FDH3001H9 forward tcp:0 tcp:40819
[  +31 ms] 49172
[        ] Forwarded host port 49172 to device port 40819 for VM Service
[   +3 ms] Caching compiled dill
[  +32 ms] Connecting to service protocol: http://127.0.0.1:49172/QQLNqyWXyX0=/
[  +47 ms] D/FlutterRenderer(21078): Width is zero. 0,0
[  +64 ms] Launching a Dart Developer Service (DDS) instance at http://127.0.0.1:0, connecting to VM service at http://127.0.0.1:49172/QQLNqyWXyX0=/.
[ +312 ms] Successfully connected to service protocol: http://127.0.0.1:49172/QQLNqyWXyX0=/
[  +38 ms] DevFS: Creating new filesystem on the device (null)
[  +11 ms] DevFS: Created new filesystem on the device (file:///data/user/0/io.flutter.plugins.webviewflutterandroidexample/code_cache/exampleBXMAOP/example/)
[   +1 ms] Updating assets for android
[        ] runHooks() with {TargetFile: /Users/mackall/development/packages/packages/webview_flutter/webview_flutter_android/example/lib/main.dart, BuildMode: debug} and TargetPlatform.android_arm64
[        ] runHooks() - will perform dart build
[  +11 ms] No packages with native assets. Skipping native assets compilation.
[        ] runHooks() - done
[  +53 ms] Syncing files to device Pixel 7 Pro...
[        ] Compiling dart to kernel with 0 updated files
[        ] Processing bundle.
[        ] <- recompile package:webview_flutter_android_example/main.dart 5c6744be-5a85-40b0-8c5c-8ed6185b544a
[        ] <- 5c6744be-5a85-40b0-8c5c-8ed6185b544a
[        ] Bundle processing done.
[  +24 ms] Updating files.
[        ] Pending asset builds completed. Writing dirty entries.
[        ] DevFS: Sync finished
[        ] Syncing files to device Pixel 7 Pro... (completed in 27ms)
[        ] Synced 0.0MB.
[        ] <- accept
[   +3 ms] Connected to _flutterView/0xb400007d25cf3a20.
[        ] Flutter run key commands.
[        ] r Hot reload. 🔥🔥🔥
[        ] R Hot restart.
[        ] h List all available interactive commands.
[        ] d Detach (terminate "flutter run" but leave application running).
[        ] c Clear the screen
[        ] q Quit (terminate the application on the device).
[        ] A Dart VM Service on Pixel 7 Pro is available at: http://127.0.0.1:49178/-8GkAcbtEAc=/
[        ] The Flutter DevTools debugger and profiler on Pixel 7 Pro is available at: http://127.0.0.1:49178/-8GkAcbtEAc=/devtools/?uri=ws://127.0.0.1:49178/-8GkAcbtEAc=/ws
[ +245 ms] I/Choreographer(21078): Skipped 90 frames!  The application may be doing too much work on its main thread.
[        ] D/WindowOnBackDispatcher(21078): setTopOnBackInvokedCallback (unwrapped): android.app.Activity$$ExternalSyntheticLambda0@d8ad171
[   +2 ms] I/WindowExtensionsImpl(21078): Initializing Window Extensions, vendor API level=9, activity embedding enabled=true
[  +17 ms] I/randroidexample(21078): Compiler allocated 5127KB to compile void android.view.ViewRootImpl.performTraversals()
[        ] D/VRI[FlutterActivity](21078): WindowInsets changed: 1080x2340 statusBars:[0,108,0,0] navigationBars:[0,0,0,63] mandatorySystemGestures:[0,140,0,84]
[        ] D/FlutterRenderer(21078): Width is zero. 0,0
[   +1 ms] D/FlutterRenderer(21078): Width is zero. 0,0
[        ] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[   +7 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[  +23 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(280)] AHB-DIAG [1.raster] OnscreenContextClearCurrent result=1
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[  +39 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[   +9 ms] I/WebViewFactory(21078): Loading com.google.android.webview version 149.0.7827.91 (code 782709103)
[   +1 ms] V/ResourcesManager(21078): The following library key has been added: ResourcesKey{ mHash=18fc51e9 mResDir=null mSplitDirs=[] mOverlayDirs=[]
mLibDirs=[/data/app/~~Y101QWN94EWI4OuMyZ7cVg==/com.google.android.webview-WfsUruoB7-He8M0izRqeqA==/base.apk,/system/framework/android.test.base.jar,/system_ext/framework/androidx.window.extensions.ja
r] mDisplayId=0 mOverrideConfig=v36 mCompatInfo={560dpi always-compat} mLoaders=[]}
[        ] D/ApplicationLoaders(21078): Returning zygote-cached class loader: /system/framework/android.test.base.jar
[        ] D/ApplicationLoaders(21078): Returning zygote-cached class loader: /system_ext/framework/androidx.window.extensions.jar
[        ] W/randroidexample(21078): Loading /data/app/~~Y101QWN94EWI4OuMyZ7cVg==/com.google.android.webview-WfsUruoB7-He8M0izRqeqA==/oat/arm64/base.odex non-executable as it requires an image which
we failed to load
[   +1 ms] D/nativeloader(21078): Configuring clns-10 for other apk /data/app/~~Y101QWN94EWI4OuMyZ7cVg==/com.google.android.webview-WfsUruoB7-He8M0izRqeqA==/base.apk. target_sdk_version=36,
uses_libraries=,
library_path=/data/app/~~Y101QWN94EWI4OuMyZ7cVg==/com.google.android.webview-WfsUruoB7-He8M0izRqeqA==/lib/arm64:/data/app/~~Y101QWN94EWI4OuMyZ7cVg==/com.google.android.webview-WfsUruoB7-He8M0izRqeqA=
=/base.apk!/lib/arm64-v8a, permitted_path=/data:/mnt/expand
[  +14 ms] I/cr_WVCFactoryProvider(21078): version=149.0.7827.91 (782709103) minSdkVersion=29 multiprocess=true packageId=2 splits=<none>
[   +2 ms] D/nativeloader(21078): Load /data/app/~~Y101QWN94EWI4OuMyZ7cVg==/com.google.android.webview-WfsUruoB7-He8M0izRqeqA==/base.apk!/lib/arm64-v8a/libwebviewchromium.so using class loader ns
clns-10 (caller=/data/app/~~Y101QWN94EWI4OuMyZ7cVg==/com.google.android.webview-WfsUruoB7-He8M0izRqeqA==/base.apk): ok
[        ] D/nativeloader(21078): Load /system/lib64/libwebviewchromium_plat_support.so using class loader ns clns-10
(caller=/data/app/~~Y101QWN94EWI4OuMyZ7cVg==/com.google.android.webview-WfsUruoB7-He8M0izRqeqA==/base.apk): ok
[   +1 ms] E/chromium(21078): [0625/095830.770358:ERROR:android_webview/browser/variations/variations_seed_loader.cc:39] Seed missing signature.
[   +2 ms] I/cr_LibraryLoader(21078): Successfully loaded native library
[        ] I/cr_CachingUmaRecorder(21078): Flushed 41 samples from 21 histograms, 0 samples were dropped.
[        ] I/cr_ChildProcLH(21078): ScopedServiceBindingBatch.tryActivate: false
[        ] I/cr_CombinedPProvider(21078): #registerProvider() provider:WV.wk@c4bc8ec isPolicyCacheEnabled:false policyProvidersSize:0
[        ] I/cr_PolicyProvider(21078): #setManagerAndSource() 0
[   +1 ms] I/cr_policy(21078): registerReceiver succeeded after 1ms
[        ] I/cr_DisplayManager(21078): Is Display Topology available: false
[   +8 ms] I/cr_CombinedPProvider(21078): #linkNativeInternal() 1
[        ] I/cr_AppResProvider(21078): #getApplicationRestrictionsFromUserManager() Bundle[EMPTY_PARCEL]
[        ] I/cr_PolicyProvider(21078): #notifySettingsAvailable() 0
[        ] I/cr_CombinedPProvider(21078): #onSettingsAvailable() 0
[        ] I/cr_CombinedPProvider(21078): #flushPolicies()
[  +16 ms] W/chromium(21078): [WARNING:net/dns/dns_config_service_android.cc:69] Failed to read DnsConfig.
[   +3 ms] W/chromium(21078): [WARNING:android_webview/browser/network_service/net_helpers.cc:137] HTTP Cache size is: 148956973
[  +20 ms] I/cr_AppResProvider(21078): #getApplicationRestrictionsFromUserManager() Bundle[EMPTY_PARCEL]
[        ] I/cr_PolicyProvider(21078): #notifySettingsAvailable() 0
[        ] I/cr_CombinedPProvider(21078): #onSettingsAvailable() 0
[        ] I/cr_CombinedPProvider(21078): #flushPolicies()
[   +2 ms] I/CameraManagerGlobal(21078): Connecting to camera service
[  +30 ms] W/cr_media(21078): BLUETOOTH_CONNECT permission is missing.
[        ] W/cr_media(21078): getBluetoothAdapter() requires BLUETOOTH permission
[        ] W/cr_media(21078): registerBluetoothIntentsIfNeeded: Requires BLUETOOTH permission
[   +1 ms] D/AudioSystem(21078): onNewService: media.audio_policy service obtained 0xb400007cf5ddef60
[   +4 ms] D/AudioSystem(21078): getService: IAudioPolicyService retrieved: 0xb400007cf5ddef60  cached: 0xb400007cf5ddef60
[  +11 ms] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[  +10 ms] I/PlatformViewsChannel(21078): Using HCPP platform view rendering strategy.
[   +7 ms] I/flutter (21078): WebView is loading (progress : 10%)
[  +20 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[   +2 ms] E/libEGL  (21078): eglMakeCurrentImpl:1100 error 3002 (EGL_BAD_ACCESS)
[        ] E/flutter (21078): [ERROR:flutter/impeller/toolkit/egl/egl.cc(56)] EGL Error: Bad Access (12290) in ../../flutter/impeller/toolkit/egl/context.cc:55
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [randroidexample] OnscreenContextMakeCurrent result=0
[        ] E/libEGL  (21078): call to OpenGL ES API with no current context (logged once per thread)
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[        ] D/WindowLayoutComponentImpl(21078): Register WindowLayoutInfoListener on Context=io.flutter.embedding.android.FlutterActivity@ba7a50b, of which baseContext=android.app.ContextImpl@54d747a
[   +3 ms] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[  +26 ms] D/InsetsController(21078): hide(ime())
[        ] I/ImeTracker(21078): io.flutter.plugins.webviewflutterandroidexample:69f164a7: onCancelled at PHASE_CLIENT_ALREADY_HIDDEN
[  +16 ms] I/randroidexample(21078): hiddenapi: Accessing hidden field Landroid/graphics/Bitmap;->mNativePtr:J (runtime_flags=0, domain=platform, api=unsupported) from
Lcom/android/webview/chromium/WebViewChromium; (domain=app, TargetSdkVersion=36) using JNI: allowed
[        ] I/flutter (21078): Page started loading: https://flutter.dev/
[        ] I/flutter (21078): url change to https://flutter.dev/
[   +5 ms] I/chromium(21078): [INFO:CONSOLE:16] "Executing inline script violates the following Content Security Policy directive 'script-src 'self''. Either the 'unsafe-inline' keyword, a hash
('sha256-br+x1hUNk8SzSxm1TBU2bbObnXPLeGg9aPX8zrgyFWg='), or a nonce ('nonce-...') is required to enable inline execution. The policy is report-only, so the violation has been logged but no further
action has been taken.", source: https://flutter.dev/ (16)
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[        ] I/chromium(21078): [INFO:CONSOLE:20] "Loading the script 'https://www.googletagmanager.com/gtm.js?id=GTM-ND4LWWZ' violates the following Content Security Policy directive: "script-src
'self'". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback. The policy is report-only, so the violation has been logged but no further action has been taken.",
source: https://flutter.dev/ (20)
[        ] I/chromium(21078): [INFO:CONSOLE:24] "Executing inline script violates the following Content Security Policy directive 'script-src 'self''. Either the 'unsafe-inline' keyword, a hash
('sha256-2kunuDlCppEZ8WaGGybs1/iSbmO+gmduci8z1NYj6GM='), or a nonce ('nonce-...') is required to enable inline execution. The policy is report-only, so the violation has been logged but no further
action has been taken.", source: https://flutter.dev/ (24)
[        ] I/chromium(21078): [INFO:CONSOLE:27] "Loading the script 'https://www.google-analytics.com/analytics.js' violates the following Content Security Policy directive: "script-src 'self'". Note
that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback. The policy is report-only, so the violation has been logged but no further action has been taken.", source:
https://flutter.dev/ (27)
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[        ] E/SurfaceComposerClient(21078): Could not call release buffer callback, buffer not found bufferId:90529320665093 framenumber:101
[        ] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[   +5 ms] I/ContentCaptureHelper(21078): Setting logging level to OFF
[  +12 ms] I/cr_A11yState(21078): Enabled accessibility services list updated. []
[        ] I/cr_A11yState(21078): Informing listeners of changes.
[        ] I/cr_A11yState(21078): New AccessibilityState: State{isComplexUserInteractionServiceEnabled=false, isTouchExplorationEnabled=false, isPerformGesturesEnabled=false,
isAnyAccessibilityServiceEnabled=false, isAccessibilityToolPresent=false, isTextShowPasswordEnabled=true, isOnlyAutofillRunning=false, isOnlyPasswordManagersEnabled=false,
isKnownScreenReaderEnabled=false}
[   +2 ms] I/chromium(21078): [INFO:CONSOLE:0] "Loading the script 'https://www.google.com/recaptcha/api.js' violates the following Content Security Policy directive: "script-src 'self'". Note that
'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback. The policy is report-only, so the violation has been logged but no further action has been taken.", source:
https://flutter.dev/ (0)
[   +2 ms] I/randroidexample(21078): hiddenapi: Accessing hidden field Landroid/content/pm/ApplicationInfo;->primaryCpuAbi:Ljava/lang/String; (runtime_flags=0, domain=platform, api=unsupported) from
Lorg/chromium/android_webview/SystemStateUtil; (domain=app, TargetSdkVersion=36) using reflection: allowed
[   +2 ms] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[   +7 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[   +1 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[  +14 ms] I/flutter (21078): WebView is loading (progress : 27%)
[  +99 ms] I/flutter (21078): WebView is loading (progress : 70%)
[  +70 ms] I/chromium(21078): [INFO:CONSOLE:1] "Loading the script 'https://www.gstatic.com/recaptcha/releases/MerVUtRoajKEbP7pLiGXkL28/recaptcha__en.js' violates the following Content Security
Policy directive: "script-src 'self'". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback. The policy is report-only, so the violation has been logged but no
further action has been taken.", source: https://www.google.com/recaptcha/api.js (1)
[  +15 ms] I/chromium(21078): [INFO:CONSOLE:0] "Loading the script 'https://www.gstatic.com/glue/cookienotificationbar/cookienotificationbar.min.js' violates the following Content Security Policy
directive: "script-src 'self'". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback. The policy is report-only, so the violation has been logged but no further
action has been taken.", source: https://flutter.dev/ (0)
[ +106 ms] I/cr_MediaCodecBridge(21078): create MediaCodec video decoder, mime video/avc, decoder name c2.exynos.h264.decoder, block_model=false
[  +11 ms] D/CCodec  (21078): allocate(c2.exynos.h264.decoder)
[        ] I/flutter (21078): WebView is loading (progress : 80%)
[  +12 ms] I/flutter (21078): Page resource error:
[        ] I/flutter (21078):   code: -1
[        ] I/flutter (21078):   description: net::ERR_FAILED
[        ] I/flutter (21078):   errorType: WebResourceErrorType.unknown
[        ] I/flutter (21078):   isForMainFrame: false
[        ] I/flutter (21078):   url: https://flutter.dev/assets/feature-flexible.49894296881c4ae30e96bed4aee6967c.mp4
[        ] I/flutter (21078):
[   +3 ms] I/ApexCodecsLazy(21078): ApexCodecs loaded
[   +1 ms] I/Codec2Client(21078): Available Codec2 services: "default" "default1" "default9" "software" "__ApexCodecs__"
[   +1 ms] I/CCodec  (21078): setting up 'default' as default (vendor) store
[   +3 ms] I/CCodec  (21078): Created component [c2.exynos.h264.decoder] for [c2.exynos.h264.decoder]
[        ] D/CCodecConfig(21078): read media type: video/avc
[   +1 ms] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.subscribed-indices.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: input.buffers.allocator-ids.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.buffers.allocator-ids.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.buffers.pool-ids.values
[   +2 ms] D/ReflectedParamUpdater(21078): ignored struct field coded.color-format.locations
[   +1 ms] D/CCodecConfig(21078): ignoring local param raw.size (0xd2001800) as it is already supported
[        ] D/CCodecConfig(21078): ignoring local param default.color (0x5200180b) as it is already supported
[        ] D/ReflectedParamUpdater(21078): ignored struct field raw.hdr-static-info.mastering
[        ] I/CCodecConfig(21078): query failed after returning 15 values (BAD_INDEX)
[   +1 ms] D/CCodecConfig(21078): c2 config diff is Dict {
[        ] D/CCodecConfig(21078):   c2::u32 algo.low-latency.value = 0
[        ] D/CCodecConfig(21078):   c2::i32 algo.priority.value = 0
[        ] D/CCodecConfig(21078):   c2::float algo.rate.value = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.pl.level = 20496
[        ] D/CCodecConfig(21078):   c2::u32 coded.pl.profile = 20481
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::u32 input.buffers.max-size.value = 7340032
[        ] D/CCodecConfig(21078):   string input.media-type.value = "video/avc"
[        ] D/CCodecConfig(21078):   c2::u32 output.delay.value = 3
[        ] D/CCodecConfig(21078):   string output.media-type.value = "video/raw"
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::float raw.hdr-static-info.max-cll = 0
[        ] D/CCodecConfig(21078):   c2::float raw.hdr-static-info.max-fall = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 240
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.width = 320
[        ] D/CCodecConfig(21078):   c2::u32 raw.pixel-format.value = 34
[        ] D/CCodecConfig(21078):   c2::i32 raw.rotation.flip =
[        ] W/ColorUtils(21078): expected specified color aspects (0:0:0:0)
[        ] I/MediaCodec(21078): MediaCodec will operate in async mode
[   +2 ms] I/MediaCodec(21078): media_quality service unavailable, skipping updatePictureProfile
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d83a60, reason connectToSurface
[        ] I/MediaCodec(21078): [c2.exynos.h264.decoder] setting surface generation to 21583873
[        ] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d83a60, reason connectToSurface(reconnect)
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d83a50, reason connectToSurface(reconnect-with-listener)
[        ] D/CCodec  (21078): [c2.exynos.h264.decoder] buffers are bound to CCodec for this session
[   +9 ms] D/CCodecConfig(21078): no c2 equivalents for csd-1
[        ] D/CCodecConfig(21078): no c2 equivalents for color-standard
[   +1 ms] D/CCodecConfig(21078): no c2 equivalents for native-window
[        ] D/CCodecConfig(21078): no c2 equivalents for native-window-generation
[        ] D/CCodecConfig(21078): no c2 equivalents for flags
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 default.color.matrix = 1
[        ] D/CCodecConfig(21078):   c2::u32 default.color.primaries = 1
[        ] D/CCodecConfig(21078):   c2::u32 default.color.range = 2
[        ] D/CCodecConfig(21078):   c2::u32 default.color.transfer = 3
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.width = 1632
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.width = 1632
[        ] D/CCodec  (21078): client requested max input size 1331712, which is smaller than what component recommended (7340032); overriding with component recommendation.
[        ] W/CCodec  (21078): This behavior is subject to change. It is recommended that app developers double check whether the requested max input size is in reasonable range.
[        ] D/CCodec  (21078): encoding statistics level = 0
[        ] D/CCodec  (21078): setup formats input: AMessage(what = 0x00000000) = {
[        ] D/CCodec  (21078):   int32_t height = 1080
[        ] D/CCodec  (21078):   int32_t level = 65536
[        ] D/CCodec  (21078):   int32_t max-input-size = 7340032
[        ] D/CCodec  (21078):   string mime = "video/avc"
[        ] D/CCodec  (21078):   int32_t priority = 0
[        ] D/CCodec  (21078):   int32_t profile = 65536
[        ] D/CCodec  (21078):   int32_t width = 1632
[        ] D/CCodec  (21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodec  (21078): }
[        ] D/CCodec  (21078): setup formats output: AMessage(what = 0x00000000) = {
[        ] D/CCodec  (21078):   int32_t android._color-format = 2130708361
[        ] D/CCodec  (21078):   int32_t android._video-scaling = 1
[        ] D/CCodec  (21078):   int32_t rotation-degrees = 0
[        ] D/CCodec  (21078):   int32_t color-standard = 1
[        ] D/CCodec  (21078):   int32_t color-range = 2
[        ] D/CCodec  (21078):   int32_t color-transfer = 3
[        ] D/CCodec  (21078):   float cta861.max-cll = 0.000000
[        ] D/CCodec  (21078):   float cta861.max-fall = 0.000000
[        ] D/CCodec  (21078):   int32_t sar-height = 1
[        ] D/CCodec  (21078):   int32_t sar-width = 1
[        ] D/CCodec  (21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodec  (21078):   int32_t width = 1632
[        ] D/CCodec  (21078):   int32_t height = 1080
[        ] D/CCodec  (21078):   int32_t max-height = 1080
[        ] D/CCodec  (21078):   int32_t max-width = 1632
[        ] D/CCodec  (21078):   string mime = "video/raw"
[        ] D/CCodec  (21078):   int32_t priority = 0
[        ] D/CCodec  (21078):   int32_t android._dataspace = 260
[        ] D/CCodec  (21078):   int32_t color-format = 2130708361
[        ] D/CCodec  (21078): }
[        ] I/CCodecConfig(21078): query failed after returning 15 values (BAD_INDEX)
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 raw.color.matrix = 1
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.primaries = 1
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.range = 2
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.transfer = 3
[        ] E/randroidexample(21078): Failed to query component interface for required system resources: 6
[        ] D/MediaCodec(21078): keep callback message for reclaim
[   +1 ms] D/C2Store (21078): debug.c2.use_dmabufheaps set, forcing DMABUF Heaps
[        ] D/C2Store (21078): Using DMABUF Heaps
[   +1 ms] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] Created input block pool with allocatorID 16 => poolID 17 - OK (0)
[        ] D/Codec2Client(21078): GraphicBufferAllocator created
[        ] I/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] Created output block pool with allocatorID 18 => poolID 218 - OK
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] Configured output block pool ids 218 => OK
[   +1 ms] D/Codec2Client(21078): setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)
[        ] D/GraphicsTracker(21078): new surface in configuration: maxDequeueRequested(0), maxDequeueCommitted(3)
[        ] D/GraphicsTracker(21078): new surface configured with id:90529320665091 gen:21583873 maxDequeue:3
[        ] D/GraphicsTracker(21078): maxDequeueCount committed to IGBP: 10
[   +2 ms] D/GraphicsTracker(21078): Cache size 0 -> 0: maybe_cleared(0), dequeued(0)
[        ] D/GraphicsTracker(21078): maxDqueueCount change 3 -> 10: pending: 0
[        ] D/Codec2Client(21078): setOutputSurface -- generation=21583873 consumer usage=0x900
[        ] I/cr_MediaCodecBridge(21078): create MediaCodec video decoder, mime video/avc, decoder name c2.exynos.h264.decoder, block_model=false
[        ] W/randroidexample(21078): AIBinder_linkToDeath is being called with a non-null cookie and no onUnlink callback set. Use AIBinder_DeathRecipient_setOnUnlinked to manage the lifetime of the
cookie. This will become an abort.
[        ] D/CCodec  (21078): allocate(c2.exynos.h264.decoder)
[   +8 ms] I/CCodec  (21078): setting up 'default' as default (vendor) store
[   +5 ms] D/CCodecConfig(21078): c2 config diff is   c2::u32 output.delay.value = 4
[        ] D/GraphicsTracker(21078): maxDequeueCount committed to IGBP: 11
[        ] D/GraphicsTracker(21078): Cache size 1 -> 1: maybe_cleared(0), dequeued(1)
[        ] D/GraphicsTracker(21078): maxDqueueCount change 10 -> 11: pending: 0
[        ] W/randroidexample(21078): AIBinder_linkToDeath is being called with a non-null cookie and no onUnlink callback set. Use AIBinder_DeathRecipient_setOnUnlinked to manage the lifetime of the
cookie. This will become an abort.
[        ] I/CCodec  (21078): Created component [c2.exynos.h264.decoder] for [c2.exynos.h264.decoder]
[        ] D/CCodecConfig(21078): read media type: video/avc
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] Ignoring stale input buffer done callback: last flush index = 0, frameIndex = 0
[   +5 ms] D/GraphicsTracker(21078): maxDequeueCount is already 11
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.subscribed-indices.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: input.buffers.allocator-ids.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.buffers.allocator-ids.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.buffers.pool-ids.values
[        ] D/GraphicsTracker(21078): maxDequeueCount is already 11
[        ] D/ReflectedParamUpdater(21078): ignored struct field coded.color-format.locations
[        ] D/CCodecConfig(21078): ignoring local param raw.size (0xd2001800) as it is already supported
[        ] D/CCodecConfig(21078): ignoring local param default.color (0x5200180b) as it is already supported
[        ] D/ReflectedParamUpdater(21078): ignored struct field raw.hdr-static-info.mastering
[   +8 ms] I/CCodecConfig(21078): query failed after returning 15 values (BAD_INDEX)
[        ] D/CCodecConfig(21078): c2 config diff is Dict {
[        ] D/CCodecConfig(21078):   c2::u32 algo.low-latency.value = 0
[        ] D/CCodecConfig(21078):   c2::i32 algo.priority.value = 0
[        ] D/CCodecConfig(21078):   c2::float algo.rate.value = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.pl.level = 20496
[        ] D/CCodecConfig(21078):   c2::u32 coded.pl.profile = 20481
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::u32 input.buffers.max-size.value = 7340032
[        ] D/CCodecConfig(21078):   string input.media-type.value = "video/avc"
[        ] D/CCodecConfig(21078):   c2::u32 output.delay.value = 3
[        ] D/CCodecConfig(21078):   string output.media-type.value = "video/raw"
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::float raw.hdr-static-info.max-cll = 0
[        ] D/CCodecConfig(21078):   c2::float raw.hdr-static-info.max-fall = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 240
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.width = 320
[        ] D/CCodecConfig(21078):   c2::u32 raw.pixel-format.value = 34
[        ] D/CCodecConfig(21078):   c2::i32 raw.rotation.flip =
[        ] W/ColorUtils(21078): expected specified color aspects (0:0:0:0)
[  +10 ms] I/MediaCodec(21078): MediaCodec will operate in async mode
[        ] I/MediaCodec(21078): media_quality service unavailable, skipping updatePictureProfile
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d8ce70, reason connectToSurface
[        ] I/MediaCodec(21078): [c2.exynos.h264.decoder] setting surface generation to 21583874
[        ] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d8ce70, reason connectToSurface(reconnect)
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d8ce60, reason connectToSurface(reconnect-with-listener)
[        ] D/CCodec  (21078): [c2.exynos.h264.decoder] buffers are bound to CCodec for this session
[        ] D/CCodecConfig(21078): no c2 equivalents for csd-1
[        ] D/CCodecConfig(21078): no c2 equivalents for color-standard
[        ] D/CCodecConfig(21078): no c2 equivalents for native-window
[        ] D/CCodecConfig(21078): no c2 equivalents for native-window-generation
[        ] D/CCodecConfig(21078): no c2 equivalents for flags
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 default.color.matrix = 1
[        ] D/CCodecConfig(21078):   c2::u32 default.color.primaries = 1
[        ] D/CCodecConfig(21078):   c2::u32 default.color.range = 2
[        ] D/CCodecConfig(21078):   c2::u32 default.color.transfer = 3
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.width = 1632
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.width = 1632
[        ] D/CCodec  (21078): client requested max input size 1331712, which is smaller than what component recommended (7340032); overriding with component recommendation.
[        ] W/CCodec  (21078): This behavior is subject to change. It is recommended that app developers double check whether the requested max input size is in reasonable range.
[        ] D/CCodec  (21078): encoding statistics level = 0
[        ] D/CCodec  (21078): setup formats input: AMessage(what = 0x00000000) = {
[        ] D/CCodec  (21078):   int32_t height = 1080
[        ] D/CCodec  (21078):   int32_t level = 65536
[        ] D/CCodec  (21078):   int32_t max-input-size = 7340032
[        ] D/CCodec  (21078):   string mime = "video/avc"
[        ] D/CCodec  (21078):   int32_t priority = 0
[        ] D/CCodec  (21078):   int32_t profile = 65536
[        ] D/CCodec  (21078):   int32_t width = 1632
[        ] D/CCodec  (21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodec  (21078): }
[        ] D/CCodec  (21078): setup formats output: AMessage(what = 0x00000000) = {
[        ] D/CCodec  (21078):   int32_t android._color-format = 2130708361
[        ] D/CCodec  (21078):   int32_t android._video-scaling = 1
[        ] D/CCodec  (21078):   int32_t rotation-degrees = 0
[        ] D/CCodec  (21078):   int32_t color-standard = 1
[        ] D/CCodec  (21078):   int32_t color-range = 2
[        ] D/CCodec  (21078):   int32_t color-transfer = 3
[        ] D/CCodec  (21078):   float cta861.max-cll = 0.000000
[        ] D/CCodec  (21078):   float cta861.max-fall = 0.000000
[        ] D/CCodec  (21078):   int32_t sar-height = 1
[        ] D/CCodec  (21078):   int32_t sar-width = 1
[        ] D/CCodec  (21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodec  (21078):   int32_t width = 1632
[        ] D/CCodec  (21078):   int32_t height = 1080
[        ] D/CCodec  (21078):   int32_t max-height = 1080
[        ] D/CCodec  (21078):   int32_t max-width = 1632
[        ] D/CCodec  (21078):   string mime = "video/raw"
[        ] D/CCodec  (21078):   int32_t priority = 0
[        ] D/CCodec  (21078):   int32_t android._dataspace = 260
[        ] D/CCodec  (21078):   int32_t color-format = 2130708361
[        ] D/CCodec  (21078): }
[        ] I/CCodecConfig(21078): query failed after returning 15 values (BAD_INDEX)
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 raw.color.matrix = 1
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.primaries = 1
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.range = 2
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.transfer = 3
[        ] E/randroidexample(21078): Failed to query component interface for required system resources: 6
[        ] D/MediaCodec(21078): keep callback message for reclaim
[   +2 ms] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#204] Created input block pool with allocatorID 16 => poolID 18 - OK (0)
[        ] D/Codec2Client(21078): GraphicBufferAllocator created
[        ] I/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#204] Created output block pool with allocatorID 18 => poolID 219 - OK
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#204] Configured output block pool ids 219 => OK
[        ] D/Codec2Client(21078): setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)
[        ] D/GraphicsTracker(21078): new surface in configuration: maxDequeueRequested(0), maxDequeueCommitted(3)
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 raw.crop.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.left = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.top = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.width = 1632
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 1088
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.height = 1088
[        ] D/GraphicsTracker(21078): new surface configured with id:90529320665092 gen:21583874 maxDequeue:3
[        ] D/GraphicsTracker(21078): maxDequeueCount committed to IGBP: 10
[        ] D/GraphicsTracker(21078): Cache size 0 -> 0: maybe_cleared(0), dequeued(0)
[        ] D/GraphicsTracker(21078): maxDqueueCount change 3 -> 10: pending: 0
[        ] D/Codec2Client(21078): setOutputSurface -- generation=21583874 consumer usage=0x900
[        ] D/CCodecBuffers(21078): [c2.exynos.h264.decoder#454:2D-Output] popFromStashAndRegister: at 0us, output format changed to AMessage(what = 0x00000000) = {
[        ] D/CCodecBuffers(21078):   int32_t android._color-format = 2130708361
[        ] D/CCodecBuffers(21078):   int32_t android._video-scaling = 1
[        ] D/CCodecBuffers(21078):   int32_t rotation-degrees = 0
[        ] D/CCodecBuffers(21078):   int32_t color-standard = 1
[        ] D/CCodecBuffers(21078):   int32_t color-range = 2
[        ] D/CCodecBuffers(21078):   int32_t color-transfer = 3
[        ] D/CCodecBuffers(21078):   float cta861.max-cll = 0.000000
[        ] D/CCodecBuffers(21078):   float cta861.max-fall = 0.000000
[        ] D/CCodecBuffers(21078):   int32_t sar-height = 1
[        ] D/CCodecBuffers(21078):   int32_t sar-width = 1
[        ] D/CCodecBuffers(21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodecBuffers(21078):   int32_t width = 1632
[        ] D/CCodecBuffers(21078):   int32_t height = 1088
[        ] D/CCodecBuffers(21078):   int32_t max-height = 1088
[        ] D/CCodecBuffers(21078):   int32_t max-width = 1632
[        ] D/CCodecBuffers(21078):   string mime = "video/raw"
[        ] D/CCodecBuffers(21078):   int32_t priority = 0
[        ] D/CCodecBuffers(21078):   int32_t android._dataspace = 260
[        ] D/CCodecBuffers(21078):   int32_t color-format = 2130708361
[        ] D/CCodecBuffers(21078): }
[        ] I/cr_MediaCodecBridge(21078): create MediaCodec video decoder, mime video/avc, decoder name c2.exynos.h264.decoder, block_model=false
[        ] W/randroidexample(21078): AIBinder_linkToDeath is being called with a non-null cookie and no onUnlink callback set. Use AIBinder_DeathRecipient_setOnUnlinked to manage the lifetime of the
cookie. This will become an abort.
[        ] I/randroidexample(21078): Use dataspace 281083904as it is
[        ] D/CCodec  (21078): allocate(c2.exynos.h264.decoder)
[        ] I/CCodec  (21078): setting up 'default' as default (vendor) store
[  +14 ms] W/randroidexample(21078): AIBinder_linkToDeath is being called with a non-null cookie and no onUnlink callback set. Use AIBinder_DeathRecipient_setOnUnlinked to manage the lifetime of the
cookie. This will become an abort.
[        ] I/CCodec  (21078): Created component [c2.exynos.h264.decoder] for [c2.exynos.h264.decoder]
[        ] D/CCodecConfig(21078): read media type: video/avc
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.subscribed-indices.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: input.buffers.allocator-ids.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.buffers.allocator-ids.values
[        ] D/ReflectedParamUpdater(21078): extent() != 1 for single value type: output.buffers.pool-ids.values
[        ] D/ReflectedParamUpdater(21078): ignored struct field coded.color-format.locations
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 output.delay.value = 4
[        ] D/GraphicsTracker(21078): maxDequeueCount committed to IGBP: 11
[        ] D/GraphicsTracker(21078): Cache size 0 -> 0: maybe_cleared(0), dequeued(0)
[        ] D/GraphicsTracker(21078): maxDqueueCount change 10 -> 11: pending: 0
[   +2 ms] D/CCodecConfig(21078): ignoring local param raw.size (0xd2001800) as it is already supported
[        ] D/CCodecConfig(21078): ignoring local param default.color (0x5200180b) as it is already supported
[        ] D/ReflectedParamUpdater(21078): ignored struct field raw.hdr-static-info.mastering
[        ] I/CCodecConfig(21078): query failed after returning 15 values (BAD_INDEX)
[        ] D/CCodecConfig(21078): c2 config diff is Dict {
[        ] D/CCodecConfig(21078):   c2::u32 algo.low-latency.value = 0
[        ] D/CCodecConfig(21078):   c2::i32 algo.priority.value = 0
[        ] D/CCodecConfig(21078):   c2::float algo.rate.value = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.pl.level = 20496
[        ] D/CCodecConfig(21078):   c2::u32 coded.pl.profile = 20481
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 coded.vui.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 default.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::u32 input.buffers.max-size.value = 7340032
[        ] D/CCodecConfig(21078):   string input.media-type.value = "video/avc"
[        ] D/CCodecConfig(21078):   c2::u32 output.delay.value = 3
[        ] D/CCodecConfig(21078):   string output.media-type.value = "video/raw"
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.matrix = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.primaries = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.range = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.transfer = 0
[        ] D/CCodecConfig(21078):   c2::float raw.hdr-static-info.max-cll = 0
[        ] D/CCodecConfig(21078):   c2::float raw.hdr-static-info.max-fall = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 240
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.width = 320
[        ] D/CCodecConfig(21078):   c2::u32 raw.pixel-format.value = 34
[        ] D/CCodecConfig(21078):   c2::i32 raw.rotation.flip =
[        ] W/ColorUtils(21078): expected specified color aspects (0:0:0:0)
[        ] I/MediaCodec(21078): MediaCodec will operate in async mode
[        ] I/MediaCodec(21078): media_quality service unavailable, skipping updatePictureProfile
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d64ab0, reason connectToSurface
[        ] I/MediaCodec(21078): [c2.exynos.h264.decoder] setting surface generation to 21583875
[        ] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d64ab0, reason connectToSurface(reconnect)
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d64aa0, reason connectToSurface(reconnect-with-listener)
[        ] D/CCodec  (21078): [c2.exynos.h264.decoder] buffers are bound to CCodec for this session
[        ] D/CCodecConfig(21078): no c2 equivalents for csd-1
[        ] D/GraphicsTracker(21078): maxDequeueCount is already 11
[        ] D/CCodecConfig(21078): no c2 equivalents for color-standard
[        ] D/CCodecConfig(21078): no c2 equivalents for native-window
[        ] D/CCodecConfig(21078): no c2 equivalents for native-window-generation
[        ] D/CCodecConfig(21078): no c2 equivalents for flags
[        ] D/GraphicsTracker(21078): maxDequeueCount is already 11
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 default.color.matrix = 1
[        ] D/CCodecConfig(21078):   c2::u32 default.color.primaries = 1
[        ] D/CCodecConfig(21078):   c2::u32 default.color.range = 2
[        ] D/CCodecConfig(21078):   c2::u32 default.color.transfer = 3
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.width = 1632
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.width = 1632
[        ] D/CCodec  (21078): client requested max input size 1331712, which is smaller than what component recommended (7340032); overriding with component recommendation.
[        ] W/CCodec  (21078): This behavior is subject to change. It is recommended that app developers double check whether the requested max input size is in reasonable range.
[        ] D/CCodec  (21078): encoding statistics level = 0
[        ] D/CCodec  (21078): setup formats input: AMessage(what = 0x00000000) = {
[        ] D/CCodec  (21078):   int32_t height = 1080
[        ] D/CCodec  (21078):   int32_t level = 65536
[        ] D/CCodec  (21078):   int32_t max-input-size = 7340032
[        ] D/CCodec  (21078):   string mime = "video/avc"
[        ] D/CCodec  (21078):   int32_t priority = 0
[        ] D/CCodec  (21078):   int32_t profile = 65536
[        ] D/CCodec  (21078):   int32_t width = 1632
[        ] D/CCodec  (21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodec  (21078): }
[        ] D/CCodec  (21078): setup formats output: AMessage(what = 0x00000000) = {
[        ] D/CCodec  (21078):   int32_t android._color-format = 2130708361
[        ] D/CCodec  (21078):   int32_t android._video-scaling = 1
[        ] D/CCodec  (21078):   int32_t rotation-degrees = 0
[        ] D/CCodec  (21078):   int32_t color-standard = 1
[        ] D/CCodec  (21078):   int32_t color-range = 2
[        ] D/CCodec  (21078):   int32_t color-transfer = 3
[        ] D/CCodec  (21078):   float cta861.max-cll = 0.000000
[        ] D/CCodec  (21078):   float cta861.max-fall = 0.000000
[        ] D/CCodec  (21078):   int32_t sar-height = 1
[        ] D/CCodec  (21078):   int32_t sar-width = 1
[        ] D/CCodec  (21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodec  (21078):   int32_t width = 1632
[        ] D/CCodec  (21078):   int32_t height = 1080
[        ] D/CCodec  (21078):   int32_t max-height = 1080
[        ] D/CCodec  (21078):   int32_t max-width = 1632
[        ] D/CCodec  (21078):   string mime = "video/raw"
[        ] D/CCodec  (21078):   int32_t priority = 0
[        ] D/CCodec  (21078):   int32_t android._dataspace = 260
[        ] D/CCodec  (21078):   int32_t color-format = 2130708361
[        ] D/CCodec  (21078): }
[        ] I/CCodecConfig(21078): query failed after returning 15 values (BAD_INDEX)
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 raw.color.matrix = 1
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.primaries = 1
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.range = 2
[        ] D/CCodecConfig(21078):   c2::u32 raw.color.transfer = 3
[        ] E/randroidexample(21078): Failed to query component interface for required system resources: 6
[   +2 ms] D/CCodecConfig(21078): c2 config diff is   c2::u32 raw.crop.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.left = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.top = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.width = 1632
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 1088
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.height = 1088
[        ] D/MediaCodec(21078): keep callback message for reclaim
[        ] D/CCodecBuffers(21078): [c2.exynos.h264.decoder#204:2D-Output] popFromStashAndRegister: at 0us, output format changed to AMessage(what = 0x00000000) = {
[        ] D/CCodecBuffers(21078):   int32_t android._color-format = 2130708361
[        ] D/CCodecBuffers(21078):   int32_t android._video-scaling = 1
[        ] D/CCodecBuffers(21078):   int32_t rotation-degrees = 0
[        ] D/CCodecBuffers(21078):   int32_t color-standard = 1
[        ] D/CCodecBuffers(21078):   int32_t color-range = 2
[        ] D/CCodecBuffers(21078):   int32_t color-transfer = 3
[        ] D/CCodecBuffers(21078):   float cta861.max-cll = 0.000000
[        ] D/CCodecBuffers(21078):   float cta861.max-fall = 0.000000
[        ] D/CCodecBuffers(21078):   int32_t sar-height = 1
[        ] D/CCodecBuffers(21078):   int32_t sar-width = 1
[        ] D/CCodecBuffers(21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodecBuffers(21078):   int32_t width = 1632
[        ] D/CCodecBuffers(21078):   int32_t height = 1088
[        ] D/CCodecBuffers(21078):   int32_t max-height = 1088
[        ] D/CCodecBuffers(21078):   int32_t max-width = 1632
[        ] D/CCodecBuffers(21078):   string mime = "video/raw"
[        ] D/CCodecBuffers(21078):   int32_t priority = 0
[        ] D/CCodecBuffers(21078):   int32_t android._dataspace = 260
[        ] D/CCodecBuffers(21078):   int32_t color-format = 2130708361
[        ] D/CCodecBuffers(21078): }
[   +1 ms] I/randroidexample(21078): Use dataspace 281083904as it is
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] Created input block pool with allocatorID 16 => poolID 19 - OK (0)
[        ] D/Codec2Client(21078): GraphicBufferAllocator created
[        ] I/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] Created output block pool with allocatorID 18 => poolID 220 - OK
[   +1 ms] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] Configured output block pool ids 220 => OK
[        ] D/Codec2Client(21078): setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)
[        ] D/GraphicsTracker(21078): new surface in configuration: maxDequeueRequested(0), maxDequeueCommitted(3)
[        ] D/GraphicsTracker(21078): new surface configured with id:90529320665093 gen:21583875 maxDequeue:3
[        ] D/GraphicsTracker(21078): maxDequeueCount committed to IGBP: 10
[        ] D/GraphicsTracker(21078): Cache size 0 -> 0: maybe_cleared(0), dequeued(0)
[        ] D/GraphicsTracker(21078): maxDqueueCount change 3 -> 10: pending: 0
[        ] D/Codec2Client(21078): setOutputSurface -- generation=21583875 consumer usage=0x900
[   +3 ms] W/randroidexample(21078): AIBinder_linkToDeath is being called with a non-null cookie and no onUnlink callback set. Use AIBinder_DeathRecipient_setOnUnlinked to manage the lifetime of the
cookie. This will become an abort.
[        ] I/chromium(21078): [INFO:CONSOLE:247] "Loading the script 'https://www.googletagmanager.com/gtag/js?id=G-04YGWK0175&cx=c&gtm=4e66o0h1' violates the following Content Security Policy
directive: "script-src 'self'". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback. The policy is report-only, so the violation has been logged but no further
action has been taken.", source: https://www.googletagmanager.com/gtm.js?id=GTM-ND4LWWZ (247)
[   +9 ms] D/CCodecConfig(21078): c2 config diff is   c2::u32 output.delay.value = 4
[        ] D/GraphicsTracker(21078): maxDequeueCount committed to IGBP: 11
[   +2 ms] D/GraphicsTracker(21078): Cache size 1 -> 1: maybe_cleared(0), dequeued(1)
[        ] D/GraphicsTracker(21078): maxDqueueCount change 10 -> 11: pending: 0
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] Ignoring stale input buffer done callback: last flush index = 0, frameIndex = 0
[        ] D/GraphicsTracker(21078): maxDequeueCount is already 11
[   +3 ms] I/flutter (21078): WebView is loading (progress : 100%)
[        ] I/flutter (21078): WebView is loading (progress : 100%)
[   +8 ms] D/GraphicsTracker(21078): maxDequeueCount is already 11
[        ] D/CCodecConfig(21078): c2 config diff is   c2::u32 raw.crop.height = 1080
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.left = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.top = 0
[        ] D/CCodecConfig(21078):   c2::u32 raw.crop.width = 1632
[        ] D/CCodecConfig(21078):   c2::u32 raw.max-size.height = 1088
[        ] D/CCodecConfig(21078):   c2::u32 raw.size.height = 1088
[        ] D/CCodecBuffers(21078): [c2.exynos.h264.decoder#239:2D-Output] popFromStashAndRegister: at 0us, output format changed to AMessage(what = 0x00000000) = {
[        ] D/CCodecBuffers(21078):   int32_t android._color-format = 2130708361
[        ] D/CCodecBuffers(21078):   int32_t android._video-scaling = 1
[        ] D/CCodecBuffers(21078):   int32_t rotation-degrees = 0
[        ] D/CCodecBuffers(21078):   int32_t color-standard = 1
[        ] D/CCodecBuffers(21078):   int32_t color-range = 2
[        ] D/CCodecBuffers(21078):   int32_t color-transfer = 3
[        ] D/CCodecBuffers(21078):   float cta861.max-cll = 0.000000
[        ] D/CCodecBuffers(21078):   float cta861.max-fall = 0.000000
[        ] D/CCodecBuffers(21078):   int32_t sar-height = 1
[        ] D/CCodecBuffers(21078):   int32_t sar-width = 1
[        ] D/CCodecBuffers(21078):   Rect crop(0, 0, 1631, 1079)
[        ] D/CCodecBuffers(21078):   int32_t width = 1632
[        ] D/CCodecBuffers(21078):   int32_t height = 1088
[        ] D/CCodecBuffers(21078):   int32_t max-height = 1088
[        ] D/CCodecBuffers(21078):   int32_t max-width = 1632
[        ] D/CCodecBuffers(21078):   string mime = "video/raw"
[        ] D/CCodecBuffers(21078):   int32_t priority = 0
[        ] D/CCodecBuffers(21078):   int32_t android._dataspace = 260
[        ] D/CCodecBuffers(21078):   int32_t color-format = 2130708361
[        ] D/CCodecBuffers(21078): }
[        ] I/randroidexample(21078): Use dataspace 281083904as it is
[ +125 ms] I/flutter (21078): WebView is loading (progress : 100%)
[   +1 ms] I/flutter (21078): Page finished loading: https://flutter.dev/
[+2841 ms] D/ProfileInstaller(21078): Installing profile for io.flutter.plugins.webviewflutterandroidexample
[ +945 ms] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[        ] D/VRI[FlutterActivity](21078): WindowInsets changed: 2340x1080 statusBars:[0,74,0,0] mandatorySystemGestures:[0,74,0,84]
[        ] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[        ] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(280)] AHB-DIAG [1.raster] OnscreenContextClearCurrent result=1
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[   +3 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[   +6 ms] E/libEGL  (21078): eglMakeCurrentImpl:1100 error 3002 (EGL_BAD_ACCESS)
[        ] E/flutter (21078): [ERROR:flutter/impeller/toolkit/egl/egl.cc(56)] EGL Error: Bad Access (12290) in ../../flutter/impeller/toolkit/egl/context.cc:55
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [randroidexample] OnscreenContextMakeCurrent result=0
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[  +20 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[   +3 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[   +1 ms] E/SurfaceComposerClient(21078): Could not call release buffer callback, buffer not found bufferId:90529320665199 framenumber:104
[   +7 ms] D/FlutterJNI(21078): Sending viewport metrics to the engine.
[   +7 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[   +1 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[  +68 ms] D/InsetsController(21078): hide(ime())
[        ] I/ImeTracker(21078): io.flutter.plugins.webviewflutterandroidexample:eeb38a2f: onCancelled at PHASE_CLIENT_ALREADY_HIDDEN
[   +4 ms] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[        ] E/flutter (21078): [ERROR:flutter/shell/platform/android/android_context_gl_impeller.cc(268)] AHB-DIAG [1.raster] OnscreenContextMakeCurrent result=1
[ +846 ms] D/AidlBufferPool(21078): bufferpool2 0xb400007c45d7bdd8 : 4(29360128 size) total buffers - 4(29360128 size) used buffers - 11/17 (recycle/alloc) - 6/15 (fetch/transfer)
[   +1 ms] D/AidlBufferPoolAcc(21078): evictor expired: 1, evicted: 1
[ +999 ms] D/AidlBufferPool(21078): bufferpool2 0xb400007c45d153f8 : 5(36700160 size) total buffers - 4(29360128 size) used buffers - 10/16 (recycle/alloc) - 6/12 (fetch/transfer)
[   +2 ms] D/AidlBufferPool(21078): bufferpool2 0xb400007c45cf8328 : 5(36700160 size) total buffers - 4(29360128 size) used buffers - 12/17 (recycle/alloc) - 5/15 (fetch/transfer)
[        ] D/AidlBufferPoolAcc(21078): evictor expired: 2, evicted: 2
[+8700 ms] I/randroidexample(21078): hiddenapi: Accessing hidden method Ldalvik/system/VMStack;->getStackClass2()Ljava/lang/Class; (runtime_flags=0, domain=core-platform, api=unsupported) from
LWV/v7; (domain=app, TargetSdkVersion=36) using reflection: allowed
[   +3 ms] E/FilePhenotypeFlags(21078): Config package com.google.android.gms.clearcut_client#io.flutter.plugins.webviewflutterandroidexample cannot use FILE backing without declarative registration.
See go/phenotype-android-integration#phenotype for more information. This will lead to stale flags.
[  +34 ms] E/FilePhenotypeFlags(21078): Config package com.google.android.gms.clearcut_client#io.flutter.plugins.webviewflutterandroidexample cannot use FILE backing without declarative registration.
See go/phenotype-android-integration#phenotype for more information. This will lead to stale flags.
[        ] E/FilePhenotypeFlags(21078): Config package com.google.android.gms.clearcut_client#io.flutter.plugins.webviewflutterandroidexample cannot use FILE backing without declarative registration.
See go/phenotype-android-integration#phenotype for more information. This will lead to stale flags.
[ +451 ms] D/AidlBufferPool(21078): bufferpool2 0xb400007c45d7bdd8 : 5(36700160 size) total buffers - 5(36700160 size) used buffers - 11/18 (recycle/alloc) - 6/15 (fetch/transfer)
[   +1 ms] W/cr_MediaCodecBridge(21078): Releasing: c2.exynos.h264.decoder
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d72c00, reason connectToSurface
[   +1 ms] I/MediaCodec(21078): [c2.exynos.h264.decoder] setting surface generation to 21583876
[        ] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d72c00, reason connectToSurface(reconnect)
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d72bf0, reason connectToSurface(reconnect-with-listener)
[        ] D/Codec2Client(21078): setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)
[   +2 ms] D/GraphicsTracker(21078): new surface in configuration: maxDequeueRequested(0), maxDequeueCommitted(11)
[        ] D/GraphicsTracker(21078): new surface configured with id:90529320665095 gen:21583876 maxDequeue:11
[        ] D/GraphicsTracker(21078): maxDequeueCount is already 11
[        ] D/Codec2Client(21078): setOutputSurface -- generation=21583876 consumer usage=0x900
[        ] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d8ce70, reason disconnectFromSurface
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#204] MediaCodec discarded an unknown buffer
[   +1 ms] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#204] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#204] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#204] MediaCodec discarded an unknown buffer
[        ] W/cr_MediaCodecBridge(21078): Codec released
[        ] D/AidlBufferPool(21078): bufferpool2 0xb400007c45cf8328 : 5(36700160 size) total buffers - 5(36700160 size) used buffers - 12/18 (recycle/alloc) - 5/15 (fetch/transfer)
[        ] W/cr_MediaCodecBridge(21078): Releasing: c2.exynos.h264.decoder
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d72590, reason connectToSurface
[   +2 ms] I/MediaCodec(21078): [c2.exynos.h264.decoder] setting surface generation to 21583877
[   +1 ms] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d72590, reason connectToSurface(reconnect)
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d72580, reason connectToSurface(reconnect-with-listener)
[        ] D/Codec2Client(21078): setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)
[        ] D/GraphicsTracker(21078): new surface in configuration: maxDequeueRequested(0), maxDequeueCommitted(11)
[        ] D/GraphicsTracker(21078): new surface configured with id:90529320665096 gen:21583877 maxDequeue:11
[        ] D/GraphicsTracker(21078): maxDequeueCount is already 11
[        ] D/Codec2Client(21078): setOutputSurface -- generation=21583877 consumer usage=0x900
[        ] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d83a60, reason disconnectFromSurface
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#454] MediaCodec discarded an unknown buffer
[        ] W/cr_MediaCodecBridge(21078): Codec released
[        ] W/cr_MediaCodecBridge(21078): Releasing: c2.exynos.h264.decoder
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d840d0, reason connectToSurface
[        ] I/MediaCodec(21078): [c2.exynos.h264.decoder] setting surface generation to 21583878
[        ] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d840d0, reason connectToSurface(reconnect)
[        ] D/SurfaceUtils(21078): connecting to surface 0xb400007d15d840c0, reason connectToSurface(reconnect-with-listener)
[        ] D/Codec2Client(21078): setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)
[        ] D/GraphicsTracker(21078): new surface in configuration: maxDequeueRequested(0), maxDequeueCommitted(11)
[        ] D/GraphicsTracker(21078): new surface configured with id:90529320665097 gen:21583878 maxDequeue:11
[        ] D/GraphicsTracker(21078): maxDequeueCount is already 11
[        ] D/Codec2Client(21078): setOutputSurface -- generation=21583878 consumer usage=0x900
[        ] D/SurfaceUtils(21078): disconnecting from surface 0xb400007d15d64ab0, reason disconnectFromSurface
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] MediaCodec discarded an unknown buffer
[        ] D/CCodecBufferChannel(21078): [c2.exynos.h264.decoder#239] MediaCodec discarded an unknown buffer