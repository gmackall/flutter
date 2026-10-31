# TLHC PR6: implementation and approach review

- Branch: `tlhc-pr6-direct-touch-after-arena-win`
- Reviewed source commit: `841b1b1ed65117c938d21508228acc79ff9b6cb9`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment

Avoiding the return trip after arena ownership is settled is a compelling latency optimization. The implementation now notifies Dart when native dispatch deactivates and forwards events to Flutter before a child can mutate them. Two correctness findings remain.

## Finding: one matching coordinate does not prove transform eligibility

`PlatformViewDirectTouchDispatcher.onPlatformViewWonGesture` accepts a mapping when one echoed point matches within two pixels. A scale or rotation can match at or near its origin while later points diverge. Dart then suppresses single-pointer move echoes, so `verifyDirectlyDispatchedEcho` cannot detect a transform introduced after suppression begins.

Suggestion: use explicit transform eligibility plus invalidation when the transform changes, or provide the correct mapping to native dispatch. Matching sampled coordinates is not a proof of translation-only mapping. Test a scaled view touched at its origin and an initially untranslated view scaled or rotated during an active gesture. Assert the actual child coordinates and continued delivery, not just the active flag.

## Finding: a new native down discards old echo-suppression state

`prepareTouchEvent` resets `directDownTime` and `lastDirectEventTime` on ACTION_DOWN. If gesture A's up was delivered directly, gesture B's native down arrives, and A's framework up echo arrives afterward, `isDirectlyDispatched` no longer recognizes A. The controller delivers that stale terminal event again. Delayed move echoes have the same underlying problem.

Suggestion: retain suppression state for completed gestures until outstanding echoes are accounted for, preferably with explicit event identities. Add controller-level interleaving tests spanning gestures, including delayed up/cancel and queued moves.

## Scope and validation

This changes hybrid/HCPP paths through FlutterMutatorView and PlatformViewsController2, not only TLHC. Test both controllers, multi-touch handoff, filtered pointers, child event mutation, disposal, and recognizer changes. Split shared AndroidTouchProcessor allocation/cache optimizations from the handoff protocol where practical. Measure latency without assuming increased FPS.
