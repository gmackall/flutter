# TLHC PR2: implementation and approach review

- Branch: `tlhc-pr2-pv-hardware-renderer`
- Reviewed source commit: `9467d2a66593ed6680c828173b7d91926ac30219`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment

The dedicated HardwareRenderer provides useful control over frame timestamps and submission. This branch includes PR1; measure its incremental benefit against PR1, not only against the common base.

## Implementation finding: handle SYNC_REDRAW_REQUESTED

`PlatformViewWrapper.postPendingFrame` examines lost-surface and dropped-frame bits but ignores `HardwareRenderer.SYNC_REDRAW_REQUESTED`. Android requires another `syncAndDraw` on the next vsync when this bit is returned, including animations the renderer cannot advance independently. `renderTarget.scheduleFrame()` requests Flutter composition; it does not ensure another native wrapper draw/submission.

Suggestion: schedule a native redraw on the next animation frame when this bit is set. Test this bit both alone and combined with other result bits, and ensure release/backgrounding cancels any scheduled retry. Verify a RenderNode animation progresses without unrelated invalidation.

Reference: https://developer.android.com/reference/android/graphics/HardwareRenderer#SYNC_REDRAW_REQUESTED

## Approach and validation concerns

The ADPF/CPU-placement explanation is a performance hypothesis until supported by device traces. Supplying a better frame timestamp is reasonable, but does not by itself establish that scheduling policy was the bottleneck.

Tests that stub renderer submission establish wrapper control flow, not real HWUI timing. Exercise transparent content, native animations, WebView, resize, surface loss, background/resume, and multiple simultaneous renderers on more than one GPU family. Compare power and sustained behavior as well as peak frame rate.

The front-of-queue submission tradeoff from PR1 still applies. Preserve initial clearing through the dedicated producer and the surface rebinding logic.
