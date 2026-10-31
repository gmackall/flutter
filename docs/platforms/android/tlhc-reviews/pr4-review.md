# TLHC PR4: implementation and approach review

- Branch: `tlhc-pr4-sync-offset-fastpath`
- Reviewed source commit: `c6698100f47df38cd3bb5f89cbd72366e25fc47a`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment

Removing the dependency on a previous offset reply before registering the next frame callback is sensible. Main-looper checks, ordinary-message fallback, pending-message checks, cleanup, and custom-messenger/profiling fallback are valuable safeguards. No definite normal-path blocker was identified.

## Approach concern: several independent changes are bundled

This branch combines reply-independent updates, synchronous Java dispatch, fixed-layout codec handling, reusable encoding storage, and one-way transport. Prefer separate changes or measurements so each part's benefit and compatibility can be assessed.

## Completion and error semantics

`_TextureAndroidViewControllerInternals.setOffset` now completes without a native reply on the default-messenger path. Native failures are therefore not observable through the returned future in the same way as the ordinary method-channel path. This should be an explicit contract decision.

Suggestion: document the completion semantics and test dispose/offset ordering, missing handlers, and native errors. Preserve custom messenger and profiling behavior. Avoid future implementations that retain the shared ByteData after send returns without copying it.

## Ordering and reentrancy validation

The synchronous handler executes inside the Dart-to-platform call. Keep it restricted to operations that cannot synchronously re-enter Dart. Audit changes to `handler.offset` and native view callbacks against that restriction.

Retain FIFO tests and extend them to handler replacement, buffered-message draining, and competing arrivals if this internal facility is reused beyond offsets. Its pending count is associated with HandlerInfo, so do not advertise stronger channel-wide guarantees across registration changes without tests.

Benchmark before/after traversal timing with PR3 independently and combined. Reduced encoding allocations alone should not be credited with the full scheduling improvement.
