# Automatic ADPF integration for Flutter on Android

Status: proposed design; no engine implementation is included.

## Decision

Integrate Android Dynamic Performance Framework (ADPF) graphics-pipeline
sessions with automatic CPU and GPU timing. Enable the integration on devices
that expose the complete required contract. On unsupported or excluded devices,
continue rendering without this additional ADPF session.

Flutter supplies the identity of its graphics workload: participating threads,
output surfaces, and intended cadence. Android owns automatic measurement and
performance control. Flutter does not implement a parallel CPU governor, estimate
GPU completion from raster duration, or calculate a per-frame percentage budget.

This is a forward-looking engine improvement. Supporting older Android versions
with a second, manually timed ADPF implementation is not a goal. A higher minimum
version for this optimization is acceptable; the application's minimum supported
Android version does not change.

Known vendor defects are handled through reproducible evidence and narrowly
scoped device/build exclusions. Hypothetical vendor defects are not a reason to
avoid the platform architecture or maintain a competing Flutter policy.

## Motivation

The experimental `isolate-adpf` branch reports build-plus-raster elapsed time as
CPU duration and vsync-to-raster-finish as total duration. It sets a target of 75%
of the nominal refresh interval. `isolate-adpf-phase4` additionally obtains a
Choreographer deadline and changes the target to 75% of the interval between
frame start and that deadline.

These experiments establish useful questions, but leave Flutter responsible for
several difficult interpretations:

- Raster finish is a CPU-side milestone, not asynchronous GPU completion.
- Raster elapsed time can include synchronization and buffer waits.
- A frame's allowed pipeline latency is different from the interval at which
  the pipeline must produce completed frames.
- UI and raster execution can overlap across frames.
- A platform-preferred timeline may provide more latency allowance than the
  earliest timeline, without changing Flutter's intended rendering cadence.
- Engine groups share execution resources and must not independently describe
  overlapping work as unrelated cycles in one manual session.

The observed difference between deadline index zero and the preferred index is
consistent with a change in hint aggressiveness, but is not proof of a correct
general target policy. We should measure presented frames, not infer success
from the reciprocal of build or raster time.

This proposal replaces those manual interpretations with explicit integration
into the platform's graphics-pipeline contract.

## Platform contract and availability

The API family introduces graphics-pipeline configuration, surface association,
and automatic timing. With automatic CPU and GPU timing enabled, the application
does not provide manual duration reports. The session must identify its relevant
surfaces and threads. Capability support and successful creation are required;
an Android version check alone is insufficient. Frame-rate information must stay
consistent with the application's cadence. An explicit target can still help
some workloads, so its optional status must be verified against the supported
platform contract. See the [NDK reference][ndk-reference] and
[AOSP declarations][aosp-header].

The AOSP declarations label this API family as introduced in API 36. Before
implementation, pin the released NDK/platform declarations used by Flutter and
verify exported symbols, signatures, feature-enumeration availability, and
behavior on shipping devices. Rolling AOSP headers and generated documentation
have shown different function signatures; neither is a substitute for the
versioned ABI. Do not hand-copy a function-pointer signature from this document.
If the complete contract requires a later release or extension, raise this
optimization's gate rather than emulating missing functionality.

Required capabilities, when exposed by the pinned API, are:

- Hint sessions.
- Graphics-pipeline sessions.
- Native surface association.
- Automatic CPU timing.
- Automatic GPU timing.

Resolve required entry points through a typed, injectable Android API wrapper.
Keep newer symbols out of unguarded load-time dependencies. Cache immutable
availability results. Treat absent feature-query support as ineligible unless a
versioned platform contract provides an equivalent documented check.

## Goals and non-goals

Goals:

- Improve sustained presentation cadence and responsiveness on supported devices.
- Accurately identify Flutter's work without maintaining vendor-specific timing
  formulas in the engine.
- Cover ordinary rendering, Flutter overlays, engine groups, and thread/surface
  lifecycle changes with one ownership model.
- Make behavior observable and regressions easy to disable and reproduce.
- Preserve current rendering when the optimization cannot operate.

