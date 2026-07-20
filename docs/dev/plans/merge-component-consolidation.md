# Merge component consolidation

## Goal

Collapse the parallel manga/novel merge components onto the shared `Entry*` seam, so the merge behavior a details screen shows (the source chips, the live group refresh, group resolution) is written once and reaches both content types. This removes the last structural fork the merge-system rebuild left behind: two near-identical merge managers, and the per-ScreenModel read/observe wiring that duplicates the group state and its observers.

## Why

The [merge-system rebuild](merge-system-rebuild.md) unified the merge *engine* (one `MergeGroupRepository`, one persisted group both `computeRelated*` read) and the merge *action* side (`EntryMergeActionHost` over the `MergeManager` interface). It deliberately parked the component consolidation as its Phase 5 final step, "a fresh session after a deep code-research pass, not surface-level." This is that pass.

Two forks remain, and both are pure Reikai-owned code (no upstream to fork):

- **Two managers.** `MangaMergeManager` and `NovelMergeManager` are line-for-line twins: same constructor `(MergeGroupRepository, ReikaiLibraryPreferences)`, same logic, differing only by a `ContentType` constant, two method-name families, and one preference read. Every non-type-named method body is identical bar the constant.
- **Two read/observe wirings.** Each details ScreenModel separately holds the group state (`relatedMangaIds` / `relatedNovelIds`), the membership observer, the chip builder, and the selected-source mirror. This is the exact gap that produced the manga live-refresh bug: the novel model observed group changes and the manga model did not, until the observer was added by hand to both. Every merge behavior added to one side still has to be added to the other.

This is the anti-divergence problem the content-layer initiative attacks. The action side is done; this finishes the read side and retires the twin managers.

## Approach

Two separable workstreams, sequenced low-risk-first, each its own commit. Phase A is mechanical and unblocks Phase B (the host takes the unified manager).

### Phase A: collapse the two managers into one `EntryMergeManager`

Replace `MangaMergeManager` and `NovelMergeManager` with one `EntryMergeManager(contentType: ContentType, repository: MergeGroupRepository, preferences: ReikaiLibraryPreferences)`. Every method body becomes the current body with the `ContentType` constant read from the field. The real work is naming and the handful of genuine divergences, not logic.

- **Neutral method names.** The type-named families (`computeRelatedMangaIds` / `computeRelatedNovelIds`, `mergeManga` / `mergeNovels`, `unmergeManga` / `unmergeNovels`) become neutral (`computeRelatedIds`, `mergeEntries`, `unmerge`). These are load-bearing across roughly two dozen caller files (list below), so this is a rename sweep. Prefer neutral names over keeping per-type aliases on the unified class: aliases would leave the very redundancy this removes.
- **The one real preference split.** `suggestGroupingOnAdd` reads `autoMergeSameTitle` for manga but `novelAutoMergeSameTitle` for novels (two distinct keys in `ReikaiLibraryPreferences`; the master switch `seriesMergingEnabled` is shared). The unified getter selects the pref by `contentType`.
- **`seriesGroupKeys` signature.** Today it takes `List<Manga>` vs `List<Novel>` but touches only `.id`, and uses a per-type standalone-key prefix (`"m"` vs `"n"`, group prefix `"g"` shared). Change it to take `List<Long>` (ids), deriving the prefix from `contentType`. The two callers are both in `NovelUpdatesScreenModel` (which injects both managers today); they pass `favorites.map { it.id }`.
- **`relatedNovelIdsFor`** is a novel-only `List<Long>` variant of `computeRelatedIds` (the reader / tracking path wants a `List`, not a `LongArray`). Keep it on the unified class as `relatedIdsList` (or have those callers use `computeRelatedIds(...).toList()`); manga simply never calls it.
- **DI.** `DomainModule` registers two singletons (`MangaMergeManager`, `NovelMergeManager`). This becomes two instances of the one class, one per content type, distinguished so injectors resolve the right one. Options: two typealias-qualified factories, an Injekt qualifier per type, or thin `MangaMergeManager` / `NovelMergeManager` subclasses that fix `contentType` (subclasses keep the ~24 injection sites resolving by type with zero DI-lookup churn, at the cost of two tiny classes). Decide in the plan; the subclass route is the smallest blast radius.
- **Tests.** `MangaMergeManagerTest` and `NovelMergeManagerTest` construct the concrete classes directly and call the named methods, so both rewrite to the unified class + neutral names (keep the same behavioral assertions; they are the regression net). `MergedChapterProviderTest` mocks `MangaMergeManager`, so its mock type changes. Everything else (repository, collapse, reconstruction, aggregation, backup round-trip tests) is insulated (they target the repository or pure helpers).

