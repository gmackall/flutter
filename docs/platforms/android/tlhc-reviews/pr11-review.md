# TLHC PR11: implementation and approach review

- Branch: `tlhc-pr11-layertree-preroll-and-glyph-fastpath`
- Reviewed source commit: `669c43179e4216a1826cb9d933e6b5afbf67745b`
- Review date: 2026-10-30 (America/Los_Angeles)
- Reviewer: Codex, at the branch owner's request.

These notes record source-review findings and proposed follow-up work. The reviewer did not build the engine or run device tests. Findings describe code-derived failure sequences; validation concerns are not claims of reproduced failures. This documentation commit does not fix implementation issues.

## Assessment

Avoiding repeated preroll and glyph discovery can reduce CPU work. The glyph key includes text identity, transform, and glyph properties, with atlas-generation invalidation. Lazy SkFont construction is a sensible smaller optimization. No definite blocker was established in this source review.

## Validation concern: preroll caches mutable layer state

`LayerTree::Preroll` skips the traversal when the tree is marked reusable, raster caching is ignored, no platform views are recorded, and cull/transform inputs match. Preroll updates layer state; tree identity alone should not be treated as proof that no other traversal has modified shared/retained layers.

Suggestion: test retained/shared subtrees, screenshot or offscreen rendering between texture redraws, filters and opacity, partial damage/cull changes, root transforms, and transitions involving platform views. Verify rendered output as well as call counts. Document the invariants that make reuse valid and the operations that invalidate it.

## Glyph cache validation and performance

Test atlas growth/recycling, upload failure/recovery, alpha versus color glyphs, stroke/tone changes, subpixel movement, and repeated TextFrame use. Generation invalidation tests are useful but do not replace rendered-text coverage.

The 512-entry clear-all policy can create abrupt cache misses under larger or moving text workloads. Measure hit rate and CPU time on static and scrolling text, and consider a different bounded eviction policy if it causes periodic spikes. Avoid expanding the key or cache without evidence.

## Scope and expected benefit

Split preroll reuse from glyph verification so they can be evaluated independently. These changes affect common rendering/Impeller workloads beyond TLHC. Chromium renders WebView webpage text itself, so this glyph optimization should not be credited with speeding webpage text rendering; it helps Flutter-rendered text around the platform view. Preroll savings are most relevant when the Flutter scene is reused for texture-only redraws.
