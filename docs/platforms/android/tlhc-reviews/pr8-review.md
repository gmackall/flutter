# TLHC PR8: implementation and approach review

- Branch: `tlhc-pr8-ndk-surface-producer-gpu-fence`
- Reviewed source commit: `e4431f0213c4fdcc4d1c624b2635369dac5e7b16`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment and changes since the earlier review

The current tip differs materially from the first review: Vulkan external semaphore import and the 64-semaphore retirement FIFO have been removed; Vulkan uses bounded CPU fence waits. Native reader affinity has also been removed. The producer now acquires a caller-owned AHardwareBuffer reference under its mutex, and native lifecycle/fence tests have been added. The previous statements that this branch lacks native tests or still contains the semaphore workaround are obsolete.

## Implementation concern: fence wait success must be checked

`NdkSurfaceProducer::WaitAcquireFenceFdBounded` returns true for any positive poll result without checking `revents`. poll can return positively for error/invalid-descriptor conditions as well as readiness. The EGL path similarly ignores the return value of `eglWaitSyncKHR` and reports success after creating a sync object.

Suggestion: distinguish successful readiness from POLLERR/POLLNVAL and define handling for other unexpected events; check EGL wait success and avoid sampling a buffer whose dependency was not established. Add injected error tests. If falling back after EGL import, account for transferred FD ownership rather than reusing a consumed descriptor.

## Bounded wait and retry policy

The nominal 32 ms CPU wait is nearly four 120 Hz intervals. Repeating poll with the full timeout after EINTR also does not enforce a total 32 ms deadline. Use an absolute deadline if the documented bound is intended to cover the entire operation.

On timeout the current image is skipped. Test that a final update eventually appears after its fence signals even when the producer sends no further images; establish whether a retry is needed. Measure the CPU-wait cost separately from the removed Vulkan GPU-wait implementation.

## Ownership and GPU completion validation

A retained AHardwareBuffer reference protects allocation lifetime, but does not by itself prevent producer overwrite. Dequeued image retirement is still count-based. Establish that each retirement is safe for actual GPU consumers, including multiple paints of the same texture in a frame, multiple Flutter views, resize, failed submissions, and background cleanup. Reader retirement should be reviewed together with its outstanding images. These are validation concerns, not a reproduced use-after-free or double-free claim.

Keep the new native tests and add real-device Vulkan/GLES stress and FD/resource accounting. The Java fallback inherits the PR7 competing-acquisition concern. Prefer explicit completion/ownership invariants over timing/count heuristics wherever the assumed frame relationship cannot be proven.