Blast radius (caller files that bind a per-type-named manager method and need the rename): `MangaScreenModel`, `NovelDetailsScreenModel`, `HistoryScreenModel`, `NovelHistoryScreenModel`, `LibraryScreenModel`, `NovelLibraryScreenModel`, `MigrateMangaUseCase`, `MigrateNovelUseCase`, `PropagateTrackerLinks`, `PropagateNovelTrackerLinks`, `GetNovelTracks`, `DeleteNovelTrack`, `MangaMigrationSourcePickScreen`, `NovelMigrationSourcePickScreen`, `MangaLibraryAdder`, `NovelLibraryAdder`, `MergedChapterProvider`, `NovelReaderScreenModel`, `NovelUpdatesScreenModel`, `SettingsAdvancedScreen`. `LegacyYokaiDbImporter` does **not** use the managers (it resets the legacy prefs directly), so it is untouched.

### Phase B: extract the read/observe wiring into a shared `EntryMergeGroupHost`

Mirror `EntryMergeActionHost` (the proven write-side host) for the read side. A plain class both ScreenModels compose, owning the group state and its observers, exposing flows each model folds into its own state. This is where "an observer like today's gets written once" lands.

The host owns:
- The `relatedIds: MutableStateFlow<LongArray>` (today `relatedMangaIds` / `relatedNovelIds`).
- The **membership observer** (recompute `relatedIds` from `mergeManager.membershipChanges()` plus, for novels, the anchor-resolution flow; the manga case is a bare `membershipChanges()` collector because its anchor is a constant, the novel case is `combine(anchorFlow, membershipChanges())`).
- The **chip builder** producing the neutral `ManageMergeSourceRow` list (id, sourceName, subtitle?) both `MergeSourceChips` and `ManageMergeSourcesDialog` already consume.
- The `selectedSource: MutableStateFlow<Long?>`.

The per-type seams the host takes as constructor params (exactly how `EntryMergeActionHost` already injects its two differences):
- **Anchor** as `anchorId: () -> Long` (manga: `{ mangaId }`, constant) plus an optional anchor-resolution `Flow<T?>` for novels (`novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId)`, which also sets `anchorNovelId`). Model this so manga passes a constant and novel passes the flow.
- **The manager** (the unified `EntryMergeManager` after Phase A).
- **The source resolver**, the genuinely divergent piece: manga resolves synchronously via `SourceManager.getOrStub` (never fails, stubs missing sources); novel resolves asynchronously via `installer.ensureLoaded()` then a nullable `NovelSourceManager.get`, and additionally must produce the `siblingSources: Map<Long, NovelSource>` map its reader-routing and ranking consume. Give the host a per-type `resolveSources(ids) -> {chips, siblingSources?}` strategy so the async plugin-load and the sibling map stay novel-side without forking the host.

Composition: `MangaScreenModel` builds the host inside a `// RK` island (its merge read state and observers are already fenced `// RK -->` / `// RK <--`, so the model is composed, never dissolved, keeping upstream syncs clean); `NovelDetailsScreenModel` builds it directly. Each model exposes the host's flows through its own state (manga via `updateSuccessState { it.copy(mergeSources = ..., selectedSourceMangaId = ...) }`; novel via its `mergeChips` / `siblingSources` flows read in `rebuildLoaded` and the chapter combine). The chapter combine itself stays per-type (manga's `ChapterInputs` vs the novel combine differ deeply); it just reads the host's `relatedIds` flow as it reads the local one today.

## Key files

- Managers to collapse: `reikai/domain/manga/MangaMergeManager`, `reikai/domain/novel/NovelMergeManager`, over the shared `reikai/domain/merge/MergeManager` interface and `reikai/data/merge/MergeGroupRepositoryImpl` (the repository is already content-type-parameterized at the method level and is untouched).
- Read wiring to extract: the `// RK` merge islands in `eu/kanade/tachiyomi/ui/manga/MangaScreenModel` (the `relatedMangaIds` state, the membership observer, `buildMergeSources`, the selected-source collector) and `reikai/presentation/novel/details/NovelDetailsScreenModel` (`relatedNovelIds`, `observeMergeGroup`, `observeMergeSourceChips`, `siblingSources`, `mergeChips`, `anchorNovelId`).
- Pattern to mirror: `reikai/presentation/details/EntryMergeActionHost` (the write-side host) and its composition islands in both ScreenModels.
- Neutral types already in place: `ManageMergeSourceRow` (in `ManageMergeSourcesDialog`), `EntryMergeSource` (in `EntryDetailsScreenState`), the shared `MergeSourceChips`.
- DI: `eu/kanade/domain/DomainModule` (the two manager factories).
- Tests: `MangaMergeManagerTest`, `NovelMergeManagerTest` (rewrite), `MergedChapterProviderTest` (mock rename).