Non-goals:

- Manual ADPF fallback, including partial automatic/manual timing combinations.
- Forcing maximum CPU frequency or permanently forcing maximum display refresh.
- Changing Flutter animation timestamps or presentation scheduling in this change.
- Boosting every thread in the process or every visible platform-view producer.
- Fixing GPU saturation, excessive composition, or synchronization defects through
  increasingly aggressive hints.
- Introducing a remote configuration service or new application telemetry upload.

## Ownership and architecture

Introduce an Android-specific `AndroidGraphicsPerformanceController` shared by
engines that share a `ThreadHost`. These names are proposed interfaces, not
existing APIs. The controller owns session lifecycle and desired workload state.
Each participating engine registers a contribution and receives a registration
token. Destroying one engine removes only its contribution.

The controller operates on the union of active contributions:

```cpp
struct GraphicsWorkloadContribution {
  EngineId engine;
  std::vector<ThreadId> critical_path_threads;
  std::vector<RetainedOutputSurface> outputs;
  bool visible;
};

class AndroidGraphicsPerformanceController {
 public:
  Registration RegisterEngine();
  void UpdateContribution(Registration, GraphicsWorkloadContribution);
  void RemoveContribution(Registration);
  void SetPolicy(AutomaticAdpfPolicy);
};
```

One shared controller initially manages one session for the shared execution
group. Independently hosted engines have separate controllers. This avoids
duplicate sessions over the same shared UI/raster resources. Distinct execution
groups still coordinate any per-app graphics-thread limit through an Android
process-level registry. Count unique TIDs according to the pinned API contract;
do not assume each controller gets the full application allowance.

Do not drop required threads arbitrarily to fit a limit. If the proposed complete
group cannot be represented, disable ADPF for that group and record the reason.
Exercise simultaneously active engines with different surfaces and cadences on
devices before enabling that topology by default. If a platform contract cannot
represent it, use a topology eligibility restriction, not invented aggregation
of per-engine durations.

### Thread membership

Obtain native thread IDs on the actual threads after initialization. Include the
threads executing Flutter's UI and raster critical path; deduplicate when roles
share a thread. Include the platform thread when UI/raster work actually runs
there, not merely because it exists. Do not include IO workers, arbitrary Dart
isolates, or plugin threads by default.

For platform/UI merge-after-launch and raster/platform merging, integrate at the
actual execution-topology transition. Retire the old session before resuming
work under a topology that would make its membership inaccurate, then recreate
from the new complete membership. An initial implementation may conservatively
recreate rather than use `setThreads`; migration is a lifecycle event, not a
per-frame operation. Never leave a session targeting an obsolete UI TID.

### Surface membership

Maintain a registry of live Flutter output producers, using retained native
handles and stable registration generations:

| Output | Treatment |
| --- | --- |
| Main Flutter window/buffer producer | Associate the actual output handle |
| Flutter-rendered overlay | Associate for its active lifetime |
| Flutter SurfaceControl swapchain output | Associate the actual buffer-bearing control |
| Container-only SurfaceControl | Do not assume it represents child producers |
| Plugin-owned SurfaceView producer | Exclude from Flutter's workload by default |
| Offscreen texture/input sampled by Flutter | Do not register as a presentation output |

Do not register the same producer twice through both its window and control
representation. Determine the canonical handle at the backend boundary.
Do not assume association with a parent automatically covers its children.

With interleaved platform views, Flutter owns the buffers it draws, including
its overlays. It does not own independently produced plugin/video buffers just
because they participate in final composition. Supporting plugin contributions
would require a separate producer-ownership contract and is outside this change.

Surface recreation, overlay pooling/reuse, resize that replaces a producer,
view detachment, and backend replacement update the registry. Reuse of an address
does not imply reuse of a registration. Retain handles until queued association
operations finish, and close a session before releasing handles it still uses.
Avoid recording raw pointer values in routine diagnostics.

Texture-backed and offscreen embedding paths require an identifiable supported
output association. Where Flutter cannot obtain one, remain ineligible rather
than binding an unrelated host window and claiming its work.

