# HCPP and AHB: running issue list

Working notes on Hybrid Composition++ and the AHB Vulkan swapchain that feeds it.
Personal branch, not intended to land.

**Conventions.** Append with the next free `PV-n`; ids are never reused or renumbered.
Move landed items to Resolved rather than deleting them. Evidence is **observed**
(reproduced on device), **derived** (follows from the code), or **suspected** (plausible,
not chased down). References lead with function names; line numbers drift.
Open items are ordered by priority (a judgement call, weighing user impact, evidence and
dependencies), not by id.

Last updated: 2026-10-07.

## Open

| # | Id | Severity | Evidence | Summary |
|---|----|----------|----------|---------|
| 1 | PV-3 / PV-4 | High | derived | Native writes after publish; GC frees a borrowed transaction in use. **In review: #192969** |
| 2 | PV-16 | Medium | derived | Dropped raster transactions leak their completion callback and AHB |
| 3 | PV-1 | High | derived | `swapTransactions()` can promote a later frame's buffers, dropping a frame |
| 4 | PV-11 | High | observed | Swapchain double-buffering (`kMaxPendingPresents = 2u`) causes 30 FPS lockstep stalls |
| 5 | PV-12 | High | observed | Energy-Aware Scheduling strands raster thread on Little cores without ADPF |
| 6 | PV-13 | Medium | observed | AHB swapchain incurs JNI and Java allocation overhead when zero platform views are active |
| 7 | PV-14 | Medium | observed | Cross-stream `tx.merge()` overhead and mutex stalls in `onEndFrame()` |
| 8 | PV-2 | Medium | derived | Platform-view position and crop travel on different channels |
| 9 | PV-17 | Low | derived | HCPP JNI entry points abort the process on Java-side state errors |
| 10 | PV-7 | Unknown | suspected | Overlay z is hardcoded; platform-view z comes from the View hierarchy |
| 11 | PV-8 | Unknown | suspected | Overlay resized in place while the view resize is unlatched |
| 12 | PV-18 | Low | derived | `activeRasterTransactions` is vestigial; collapse swap + end frame |
| 13 | PV-15 | Low | derived | Raster thread can block on the platform thread's monitor |
| 14 | PV-5 | Low | derived | `SurfacePool::ResetLayers()` is unsynchronised |
| 15 | PV-6 | Low | derived | `bringToFront()` on every visible platform view, every frame |
| 16 | PV-19 | Trivial | derived | JNI exception paths have no unit coverage |
| 17 | PV-9 / PV-10 | Trivial | derived | `hidePlatformView` builds a hierarchy to hide it; dead `SurfacePool` methods |

### PV-3 / PV-4 — Ownership of the borrowed transaction

