# Content layer: library surface

## Goal

Collapse the manga and novel libraries onto one Reikai-owned content layer, so the library behaves identically for both content types and a library change is written once. This is the library surface of the [content-layer program](content-layer-architecture.md) (after the details surface), built to the same shape: a neutral state, a behavior seam, adapters over the live engines, an `EntryId` identity, and typed capability slots.

## Why

Unlike details, the library UI is already largely shared. Novels render through the same `LibraryTab` and the same grid, hopper, category picker, and settings by disguising each novel as a negative-id `LibraryItem`, and the grouping / sort / filter kernels (`LibraryDynamicGrouping`, `reikaiSortCategories`, `EntryDisplayPage`, the hopper/picker/fast-scroll grid) are already content-neutral. Two forks remain, and both are exactly the anti-divergence hazard the program exists to remove:

- **The ScreenModel pipeline.** `NovelLibraryScreenModel` reimplements the manga library's `buildState` pipeline (filter, merge-collapse, tracker union, tracker-status filter, sort, dynamic grouping, custom-info overlay), retyped onto `Novel`. Its own code notes it mirrors the manga library's tracker filter. Every library behavior change is two edits, and the two sides drift.
- **The identity disguise.** Novels ride as negative-id fake `LibraryItem`s so they can reuse the manga-typed shared UI and pipeline. The neutral `EntryId` (already used by the details and cover-cache layers) is unused in the library, so the library still leans on the sign-of-the-id trick the architecture doc wants retired.

## Approach

Three phases, lowest-risk first, each independently shippable and on-device verified (debug and minified), matching the program's per-surface discipline.

**Phase 1: the type-agnostic shared transforms.** A deep side-by-side of the two `buildState` pipelines showed the transform stages are logically congruent, but the filter, merge-collapse, and item-build stages are coupled to the entry type (manga transforms `LibraryItem`; the novel model transforms `LibraryNovel`, collapses into `CollapsedNovel`, then builds `LibraryItem`, and the two collapse at different pipeline positions, manga before filter, novel after). So the type-coupled bulk moves to Phase 2, where the neutral `EntryId`-keyed row lands and both sides share one type. Phase 1 extracts only the genuinely type-agnostic pieces, each safe and independent: the **lewd filter level-up** (a genre-based novel `isLewd`, see the decision below), the **shared sort comparator** (the 11 keys are identical across `LibrarySort` and `NovelLibrarySort`; unify to one comparator builder or a decode-to-shared seam), and the **shared tracker union + mean + tri-state filter** helper (both models compute the same merge-group tracker union and include/exclude tri-state). Two open questions to resolve first: whether manga's `getTracksPerManga` is already merge-group-aware, and whether to unify the two sort enums or keep a thin decode seam.

**Phase 2: neutral state, adapters, `EntryId`.** Introduce a neutral `LibraryScreenState` (rows keyed by `EntryId`, capability slots) and a `LibraryBehavior` seam, with a `MangaLibraryAdapter` over the live `LibraryScreenModel` (fenced in `// RK` islands, so a renamed upstream field breaks the adapter at compile time) and a `NovelLibraryAdapter` over the novel engine. `LibraryTab` consumes the neutral state and behavior instead of today's `active*` fall-through (an informal adapter already). Identity moves from the signed `Long` / id-sign branches to `EntryId`, retiring the disguise. This is code-only: no schema migration and no backup change, because novel rows already carry real positive ids and `EntryId.coverCacheKey()` already centralizes the one cover-cache negation. A one-time reset of any runtime cache or UI state keyed by the old disguised id (scroll, selection) is expected and is never a backup concern.

**Phase 3: parity and reconciliations.** Unify the collapsed-set representation (the novel side's single `Set<String>` vs the manga side's split real / dynamic sets), reconcile `NovelLibrarySort` against `LibrarySort`, and finalize the lewd gating and label.

**What stays forked, deliberately (the seams).** The manga and novel engines never merge (different domain models, `String` vs `Long` sources, plugin host vs Mihon sources). The merge-collapse helpers stay forked (different output types, a prior locked decision in [merge-component-consolidation.md](merge-component-consolidation.md)). Download-count derivation stays per-type until the separate download-subsystem unification (Road B). These are injected seams, exactly like the details adapters' `resolveSources` / `setFavorite` lambdas.

## Key files

- Manga library, stays live (engine): `eu/kanade/tachiyomi/ui/library/` (`LibraryScreenModel`, `LibraryTab`, `LibraryItem`, `LibrarySettingsScreenModel`).
- Novel library, its pipeline dissolves: `reikai/presentation/library/novels/` (`NovelLibraryScreenModel`, `NovelLibraryItem`, `NovelLibrarySettingsDialog`, `NovelMergeCollapse`).
- Already-neutral shared plumbing to build on: `reikai/presentation/library/` (`ReikaiLibraryContent`, `ReikaiLibraryState`, `ReikaiLibraryBadges`, `LibraryDynamicGrouping`, `ReikaiCategorySort`, the hopper / picker / fast-scroll grid), plus `EntryDisplayPage`.
- Neutral identity: `reikai/domain/entry/EntryId` (and `coverCacheKey`).
- The template to mirror: `reikai/presentation/details/` (the two adapters, `EntryDetailsScreenState`, `EntryDetailsBehavior`, the `// RK` host composition).
- Lewd heuristic to generalize: `reikai/util/MangaLewd`.

## Status

Planned; Phase 1 in progress. Grounded by a deep code-research pass across both library ScreenModels, the shared library plumbing, the details template, the manga/novel feature gaps, and the novel plugin ecosystem (for the lewd-filter viability check).

## Decisions & tradeoffs

- **Match the details architecture** (neutral state + behavior seam + adapters + `EntryId`), not a lighter pipeline-only helper, so the whole content layer is one cohesive shape rather than two. Owner-chosen.
- **Extract shared behavior before dissolving the novel model** (Phase 1 before Phase 2), per the program's rule, so each step is incremental and never gambles the whole surface on one big adapter.
- **Retire the negative-id disguise via `EntryId`** (Phase 2). It works today and is code-only, but leaving it forks identity from the rest of the content layer; unifying it is the point of the full collapse.
- **Level novels up on the lewd filter (genre-based), not gate it.** Novel sources carry no nsfw flag (neither the plugin metadata nor the novel model has one, and LNReader has no adult concept), but adult genre tags (`mature`, `adult`, `smut`, `erotica`) are common across the novel plugin ecosystem, so the existing genre heuristic in `MangaLewd` classifies them. Only the manga source-name branch is inapplicable to novels. In-app copy should note that novel lewd-detection is genre-tag-based, so it is weaker than a hard source flag.
- **`LibraryScreenModel` and `LibraryTab` stay live and `// RK`-patched** (engine / partially-collapsed files), never dissolved or added to the off-path manifest while a live remainder exists, per the [upstream-sync](../upstream-sync.md) policy.
- **The merge-collapse helpers and the download-count derivation stay per-type seams**, not shared code.