## Cadence and targets

Preserve the existing Flutter vsync and presentation policy. The ADPF integration
does not select Choreographer timeline zero or the preferred index, move
animation clocks, or change frame pacing.

Use the embedding/display policy's intended cadence when publishing frame-rate
information on associated outputs. This is a desired cadence, not a measurement
of achieved FPS: missed frames must not cause Flutter to advertise a lower target
and turn a transient slowdown into the new objective. Respect host and explicit
application frame-rate policy. Do not hard-code 120/144 Hz or derive display
rate from a render-duration counter.

Cadence plumbing should have one authoritative owner shared with existing
surface/display handling. Update on policy, display, visibility, or producer
changes, not on every frame. Audit ANativeWindow and SurfaceControl paths
separately; they need equivalent information but different submission plumbing.
Do not change plugin-owned surface frame-rate requests.

The selected design uses fully automatic mode without a manually derived target
budget. Where the pinned API permits an omitted/zero manual-work target, use that
documented representation. Do not equate that sentinel with a zero-time deadline.
Verify this behavior with the targeted released implementation before coding the
wrapper. If it requires an explicit meaningful budget, resolve that requirement
as a design amendment; do not silently substitute a refresh period or 75% of it.

There are no `AWorkDuration` reports, per-frame target updates, GPU-tail estimates,
or target hysteresis in this path. Flutter continues collecting its ordinary
frame timings for diagnostics. Those timings do not drive the automatic session.

## Session creation and lifecycle

The following pseudocode describes ordering, not literal NDK signatures:

```cpp
void Reconcile() {
  auto desired = SnapshotActiveContributions();
  auto eligibility = EvaluatePolicyCapabilitiesAndTopology(desired);
  if (!eligibility.allowed || desired.outputs.empty()) {
    CloseSessionIfPresent();
    RecordInactiveReason(eligibility);
    return;
  }
  if (SessionMatches(desired)) {
    return;
  }

  CloseSessionIfPresent();
  auto config = api.CreateConfig();
  api.SetThreads(config, desired.unique_threads);
  api.EnableGraphicsPipeline(config);
  api.SetSurfaces(config, desired.retained_outputs);
  api.EnableAutomaticTiming(config, /*cpu=*/true, /*gpu=*/true);
  // No manually calculated work-duration target.
  auto result = api.CreateSession(config);
  RecordCreationResult(result);
  session = TakeSuccessfulSession(result);
}
```

All configuration/result handling follows the actual versioned API. Release
configuration objects on every path. Failed creation never interrupts rendering.

State transitions:

| State | Meaning and transition |
| --- | --- |
| Ineligible | Disabled by policy, API, capabilities, topology, or denylist |
| WaitingForOutput | Eligible, but no active associated output |
| Active | Session matches current output/thread generation |
| Reconfiguring | Old session retired; latest complete snapshot being applied |
| Failed | Creation/disconnection failure recorded; no session |
| Closed | Controller destroyed; no subsequent operations accepted |

Close when the last active engine/output is removed or the execution group
becomes inactive. A surface surviving in a cache is not sufficient to keep a
hidden rendering workload active. Resume from the current registry rather than
assuming old handles remain valid.

Serialize NDK session calls. Coalesce lifecycle updates and discard stale
generations. Establish a management sequence that does not block raster work on
session creation or vendor IPC. Surface release and thread shutdown must honor
in-flight ownership; document the ordering and avoid synchronous cross-thread
wait cycles. Use existing shutdown-safe mechanisms where available.

Initially prefer recreation on changed thread topology or producer identity.
For frequent overlay changes, implement batched association updates if the pinned
contract supports reliable replacement. Measure churn before choosing a final
strategy; do not put session recreation in the ordinary frame submission loop.

On disconnection or operational failure, close and disable for the current
workload generation. Retry only at a meaningful lifecycle boundary with a bounded
attempt budget. Never retry every frame. API/capability failures remain cached;
an unsupported device does not repeatedly attempt creation on each overlay.