**Active PR:** [#192969](https://github.com/flutter/flutter/pull/192969), the global-ref
direction below, with `submitTransaction` named `publishTransaction` and the raster entry
renamed `createUnpublishedTransaction()`. If publishing fails (`FlutterJNI` collected, or
Java throws), native applies the transaction itself so its completion callback still fires.

`createTransaction()` publishes to the pending list before the native producer is done.
`PlatformViewAndroidJNIImpl::createTransaction` drops its JNI local ref, then `Present` calls
`SetContents()` and `Apply()` through the borrowed pointer — so a platform-thread merge can
race those writes (PV-3). Once `onEndFrame()` drains the list the Java object is unreachable
and the cleaner can free the native transaction while native code still uses it (PV-4).
Declining to `close()` narrows that window; it was never `close()` that created it.

**Fix direction:** native retains a global ref; the raster-side `createTransaction()` stops
publishing; native calls a new `submitTransaction(tx)` after `Apply()`, which adds it under
the lock and releases the ref. One extra JNI call per present, explicit ownership transfer.

**Alternative fix direction (unidirectional pre-vended pool):** Keep a pool of
transactions pre-vended by Java to C++ ahead of time. When the raster thread
presents, it grabs a ready transaction directly in C++ without a synchronous JNI
callback. Once `Apply()` finishes, native code hands the populated transaction to Java
(or queues it), making the dependency strictly one-way (C++ → Java) and eliminating
the mid-frame JNI invocation.

This also removes PV-15 outright, since the raster thread stops taking the lock mid-frame.
Open question for it and for the caching variant below: whether the pool can be refilled
without the platform thread becoming the bottleneck again when it stalls — an empty pool
needs a fallback, and the fallback is the JNI path it was meant to avoid.

### PV-16 — Dropped raster transactions leak their completion callback and buffer

`SurfaceTransaction::Apply()` registers its completion callback with a heap context that is
freed only when the callback runs, and the callback holds the presented
`AHBTextureSourceVK`. Java drops raster transactions without applying them in three places:
`dropTransactions()` on detach, `swapTransactions()` clearing an active list whose
`onEndFrame()` never ran (PV-18), and `onEndFrame()` closing the merged transaction when the
root surface control is gone. A transaction that is never applied never completes, so the
context and its hardware buffer leak. The pool allocates a replacement, so nothing stalls
and the leak is silent.

Detach is the reachable path: add-to-app, and apps that attach and detach `FlutterView`
repeatedly, lose roughly one full-screen buffer per dropped present. #192969 applies natively
when publishing fails for the same reason; these paths have the same problem on the Java
side.

**Fix direction:** apply rather than drop, even when the target is going away, so the
callback runs and the texture returns to the pool. **Confirm:** an unapplied transaction freed
by the cleaner does not fire its completion callbacks; then count AHB allocations across
repeated attach/detach.

### PV-1 — Raster run-ahead breaks frame attribution

Suspected cause of the intermittent jitter. `Present` never blocks (`AHBTexturePoolVK::Pop`
allocates when the pool is empty) and `SubmitFlutterView` posts the platform task without
waiting, so the raster thread can be a frame ahead. `swapTransactions()` then promotes
buffers from both frames, `onEndFrame()` merges them, and two `setBuffer` calls on one
`SurfaceControl` mean the last wins: frame N is dropped and N+1's content is shown against
N's clips.

Platform-thread lag is the trigger, so this and PV-13 are the same problem from two ends.
The coupling may be inherent; discarding frames is not.

**Interacts with PV-11.** The window is bounded by `kMaxPendingPresents`, today 2 — enough
to overlap one frame. Raising it to 3 to fix PV-11's lockstep stall widens this window.
The two should be landed together, or PV-1 fixed first.

**Confirm:** trace counter on `pendingRasterTransactions.size()` at the top of
`swapTransactions()`. Above one present per frame means this is happening.

**History (derived).** The pending/active split was originally this guard. Jonah's first
platform task (#162493) called `swapTransaction()` first and `onEndFrame2()` last, so
anything the raster thread published while the task ran stayed pending for the next frame.
Platform-thread transactions shared that list, though, and any created after the swap
lagged a frame: #176742 moved overlay show/hide ahead of it, and #189946 moved the swap to
directly before `onEndFrame2()`. Right trade: a one-frame lag on every frame versus a race
window one platform task long. But it closed the window entirely (see PV-18), and swap-first
never covered the larger case anyway: a platform task queued behind a raster thread that is
already ahead.

**Fix:** neither ordering gives real frame isolation. That needs transactions tagged with
their frame, with `onEndFrame(N)` taking only the raster transactions up to N and leaving
later ones pending. Now that platform transactions have their own slot (#192606), that's
straightforward. Fold it into the PV-18 cleanup, gated on the counter below actually showing
run-ahead. Composes with the PV-3/PV-4 fix.

**Possibly worse (suspected).** With two overlay presents in one merged transaction, both
`OnTextureUpdatedOnSurfaceControl` callbacks fire for a single latch. If they run out of
order, `currently_displayed_texture_` records N while the surface shows N+1, and N+1 goes
back to the pool while on screen: the next render into it would tear. Depends on callback
order within a merged transaction; unverified.

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

**See PV-1:** raising the slot count also widens the frame-attribution window.

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

**Mechanism, and why it is bigger than the allocation cost (derived).**
`SurfaceTransaction::Apply()` returns early *without applying* when the transaction came
from Java, so nothing is shown until the platform task runs `onEndFrame()` →
`applyTransactionOnDraw()`. Presentation of every HCPP frame therefore waits on the platform
thread, which also runs plugin channel handlers — putting third-party code on the present
path even in apps with no platform views. Note `AttachCurrentThread` is a cached `GetEnv`
fast path, so the per-frame attach cost cited above is closer to zero than the description
suggests; the coupling is the real cost. With platform views the coupling is inherent —
`applyTransactionOnDraw` has no NDK equivalent and only `ViewRootImpl` knows when the draw
commits. **Confirm:** per-frame gap between `Present` and `applyTransactionOnDraw`.

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

**Conflicts with #192606.** That PR keeps the separate merge destination deliberately:
merging into a raster input and closing it could free a native pointer the producer is still
using (PV-3/PV-4). A unified per-frame transaction needs the ownership handoff first, or it
reintroduces that hazard. Since merges happen on the platform thread, this also feeds PV-13.

### PV-2 — Position and crop travel on different channels

In one platform task, `onDisplayPlatformView` sets geometry via `readyToDisplay()` →
`setLayoutParams()` (View layout pipeline) and clipping via `maybeApplyClipToSurfaceView()` →
`setCrop()` (SurfaceControl transaction). They coincide only because both usually land in the
same traversal, and a SurfaceView's position is additionally updated by the View system's own
transaction. Same failure mode as #189946, on the channel that fix didn't cover.

### PV-17 — HCPP JNI entry points abort the process on Java-side state errors

`FlutterJNI.swapTransactions()` and `endFrame2()` throw `RuntimeException("")` when
`platformViewsController2` is null, and their native callers
`FML_CHECK(CheckException(env))`, which aborts in release. #192969 hardened the transaction
entry points (exceptions are cleared and fall back to a native apply); the rest of the HCPP
JNI surface wasn't audited. Not known to be reachable, but the failure mode is a process kill
and the fix is cheap: log and return, and give the exceptions a message.

### PV-7 — Overlay z-order is hardcoded

`createOverlaySurface()` pins the overlay with `setLayer(1000)` while platform-view
SurfaceViews get layers from the View hierarchy. Nothing coordinates the two. **Confirm:**
`adb shell dumpsys SurfaceFlinger` while the glitch is visible.

### PV-8 — Overlay resize versus view resize

Since #190638 the overlay is resized in place by `SurfacePool::GetLayer` rather than
destroyed, while `MaybeResizeSurfaceView` is posted without a latch — deliberately, to avoid
the deadlock that PR fixed. For a frame or two after a size change the overlay can present at
the old size. Start here if the jitter is worse right after rotation.

### PV-18 — `activeRasterTransactions` is vestigial

Since #189946, `swapTransaction()` and `onEndFrame2()` run back to back in the same platform
task at both call sites, so the active list is filled and drained immediately. The
"previous `onEndFrame()` never ran" cleanup in `swapTransactions()` is reachable only if
something fails between those two adjacent calls. See PV-1 for how it got this way.

**Fix direction:** collapse into one `applyFrame()`: drain pending under the lock, merge with
the platform transaction into a fresh destination, `applyTransactionOnDraw`. Removes both
active slots, one JNI call per frame, and one of PV-16's drop paths. Decide alongside PV-1,
since frame tagging changes what "drain" means. Pairs naturally with PV-14.

### PV-15 — Raster thread blocks on the platform thread's monitor

`createTransaction()` takes `transactionLock` on the raster thread, while the platform thread
holds it across `swapTransactions()` — including a native `close()`. A high-priority raster
thread waiting on a normal-priority platform thread is priority inversion, and it bites
exactly when the platform thread is busy. Small today, but new with the locking added in
#192606, and it sits on the present path. Only the pre-vended pool under PV-3/PV-4 removes it.

**Update (2026-10-07).** #192969 takes the global-ref direction, which keeps the lock:
`publishTransaction()` takes `transactionLock` on the raster thread once per present. The
platform thread now holds it only to move list entries (the platform-transaction `close()`
is outside it), so the hold is short. Low until a trace says otherwise.

### PV-5 — `SurfacePool::ResetLayers()` is unsynchronised

The only method in the class that doesn't take `mutex_`, though it writes
`available_layer_index_` from the raster thread every frame. `GetLayer` is called from the
platform thread in the overlay-creation path, where a latch orders it in practice — so
latent, not active. Looks like an oversight next to `RecycleLayers`.

### PV-6 — `bringToFront()` every frame

`onDisplayPlatformView` calls it for every visible platform view on every frame; each call
reorders the child array and requests a layout. Present since #161829, so not a regression —
but it is on the critical path (PV-13) and widens the PV-1 window.

### PV-19 — JNI exception paths have no unit coverage

`MockJNIEnv` doesn't implement the JNI calls `fml::jni::CheckException` makes to log, so the
throw and fallback branches added in #192969 are untested. Extending the mock would cover
them and PV-17's fix.

### PV-9 / PV-10 — Minor

`hidePlatformView` calls `initializePlatformViewIfNeeded`, so hiding a view with no parent
builds and attaches the hierarchy purely to set it `GONE`. `RecycleLayers()`, `TrimLayers()`
and `GetUnusedLayers()` are unused by `AndroidExternalViewEmbedder2`.

## Directions

Not defects — design options.

- **Cache two long-lived transactions.** `SurfaceControl.Transaction` is reusable (`apply()`
  and `merge()` clear the source, leaving the object intact) and the code already ping-pongs
  two queues. Allocate two at setup, cache both `ASurfaceTransaction*` handles natively, write
  into the current parity. A fixed-size variant of the pre-vended pool under PV-3/PV-4, with
  no refill path to starve. **Prerequisite:** confirm on device that the pointer from
  `ASurfaceTransaction_fromJava` stays valid across `apply()` and `merge()`. Worth asking
  Android to document that guarantee.
- **Per-view promotion instead of whole-view fallback.** Today one `SurfaceView` anywhere in
  the subtree (`VIEW_TYPES_REQUIRE_NON_TLHC`) forces the entire view off the texture path.
  Promoting only SurfaceView descendants to their own SurfaceControls would keep TLHC for the
  rest. Hard parts: z-order, hit-testing and clip inheritance across two mechanisms. Note that
  SurfaceView content cannot be captured into a Flutter texture at all — the producer owns the
  BufferQueue — so anything composited as a layer is hybrid composition by definition.
- **`SurfaceControlViewHost`.** Generalises promotion to arbitrary views: the subtree gets its
  own `ViewRootImpl` and Flutter places the resulting `SurfacePackage` from its own transaction.
  Doesn't remove the platform thread from the frame, but removes the need for atomicity with
  the app's View draw — a route out of `applyTransactionOnDraw` needing nothing from Android.
  Cost is input, focus, IME and accessibility across the boundary.

## Resolved

| PR | Summary |
|----|---------|
| #192606 | Transaction-list race: raster adds raced platform swaps, leaving null entries and a `merge(null)` abort. Also split the raster/platform entry points. |
| #190638 | Rotation ANR: platform↔raster deadlock from `DestroySurfaces()` on resize. See PV-8 for the follow-on question. |
| #190612 | `onEndFrame` guarded against a detached `FlutterView`. |
| #190311 | 1px edge clipping from truncating instead of rounding physical pixels. |
| #189946 | Clip rect behind by one frame: `swapTransaction()` ran before `onDisplayPlatformView2()`. See PV-2 for the channel this did not cover. |
| #181009 | Flicker when scrolling off/on screen: apply with Flutter frame timing instead of immediately. |

## Diagnostics

- **Pending-transaction counter** in `swapTransactions()` — tests PV-1, near-zero cost.
- **Perfetto**, using the existing `SubmitFlutterView` marker: the gap between raster submit
  and the platform task, relative to vsync. Tests PV-13.
- **AHB count across attach/detach** (`dumpsys meminfo`, graphics) — tests PV-16.
- **`dumpsys SurfaceFlinger`** — relative z and buffer sizes of overlay versus platform-view
  controls. Tests PV-7 and PV-8.
- **Robolectric harness** — `PlatformViewsController2Test` runs standalone against the engine
  sources in a scratch Gradle project. Java ordering only, nothing native. On Windows `TMP`
  and `TEMP` must be short paths (`C:\Temp`) or Gradle can't open its loopback connection.

## Caveat

PV-11 through PV-14 are device-measured. Everything else is static analysis: not watched on a
device, and PV-2 and PV-7 turn on View-system behaviour subtle enough that they shouldn't be
acted on without a trace.
