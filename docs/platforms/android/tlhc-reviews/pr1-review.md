# TLHC PR1: implementation and approach review

- Branch: `tlhc-pr1-defer-frame-and-offset-relayout`
- Reviewed source commit: `538314f6eff47a2a5e9004d7707689a07c4fe46d`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment

The deferred submission and initial-size ordering are focused improvements. No concrete correctness blocker was identified in this source review. Pending canvas ownership is explicitly handled across repeated draws, resize, release, and failed unlock.

## Validation concern: submission timing is not a presentation guarantee

In `PlatformViewWrapper.draw` / `postPendingFrame`, posting at the front of the main queue moves submission after the current traversal. It does not guarantee that the resulting content reaches the same display deadline. The comment's causal explanation should distinguish an observed trace from behavior guaranteed by the API.

Suggested validation: compare actual platform-view content presentation and input-to-display latency, not just Flutter frame counts. Capture main-thread, RenderThread, raster, and SurfaceFlinger timing for one and two scrolling views at 60/90/120 Hz. Check whether the main-thread stall is removed or merely moved elsewhere.

## Lifecycle and scope suggestions

Keep the existing tests for double draw, resize-before-post, release, and unlock failure. Add device coverage for background/resume with a pending frame, surface replacement, and multiple wrappers posting in one traversal. Verify that no surface remains locked and no stale callback posts after release.

Measure this branch independently before adding the dedicated renderer from PR2. The constructor resize ordering and deferred submission are useful changes whose benefit should remain attributable.
