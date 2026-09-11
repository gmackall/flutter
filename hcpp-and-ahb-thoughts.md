# HCPP and AHB: running issue list

Working notes on Hybrid Composition++ (Android 14+ `SurfaceControl` platform views) and
the AHB Vulkan swapchain that feeds it. This is a personal branch, not intended to land.

## How to use this file

Append new findings to **Open issues** with the next free `PV-n` id. Ids are never
reused or renumbered, so they stay quotable across branches and PRs. When something
lands, move it to **Resolved** with the PR number rather than deleting it — the
history of what was already ruled out is most of the value here.

Every entry carries an evidence level, because the difference between "I read this in
the code" and "I watched this happen on a device" matters a lot for prioritisation:

- **observed** — reproduced on a device, with a trace or video.
- **derived** — follows from reading the code; the mechanism is understood but has not
  been caught in the act.
- **suspected** — plausible from the code, not yet traced through end to end.

Code references use function names first and line numbers only as a hint, since the
latter drift. Line numbers for `PlatformViewsController2.java` refer to the version on
`isolate-hcpp-transactions` (PR #192606), which moves things around a little.

Last updated: 2026-09-11.

---

## Open issues

| Id | Severity | Evidence | Summary |
|----|----------|----------|---------|
| PV-1 | High | derived | Raster thread runs ahead; `swapTransactions()` can promote a later frame's buffers |
| PV-2 | Medium | derived | Platform-view position and crop travel on different channels |
| PV-3 | High | derived | Native writes to a transaction after it is published to Java |
| PV-4 | High | derived | GC can free a borrowed native transaction still in use |
| PV-5 | Low | derived | `SurfacePool::ResetLayers()` is unsynchronised |
| PV-6 | Low | derived | `bringToFront()` on every visible platform view, every frame |
| PV-7 | Unknown | suspected | Overlay z is hardcoded; platform-view z comes from the View hierarchy |
| PV-8 | Unknown | suspected | Overlay resized in place while the view resize is unlatched |
| PV-9 | Trivial | derived | `hidePlatformView` builds a view hierarchy in order to hide it |
| PV-10 | Trivial | derived | Dead `SurfacePool` methods on the HCPP path |
| PV-11 | High | observed | Swapchain double-buffering (`kMaxPendingPresents = 2u`) causes 30 FPS lockstep stalls |
| PV-12 | High | observed | Energy-Aware Scheduling strands raster thread on Little cores without ADPF |
| PV-13 | Medium | observed | AHB swapchain incurs JNI and Java allocation overhead when zero platform views are active |
| PV-14 | Medium | observed | Cross-stream `tx.merge()` overhead and mutex stalls in `onEndFrame()` |

### PV-1 — Raster run-ahead breaks frame attribution

**Severity:** high. **Evidence:** derived. **Suspected cause of:** intermittent jitter
of Flutter content relative to platform views, worse under load.

The design assumes frame N's buffers and frame N's platform geometry meet in the same
swap. Nothing enforces it.

- `AHBSwapchainImplVK::Present` never blocks. `AHBTexturePoolVK::Pop` allocates a new
  texture when the pool is empty rather than waiting for one to be released.
- `AndroidExternalViewEmbedder2::SubmitFlutterView` posts the platform task and returns.
  The raster thread is then free to rasterise and submit frame N+1.
- `PlatformViewsController2.swapTransactions()` promotes *everything* pending. It has no
  notion of which frame a transaction belongs to.

So when the platform thread is busy — layout, view inflation, GC — and slips past the
next raster submit, the platform task for frame N swaps in buffers from both N and N+1.
`onEndFrame()` merges both into one transaction, and two `setBuffer` calls on the same
`SurfaceControl` mean the last one wins. Frame N's buffer is dropped, and N+1's content
is displayed against the clips, crops and positions computed for frame N.

Content and platform views separate by exactly one frame, for exactly one frame. That
matches "mostly fine, occasionally jitters" far better than a persistent offset would.

**How to confirm:** add a trace counter for `pendingRasterTransactions.size()` at the
top of `swapTransactions()`. If it ever exceeds the number of surfaces presented in one
frame, this is happening. Correlate with the `SubmitFlutterView` trace markers in
Perfetto during a scroll and the platform task should be visibly sliding past a raster
submit.

**Fix direction:** tag transactions with a frame id at creation; `swapTransactions()`
promotes only the frame it belongs to and leaves later ones pending. This composes with
the fix for PV-3 and PV-4, since both want the publication point to be explicit rather
than implied by "whatever is in the list right now".

### PV-2 — Position and crop travel on different channels

**Severity:** medium. **Evidence:** derived.

For a single platform view in a single platform task, `onDisplayPlatformView`:

- calls `readyToDisplay()` → `setLayoutParams()`, which goes through the **View layout
  pipeline**, and
- calls `maybeApplyClipToSurfaceView()` → `setCrop()`/`setAlpha()`, which goes into the
  **SurfaceControl transaction**.

These coincide only because both usually land in the same traversal. A SurfaceView's
position is additionally updated by the View system's *own* transaction, not ours.

This is the same failure mode that #189946 fixed, on the channel that fix did not cover.
Worth re-checking whenever the ordering inside the platform task changes.

### PV-3 — Native writes after publication

**Severity:** high. **Evidence:** derived. Documented in code on the PR #192606 branch.

`PlatformViewsController2.createTransaction()` adds the transaction to the pending list
before the native producer has finished with it. `PlatformViewAndroidJNIImpl::createTransaction`
converts it with `ASurfaceTransaction_fromJava` and drops its JNI local ref, then
`AHBSwapchainImplVK::Present` calls `SetContents()` and `Apply()` through that borrowed
pointer. A platform-thread merge can race those writes.

Locking the *lists* does not help here; the race is on the transaction object.

**Fix direction:** see PV-4 — same fix.

### PV-4 — GC can free a borrowed native transaction

**Severity:** high. **Evidence:** derived.

Once `onEndFrame()` drains the list, the Java transaction becomes unreachable and the
`NativeAllocationRegistry` cleaner can free the native transaction at the next GC —
while native code may still be writing through the borrowed pointer. Declining to call
`close()` narrows the window but does not close it, because it was never `close()` that
created the hazard.

**Fix direction for PV-3 and PV-4 together:** native retains a global ref to the
transaction; the raster-side `createTransaction()` stops publishing; native calls a new
`submitTransaction(tx)` after `Apply()`, which adds it to the list under the lock and
releases the ref. One extra JNI call per present, and the ownership transfer becomes
explicit.

**Alternative fix direction (unidirectional pre-vended pool):** Keep a pool of
transactions pre-vended by Java to C++ ahead of time. When the raster thread
presents, it grabs a ready transaction directly in C++ without a synchronous JNI
callback. Once `Apply()` finishes, native code hands the populated transaction to Java
(or queues it), making the dependency strictly one-way (C++ → Java) and eliminating
the mid-frame JNI invocation.

### PV-5 — `SurfacePool::ResetLayers()` is unsynchronised

**Severity:** low. **Evidence:** derived. Latent rather than active.

`ResetLayers()` is the only method in `SurfacePool` that does not take `mutex_`. It
writes `available_layer_index_` from the raster thread every frame, while every other
accessor locks. `GetLayer` is called from the platform thread in the overlay-creation
path in `SubmitFlutterView`, though the latch there orders it in practice.

Looks like an oversight from when `ResetLayers` was added next to `RecycleLayers`.

### PV-6 — `bringToFront()` every frame

**Severity:** low on its own; feeds PV-1. **Evidence:** derived.

`onDisplayPlatformView` calls `parentView.bringToFront()` and `view.bringToFront()` for
every visible platform view on every frame. Each call reorders the parent's child array
and requests a layout, so every HCPP frame with a platform view forces a measure/layout
pass on the platform thread.

Present since the original HCPP class (#161829), so not a regression — but it makes the
platform task slower, which is exactly what widens the PV-1 window.

### PV-7 — Overlay z-order is hardcoded

**Severity:** unknown. **Evidence:** suspected.

`createOverlaySurface()` reparents the overlay and pins it with `setLayer(1000)`, while
platform-view SurfaceViews get their layers from the View hierarchy. Nothing coordinates
the two. If a SurfaceView ever lands at or above 1000, the overlay goes behind the
platform view.

**How to confirm:** `adb shell dumpsys SurfaceFlinger` while the glitch is visible, and
check the relative z actually holds.

### PV-8 — Overlay resize versus view resize

**Severity:** unknown. **Evidence:** suspected. Rotation-specific.

Since #190638 the overlay is no longer destroyed on size change; `SurfacePool::GetLayer`
resizes the swapchain in place via `OnScreenSurfaceResize`. Meanwhile `PrepareFlutterView`
posts `MaybeResizeSurfaceView` without a latch — deliberately, to avoid the deadlock that
PR fixed. For a frame or two after a size change the overlay can therefore present at the
old size while the view resizes independently.

Dropping `DestroySurfaces()` was the right call; this is about what replaced it. If the
jitter is worse immediately after rotation, start here.

### PV-9 — `hidePlatformView` initialises in order to hide

**Severity:** trivial. **Evidence:** derived.

`hidePlatformView` calls `initializePlatformViewIfNeeded`, so hiding a view that has no
parent yet builds and attaches the entire parent hierarchy purely to set it `GONE`.

### PV-10 — Dead `SurfacePool` methods on the HCPP path

**Severity:** trivial. **Evidence:** derived.

`RecycleLayers()`, `TrimLayers()` and `GetUnusedLayers()` are unused by
`AndroidExternalViewEmbedder2`; only the legacy embedder calls `RecycleLayers()`.

### PV-11 — Swapchain double-buffering causes 30 FPS / lockstep stalls

**Severity:** high. **Evidence:** observed. **Impact:** severe frame-rate degradation under bursty rasterization.

In `AHBSwapchainImplVK`, `kMaxPendingPresents` is set to `2u`. This leaves only two
in-flight buffer slots between GPU execution and SurfaceFlinger composition.

When GPU work takes slightly longer than 1 vsync cadence, or when SurfaceFlinger
delays latching by even a fraction of a frame, `AcquireNextDrawable` immediately blocks
waiting on the GPU fence and compositor release fence of the older buffer. This
serializes the raster thread directly behind display composition, locking rendering into
a 30 FPS (or multi-frame) lockstep.

**Fix direction:** Bump `kMaxPendingPresents` from 2 to 3 (triple buffering). This
absorbs VSYNC cadence jitter and GPU fence backpressure in `AcquireNextDrawable`,
preventing the raster thread from hard-stalling into lockstep during bursty raster work.
Tested on Pixel 7 Pro alongside ADPF.

**Branch:** [optimize-ahb-with-adpf](https://github.com/flutter/flutter/compare/master...gmackall:flutter:optimize-ahb-with-adpf).

### PV-12 — Energy-Aware Scheduling strands raster thread on Little cores without ADPF

**Severity:** high. **Evidence:** observed. **Tracking issue:** [#114835](https://github.com/flutter/flutter/issues/114835).

Modern Android kernels (API 30+) use Energy-Aware Scheduling (EAS) in conjunction with
PowerHAL. Traditional POSIX `setpriority` / `sched_setaffinity` calls do not reliably
signal computational demand to the kernel scheduler, frequently leaving the Flutter
raster thread stranded on low-frequency Little (efficiency) cores during bursty or
complex UI frames.

Because EAS optimizes for power minimization until deadline misses occur, frames miss
their vsync budget before the scheduler decides to migrate the thread to Big or Middle
performance cores.

**Fix direction:** Integrate the Android Dynamic Performance Framework (ADPF).
Implement `AndroidPerformanceHintManager` wrapping the NDK `APerformanceHintSession`
API (`<android/performance_hint.h>`) for UI and Raster threads. Reporting actual work
duration per frame allows the Linux kernel and PowerHAL scheduler to proactively scale
CPU frequency and migrate render threads to performance cores under load.

**Branch:** [optimize-ahb-with-adpf](https://github.com/flutter/flutter/compare/master...gmackall:flutter:optimize-ahb-with-adpf).

### PV-13 — AHB swapchain incurs JNI and Java allocation overhead when zero platform views are active

**Severity:** medium. **Evidence:** observed. **Active PR:** [#192083](https://github.com/flutter/flutter/pull/192083).

When no platform views are present on screen, Impeller's `AHBSwapchainImplVK` still
executes the full HCPP JNI round-trip on every frame: `(cb_)()` calls
`FlutterJNI.createTransaction()` → `PlatformViewsController2.createTransaction()`.

This attaches the raster thread to the JVM, allocates Java `SurfaceControl.Transaction`
objects, and exercises `transactionLock` synchronization on the raster thread, even
though standard rendering without platform views requires none of this coordination.
This introduces a measurable raster-time regression for pure Flutter rendering compared
to direct NDK swapchain presentation.

**Fix direction:** Decouple the swapchain when platform view count is zero. When no
platform views are registered or active, the JNI callback returns `nullptr`, and
`AHBSwapchainImplVK` creates an NDK `ASurfaceTransaction` directly in C++. This claws
back the raster time regression for standard rendering while preserving active platform
view behavior.

**Branch:** [optimize-ahb-swapchain-no-pv](https://github.com/flutter/flutter/compare/master...gmackall:flutter:optimize-ahb-swapchain-no-pv).

### PV-14 — Cross-stream transaction merging (`tx.merge()`) overhead in `onEndFrame()`

**Severity:** medium. **Evidence:** observed. **Impact:** ~35% dropped frame reduction on Pixel 7 Pro when eliminated.

In `PlatformViewsController2.onEndFrame()`, transactions from the platform thread
(clips, overlays) and raster presentations are merged via `tx.merge()` before passing to
`rootSurfaceControl.applyTransactionOnDraw(tx)`.

`SurfaceControl.Transaction.merge()` calls down into AOSP `libgui.so`'s
`SurfaceComposerClient::Transaction::merge()`, which acquires internal mutexes and
iterates over layer state arrays (`mComposerStates`). Under multiple platform views and
high frame rates, these native merges introduce significant CPU time and mutex stalls on
the platform thread.

While PR #192606 consolidated platform-thread mutations into a single transaction, the
cross-stream merge into a separate destination transaction still occurs. Eliminating
intermediate merges entirely by consolidating directly into a unified per-frame
transaction dropped frame drops by ~35% on `platform_views_hcpp_scroll_perf` on Pixel 7 Pro.

**Fix direction:** Consolidate all frame mutations directly into a single per-frame
transaction passed straight to `applyTransactionOnDraw()`, or coordinate a unified
transaction stream across C++ and Java.

**Branch:** [optimize-hcpp-transactions](https://github.com/flutter/flutter/compare/master...gmackall:flutter:optimize-hcpp-transactions).

---

## Resolved

| Id | PR | Summary |
|----|----|---------|
| — | #192606 | Transaction-list race: raster adds raced platform swaps, leaving null entries and a `merge(null)` abort. Also split the raster/platform entry points. |
| — | #190638 | Rotation ANR: platform↔raster deadlock from `DestroySurfaces()` on resize. See PV-8 for the follow-on question. |
| — | #190612 | `onEndFrame` guarded against a detached `FlutterView`. |
| — | #190311 | 1px edge clipping from truncating instead of rounding physical pixels. |
| — | #189946 | Clip rect behind by one frame: `swapTransaction()` ran before `onDisplayPlatformView2()`. See PV-2 for the channel this did not cover. |
| — | #181009 | Flicker when scrolling off/on screen: apply with Flutter frame timing instead of immediately. |

---

## Diagnostics worth keeping around

- **Pending-transaction counter.** Trace counter on `pendingRasterTransactions.size()` at
  the top of `swapTransactions()`. Directly tests PV-1 and costs almost nothing.
- **Perfetto with the existing markers.** `SubmitFlutterView` already has a
  `TRACE_EVENT0`. What matters is the *gap* between the raster submit and the platform
  task running, relative to vsync.
- **`dumpsys SurfaceFlinger`.** Relative z and actual buffer sizes of the overlay versus
  the platform-view SurfaceControls. Tests PV-7 and PV-8.
- **Robolectric harness.** `PlatformViewsController2Test` runs standalone against the
  engine sources in a scratch Gradle project — useful for the Java-side ordering, useless
  for anything native. On Windows, `TMP`/`TEMP` must be a short path (`C:\Temp`) or Gradle
  cannot open its loopback connection.

## Caveats

All of the above is static analysis unless marked otherwise. None of it has been watched
on a device. The interaction between `applyTransactionOnDraw` and the View system's own
SurfaceView transactions is subtle enough that PV-2 and PV-7 in particular should not be
acted on without a trace first.