## Status

**Phase A shipped** (`be37f01f0`): the two managers are now thin subclasses of one `EntryMergeManager(contentType, ...)`, with neutral method names swept across the ~20 callers; the manager tests were rewritten as the regression net and pass. No behaviour change (the managers are pure adapters over the merge group tables).

**Phase B shipped** (`99c2479da`): `EntryMergeGroupHost` now owns the shared read side (the group `relatedIds`, the selected-source chip, the membership observer, and the switcher-chip flow); both details models compose it (manga inside its `// RK` island, novel directly), mirroring `EntryMergeActionHost`. The two genuinely per-type pieces are injected seams: an `anchorChanges: Flow<Long>` (manga a constant re-emitted on membership change, novel the url+source lookup that also updates `anchorNovelId`) and a `resolveSources` closure (the novel one does the async plugin-load and populates `siblingSources`, which stays novel-side). The chapter combine and metadata load stay per-type and read the host's flows. Also retired the per-type chip DTOs `MangaScreenModel.MergeSourceInfo` + `NovelMergeSourceInfo` onto the neutral `EntryMergeSource` (chips) / `EntryManageSourceInfo` (dialog), dropping a dead `isCurrent` field. Covered by `EntryMergeGroupHostTest` (seed + reactive observe) and verified on-device: merged manga details, merged novel details, and the novel reader source routing.

## Decisions & tradeoffs

- **Two workstreams, Phase A first.** The manager collapse is mechanical and de-risks Phase B (the host consumes the unified manager). Shipping them separately keeps each diff reviewable.
- **The collapse helpers stay forked (do not consolidate).** `MangaMergeCollapse` and `NovelMergeCollapse` share only a ~12-line bucket-and-pick core, but their outputs are different types (in-place badge stamping on `List<LibraryItem>` vs a `CollapsedNovel` wrapper), take different arguments, treat singletons differently, and feed structurally different downstreams (the novel side unions tracks across group members; manga has no analogue). A merged generic would need a type parameter plus a stamp-vs-wrap strategy, more surface than the duplication removed. This confirms the merge-rebuild doc's prior call.
- **Compose the manga model, never dissolve it.** `MangaScreenModel` is Mihon's, upstream-synced; the read host is composed inside `// RK` islands exactly like `EntryMergeActionHost`, so an upstream rename breaks the composition at compile time rather than drifting. The novel model, having no upstream, absorbs the host directly. Same shared host, two composition styles, dictated by which side has an upstream.
- **The novel source-resolution nuance is a seam, not a fork.** The async plugin load (`installer.ensureLoaded`) and the `siblingSources` map are novel-only, injected into the host as a per-type resolver, so they stay novel-side without duplicating the host.
- **Neutral method names over per-type aliases.** Renaming the ~two dozen call sites is the point of the collapse; keeping aliases would preserve the redundancy.

## Open questions (resolve before executing)

1. **DI shape for the unified manager**: two `EntryMergeManager` instances via Injekt qualifiers, or two thin `Manga`/`Novel` subclasses that fix `contentType` (smallest blast radius, keeps ~24 injection sites resolving by type). Recommend the subclass route unless a qualifier reads cleaner.
2. **Host scope**: should `EntryMergeGroupHost` also own the selected-source mirror and the chip flow, or only `relatedIds` + the membership observer? Recommend it owns all four read concerns (relatedIds, membership observer, chips, selectedSource); the chapter combine stays per-type and reads the host's `relatedIds`.
3. **`siblingSources`/plugin-load exposure**: keep the novel sibling-source map entirely novel-side (host returns only chips for manga, chips + sibling map for novel via the resolver), or hoist a neutral resolved-source map into the host for both. Recommend keeping it novel-side behind the resolver seam (manga has no equivalent need).
4. **`seriesGroupKeys` callers**: confirm both live in `NovelUpdatesScreenModel` and that no separate manga updates model exists (the scout found the manga favorites path appears to run through that same file, worth a glance).