## Engine integration points

Starting from master, add the controller and typed API wrapper rather than
cherry-picking manual reporting into this design.

| Area | Proposed responsibility |
| --- | --- |
| `shell/platform/android/android_shell_holder.*` | Shared controller ownership, engine registration and spawn propagation |
| `shell/platform/android/platform_view_android.*` | Main output attachment, visibility, destruction |
| `shell/platform/android/external_view_embedder/` | Flutter overlay producer lifetime and ownership |
| `impeller/toolkit/android/` and Android swapchain implementations | Canonical retained window/control handles and association events |
| Thread merger and execution-topology integration | Accurate participating TIDs before/after migration |
| Android display/surface policy | Intended cadence propagation |
| Android embedding flags/settings | Developer disable control and rollout policy |

These are integration boundaries; exact hook placement should follow the backend
that owns each resource. Keep Android ADPF concepts out of cross-platform frame
timing records unless another consumer independently needs them.

The existing engine frame-rasterized callback remains untouched. No
`preferred_frame_deadline` field needs to be added to `FrameTiming` for this
integration. Independent timeline tracing can be a separate diagnostic change.

## Device exclusions and rollout

Ship capability-based support, not a permanent whitelist of vendors. Provide a
compiled, reviewable denylist for demonstrated defects. Match the narrowest
reliable combination of manufacturer/device, OS build or bounded version range,
and backend/topology where relevant. Missing metadata must not accidentally match
all devices. Prefer exact normalized identifiers and explicit bounded predicates
over broad regular expressions.

Each entry includes a tracking issue, reproduction, affected versions, evidence
that disabling this integration resolves the regression, and a removal/retest
condition. Broader vendor exclusion is appropriate only when evidence supports
that scope. Unit-test matching and nonmatching neighboring builds.

Distribute exclusions through normal Flutter engine releases. Do not imply an
instant remote kill switch for already-shipped applications. Add an application
manifest disable option and a development command-line override using Flutter's
existing engine-flag conventions; finalize names during implementation. A disable
request always wins. An experimental enable override never bypasses missing
symbols, unsupported capabilities, or invalid resource lifetimes. Testing a
denylisted device requires a separate explicit developer override.

Begin behind an experimental flag while integration is validated. Promote the
supported path to default-on once acceptance criteria pass, retaining opt-out and
denylist handling. Vendor-specific failures are addressed locally rather than
holding all conforming devices back indefinitely.

## Diagnostics and evidence collection

Expose structured trace events and bounded diagnostic logging for:

- OS/API eligibility and each missing capability.
- Selected policy and denylist rule identifier.
- Session creation, close, failure, and retry reason.
- Output/thread counts, topology and registration generation.
- Desired cadence changes and association/reconfiguration cost.

Log state changes rather than one message per frame. Diagnostics must distinguish
"feature enabled by flag" from "an automatic session was successfully created."
Do not claim the platform improved performance merely because creation succeeded.

Collect evidence through device-lab runs, developer opt-in traces, and bug
reports. Include device/build, backend, embedding topology, active capabilities,
engine revision, and flag state. This proposal adds no background data upload.

Compare enabled and disabled runs of identical workloads using actual
presentation intervals, missed presentations, latency where measurable, CPU/GPU
scheduling, energy, and thermal behavior. Control refresh mode, app build mode,
warmup, and starting thermal conditions. Use repeated runs and report variation.
Idle/light workloads matter alongside stress workloads.

## Validation

Deterministic tests with a fake API wrapper cover:

- Old API, missing symbol, missing individual capability, null manager, and
  creation failure all preserve rendering and create no active session.
- Graphics mode, both automatic modes, complete TIDs and correct surfaces reach
  creation; manual report and target-update entry points are never called.
- Shared engines register once per execution group; destroying the parent does
  not invalidate a remaining child; final destruction closes exactly once.
