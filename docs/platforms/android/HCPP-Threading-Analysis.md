# Hybrid Composition++ (HCPP) Threading Model Analysis & Speculative Fixes

## 1. Background & HC vs HCPP Threading Model

### Hybrid Composition (HC)
In original Hybrid Composition (HC), the Flutter Engine uses `RasterThreadMerger` to merge the **Raster Task Runner** onto the **Platform Task Runner** (Android main UI thread) whenever platform views are active in a scene.
- **Single-Threaded Invariant**: Under HC, frame rasterization (`SubmitFlutterView`), JNI callbacks (`FlutterViewOnDisplayPlatformView`), Android View hierarchy manipulations (`FlutterMutatorView`), and Dart platform channel calls (`create`, `dispose`, `resize`, `touch`) all execute sequentially on the Android UI thread.
- Data structures (e.g. `SparseArray`, `ArrayList`, layout params) in `PlatformViewsController` did not require thread synchronization because concurrent execution between rasterization and the UI thread was impossible by design.

### Hybrid Composition++ (HCPP)
HCPP replaces the view slicing and image-view overlay architecture of HC with Android 14+ (`API 34`) `SurfaceControl` transactions and Vulkan swapchain synchronization (`AHBSwapchainVK`).
- **Concurrent Threading Invariant**: In HCPP (`AndroidExternalViewEmbedder2` / `PlatformViewsController2`), **threads are never merged**.
- The **Raster Thread** renders scene underlays/overlays with Impeller/Vulkan and acquires `SurfaceControl.Transaction`s from swapchain presentation callbacks.
- The **Platform Thread** handles the Android UI view hierarchy, gesture inputs, platform channel invocations, and window lifecycle.

---

## 2. Identified Concurrency & Thread-Safety Issues

### Issue 1: `SurfaceControl.Transaction` Data Race & Missing Synchronization
* **Mechanism**:
  `PlatformViewsController2.createTransaction()` appends to `pendingTransactions` (`ArrayList<SurfaceControl.Transaction>`). This is called concurrently from:
  1. The **Raster Thread** via Impeller Vulkan swapchain presentation (`AHBSwapchainImplVK` -> `PlatformViewAndroidJNIImpl::createTransaction` -> `FlutterJNI.createTransaction`).
  2. The **Platform/UI Thread** via `maybeApplyClipToSurfaceView`, `createSurfaceClipCallback`, `showOverlaySurface`, and `hideOverlaySurface`.
  Meanwhile, `swapTransactions()` and `onEndFrame()` drain and clear `pendingTransactions` on the Platform Thread.
* **Failure Mode**: `ConcurrentModificationException`, corrupted backing arrays, or dropped `SurfaceControl` transactions causing dropped frame clips and visual tears.
* **Reproduction**:
  Run an animated platform view (e.g. video player or camera) with continuous size, clip, or opacity animations alongside high-framerate (60/120fps) Flutter rendering. The concurrent calls to `createTransaction()` will race with transaction swapping.

### Issue 2: Lifecycle Race on View Disposal / Detachment during Frame Submission
* **Mechanism**:
  In HCPP, `SubmitFlutterView` on the Raster Thread finishes frame composition and posts a task containing `onDisplayPlatformView2` and `hidePlatformView2` to the Platform Task Runner.
  Between frame submission on the raster thread and task execution on the UI thread, a view can be destroyed (`dispose(viewId)`) or detached (`detachFromView()`).
* **Failure Mode**: Calling `readyToDisplay()` or `setVisibility()` on a null `parentView` in `PlatformViewsController2` causes a `NullPointerException`, which the C++ JNI boundary escalates into a fatal abort (`FML_CHECK(fml::jni::CheckException(env))`).
* **Reproduction**:
  Rapidly push and pop routes containing a Platform View during active animations, causing the raster thread to submit a frame for a `viewId` while the UI thread simultaneously processes widget disposal.

### Issue 3: Fatal Abort and Null Dereference on Detached Overlay Surface Creation
* **Mechanism**:
  When `createOverlaySurface()` is invoked while `FlutterView` is detached from its window (e.g. during activity recreation or split-screen transitions), `flutterView.getRootSurfaceControl()` returns `null`. `SurfacePool::GetLayer` in C++ unconditionally checked `FML_CHECK(java_metadata->window)` and `ExternalViewEmbedder2` dereferenced `layer->surface` without checking for `nullptr`.
* **Failure Mode**: Immediate crash on `FML_CHECK(java_metadata->window)` or `SIGSEGV` when dereferencing `layer->surface`.
* **Reproduction**:
  Display a Platform View with an active Flutter overlay layer and trigger an activity rotation or split-screen mode change.

---

## 3. Speculative Fixes Applied

1. **Synchronized SurfaceControl Transactions ([PlatformViewsController2.java](file:///Users/mackall/development/flutter/engine/src/flutter/shell/platform/android/io/flutter/plugin/platform/PlatformViewsController2.java))**:
   Synchronized `createTransaction()`, `applyTransactions()`, `swapTransactions()`, and `onEndFrame()` to guarantee thread safety across Raster and UI threads.
2. **Defensive Lifecycle Guards ([PlatformViewsController2.java](file:///Users/mackall/development/flutter/engine/src/flutter/shell/platform/android/io/flutter/plugin/platform/PlatformViewsController2.java))**:
   Added null checks for `parentView`, `platformViews`, and `flutterJNI` in `onDisplayPlatformView`, `hidePlatformView`, and `SurfaceHolder.Callback`.
3. **Safe Null Overlay Handling ([surface_pool.cc](file:///Users/mackall/development/flutter/engine/src/flutter/shell/platform/android/external_view_embedder/surface_pool.cc) & [external_view_embedder_2.cc](file:///Users/mackall/development/flutter/engine/src/flutter/shell/platform/android/external_view_embedder/external_view_embedder_2.cc))**:
   Replaced the fatal `FML_CHECK(java_metadata->window)` with a safe null check and warning log, and guarded against null `layer` / `overlay_frame` access during overlay rendering.
