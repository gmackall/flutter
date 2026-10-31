# TLHC PR7: implementation and approach review

- Branch: `tlhc-pr7-imagereader-async-and-lru`
- Reviewed source commit: `061b208c5884bba4107c14c675e7f616230a7a53`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment and updated scope

Background reader work, reduced JNI overhead, import reuse, and preservation of the displayed texture on failed import are worthwhile. The current tip reduces the cache from the earlier 64 entries to 14, scopes the async main handler to the producer, and adds buffer-lifetime protection and more tests. Do not carry the earlier 64-entry criticism forward as a current finding. Fence waiting remains enabled.

## Finding: competing acquisitions can discard a newer image

In FlutterRenderer.ImageReaderSurfaceProducer, the reader callback increments `inFlightAcquire` without excluding a raster fallback acquisition already in progress. Both acquire outside `lock`. `dequeueImage` subsequently clears `acquireTarget.imageQueue` when its own acquisition succeeds, assuming those queued images are older.

Possible interleaving: raster acquires A and pauses before taking the Java lock; callback acquires newer B and queues B; raster takes the lock, discards B, and returns A. The latest/final update can be lost until another producer frame arrives. The count protects shutdown but does not serialize acquisition or establish freshness order.

Suggestion: serialize per-reader acquisitions or use a proven ordering mechanism before discarding queued frames. Add a deterministic two-thread test with barriers after raster acquisition and after callback enqueue; assert that the newer image survives and all images close exactly once.

## Latency and retention tradeoffs

The 2 ms rendezvous wait and fallback cleanup can consume substantial raster time relative to an 8.33 ms frame budget. Measure wait frequency, duration, stale-frame avoidance, and missed presentation deadlines; do not assume moving work to a background thread removes it from the critical path.

The smaller cache is an improvement. Still measure graphics-memory retention after same-size reader replacement and idle views, because expiration advances with acquired frames rather than wall time.

Exercise background cleanup, reader replacement, release while acquire is in flight, and final-buffer delivery. Separate cache/import changes from concurrency changes when attributing performance.