- Thread migration, duplicate TIDs, per-app limits, and incomplete topology.
- Overlay add/remove/reuse, producer replacement, and stale generation delivery.
- Retained handles outlive API operations; shutdown cannot resurrect a session.
- Background/foreground and detach/reattach transitions.
- Failed updates, disconnection, bounded retry, and policy changes.
- Cadence comes from intended policy, not measured achieved FPS.
- Denylist match specificity and disable-option precedence.

Device integration coverage includes:

| Dimension | Cases |
| --- | --- |
| API/support | Below gate; supported OS without capabilities; complete support |
| Rendering | Vulkan and GLES paths eligible for real output association |
| Cadence | 60/90/120 Hz where supported; mode changes and external display |
| Workload | Idle, scrolling, UI-heavy, raster-heavy, GPU-heavy, animation |
| Platform views | No PVs; many SurfaceViews; Flutter overlays; overlay churn |
| Lifecycle | Rotation, resize, background/resume, surface recreation |
| Embedding | Standalone, add-to-app, engine groups, simultaneous engines |
| Threads | Merged UI/platform; separate UI; merge-after-launch; raster migration |

The interleaved 30-widget/30-SurfaceView case with Flutter overlays is a required
regression workload. Verify actual display capability and presentation cadence
independently of any application FPS counter. It must not be the sole benchmark.

Acceptance requires correct lifecycle/capability behavior, no manual timing
policy in the automatic path, and repeatable benefit in at least a relevant
scheduling-limited workload without material regressions in representative light
or sustained cases. Define benchmark-specific numerical thresholds before the
default-on decision. Classify proven vendor defects for targeted exclusion;
do not compensate with device-specific target multipliers.

## Implementation sequence and open verification items

1. Verify the complete released ABI and runtime capability contract on a target
   device. Confirm fully automatic operation without a manual target budget.
2. Implement the injectable wrapper, eligibility policy, and RAII session owner.
3. Integrate execution-group ownership, main surfaces, and cadence behind a flag.
4. Integrate overlays, SurfaceControl producers, migration, and engine groups.
5. Add lifecycle tests and device benchmarks; identify topology restrictions only
   where the actual contract requires them.
6. Enable by default for eligible configurations and maintain evidence-based
   exclusions through normal releases.

Specific items to resolve during steps 1-4 are the released feature-query API,
target omission semantics, multiple-output timing semantics, canonical producer
handles per backend, and per-app thread-limit accounting. These are concrete
contract/integration questions, not a mandate to invent a fallback governor.

## Alternatives

Keep the 75% refresh-period target: small implementation, but keeps a heuristic
that can request more performance than a pipelined workload needs.

Use the preferred timeline deadline: adds useful platform information, but does
not by itself solve workload measurement or relate pipeline latency to cadence.

Always use timeline zero: can make the hint more aggressive; a performance result
on one workload is insufficient to make this the global policy.

Split into manually timed UI and raster sessions: makes some stage boundaries
clearer but adds policy for dependencies, GPU tails, migration, and overlapping
work. It is not necessary to support older devices for this proposal.

Graphics mode with manual or partial automatic timing: retains the measurement
problem for the manually reported component. Require the complete automatic path
instead.

## References

- [Android NDK Performance Hint Manager reference][ndk-reference].
- [AOSP performance hint declarations][aosp-header] (rolling source; pin the ABI
  before implementation).
- [AOSP libandroid exported symbol map][symbol-map].
- [Choreographer timelines][choreographer].
- [Original manual experiment][manual-experiment].
- [Phase-4 experiment][phase4-experiment].

[ndk-reference]: https://developer.android.com/ndk/reference/group/a-performance-hint
[aosp-header]: https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/include/android/performance_hint.h
[symbol-map]: https://android.googlesource.com/platform/frameworks/base/+/master/native/android/libandroid.map.txt
[choreographer]: https://developer.android.com/ndk/reference/group/choreographer
[manual-experiment]: https://github.com/gmackall/flutter/commit/d85aad42c7dad0e0fe390d6dc66bbfe255463585
[phase4-experiment]: https://github.com/gmackall/flutter/commit/da364963d9333effd8431b7c55d5e740ad0b1d45
