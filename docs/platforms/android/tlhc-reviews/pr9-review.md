# TLHC PR9: implementation and approach review

- Branch: `tlhc-pr9-animator-same-vsync-texture`
- Reviewed source commit: `be1f8695358e4ccd8a0c15118c262de79d570e65`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment and changes since the earlier review

Drawing a ready texture without waiting another display interval can reduce latency. The current tip limits extrapolation to two intervals anchored to real vsync timing; synthetic draws no longer refresh that anchor. The feature is enabled by default only on Android, has a setter for experiments, and adds scheduling tests. The earlier indefinite-extrapolation and cross-platform-default concerns have been addressed. The earlier raster timing-reporting and GPU-unavailable changes have also been removed.

## Remaining approach concern: headroom is a heuristic

`ComputeCurrentVsyncWindowWithHeadroom` requires half an inferred interval to remain. That does not measure raster backlog, GPU work, or the cost of this scene. Bounded extrapolation improves safety but can still briefly use stale phase/period around a display change.

Suggestion: validate against actual presentation deadlines; consider fresh display timing and measured workload/backpressure when deciding eligibility. Test 60/90/120 Hz transitions, variable refresh, long idle, resume, expensive scenes, and a busy raster thread. Retain the fallback when timing is invalid or too old.

## Multiple-producer scheduling tradeoff

The guard prevents a second immediate texture redraw in the same window. A later-arriving platform view may consequently wait even when an earlier view has already consumed that opportunity. Test two views whose buffers arrive on either side of the first draw, using per-view content timestamps. Measure latency and fairness, not only engine frame rate.

## Bookkeeping and scope

Keep independent tests for frame request coalescing, a framework frame request arriving while the texture task is queued, no-render startup, trace-flow cleanup, and idle notification behavior. Common bookkeeping changes still need regression coverage even though fast-path activation is Android-only by default. Separate straightforward bookkeeping fixes from the speculative scheduling policy when practical.

No additional confirmed blocker was established in this refresh. This remains a scheduling-policy experiment that needs device evidence rather than an assertion of same-vsync presentation.
