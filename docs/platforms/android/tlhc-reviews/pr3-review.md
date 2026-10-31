# TLHC PR3: implementation and approach review

- Branch: `tlhc-pr3-vsync-choreographer-sync`
- Reviewed source commit: `efd5218e26c9470b281136842393bfdbed815abd`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment

Running Flutter frame work in Android's Choreographer animation phase, before traversal, is a persuasive scheduling optimization. Explicit inline-dispatch opt-in, the UI-thread check, the current-isolate guard, and Java callback rearming are useful safeguards. No definite blocker was identified.

## Approach concern: shared microtask ordering changes

`Engine::BeginFrame` now flushes microtasks immediately after `RuntimeController::BeginFrame`. This executes on all platforms and on the existing posted callback path, not only the new inline Android path. The flush is useful for completing frame-triggered work before returning to Android, but its scope and ordering contract need an explicit explanation.

Suggestion: document why the new flush belongs in shared Engine code and test the posted path as well as the inline path. Cover microtasks created in draw/post-frame callbacks, callbacks that request another frame, and platform messages issued by those microtasks. Verify ordering against Animator end-of-frame bookkeeping and task-observer flushing.

## Integration validation

The unit tests cover dispatch, rearming, and microtask completion. They do not prove that the real Java animation/traversal phases deliver the intended result. Add an Android integration test or trace showing Flutter offset updates reaching the native hierarchy before traversal.

Exercise merged and unmerged threads, multiple engines, continuous Flutter animation, startup, background/resume, and refresh-rate changes. Preserve the fallback when an isolate is already entered.

Measure with and without PR4: synchronous offsets and pre-traversal Flutter execution complement each other, so their gains must not be added independently.
