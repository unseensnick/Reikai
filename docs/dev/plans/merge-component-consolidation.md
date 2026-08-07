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
- **`seriesGroupKeys` signature.** Today it takes `List<Manga>` vs `List<Novel>` but touches only `.id`, and uses a per-type standalone-key prefix (`"m"` vs `"n"`, group prefix `"g"` shared). Change it to take `List<Long>` (ids), deriving the prefix from `contentType`. The two callers are both in `NovelUpdatesViewModel` (which injects both managers today); they pass `favorites.map { it.id }`.
- **`relatedNovelIdsFor`** is a novel-only `List<Long>` variant of `computeRelatedIds` (the reader / tracking path wants a `List`, not a `LongArray`). Keep it on the unified class as `relatedIdsList` (or have those callers use `computeRelatedIds(...).toList()`); manga simply never calls it.
- **DI.** `DomainModule` registers two singletons (`MangaMergeManager`, `NovelMergeManager`). This becomes two instances of the one class, one per content type, distinguished so injectors resolve the right one. Options: two typealias-qualified factories, an Injekt qualifier per type, or thin `MangaMergeManager` / `NovelMergeManager` subclasses that fix `contentType` (subclasses keep the ~24 injection sites resolving by type with zero DI-lookup churn, at the cost of two tiny classes). Decide in the plan; the subclass route is the smallest blast radius.
- **Tests.** `MangaMergeManagerTest` and `NovelMergeManagerTest` construct the concrete classes directly and call the named methods, so both rewrite to the unified class + neutral names (keep the same behavioral assertions; they are the regression net). `MergedChapterProviderTest` mocks `MangaMergeManager`, so its mock type changes. Everything else (repository, collapse, reconstruction, aggregation, backup round-trip tests) is insulated (they target the repository or pure helpers).

Blast radius (caller files that bind a per-type-named manager method and need the rename): `MangaViewModel`, `NovelDetailsViewModel`, `HistoryViewModel`, `NovelHistoryViewModel`, `LibraryViewModel`, `NovelLibraryViewModel`, `MigrateMangaUseCase`, `MigrateNovelUseCase`, `PropagateTrackerLinks`, `PropagateNovelTrackerLinks`, `GetNovelTracks`, `DeleteNovelTrack`, `MangaMigrationSourcePickScreen`, `NovelMigrationSourcePickScreen`, `MangaLibraryAdder`, `NovelLibraryAdder`, `MergedChapterProvider`, `NovelReaderScreenModel`, `NovelUpdatesViewModel`, `SettingsAdvancedScreen`. `LegacyYokaiDbImporter` does **not** use the managers (it resets the legacy prefs directly), so it is untouched.

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

Composition: `MangaViewModel` builds the host inside a `// RK` island (its merge read state and observers are already fenced `// RK -->` / `// RK <--`, so the model is composed, never dissolved, keeping upstream syncs clean); `NovelDetailsViewModel` builds it directly. Each model exposes the host's flows through its own state (manga via `updateSuccessState { it.copy(mergeSources = ..., selectedSourceMangaId = ...) }`; novel via its `mergeChips` / `siblingSources` flows read in `rebuildLoaded` and the chapter combine). The chapter combine itself stays per-type (manga's `ChapterInputs` vs the novel combine differ deeply); it just reads the host's `relatedIds` flow as it reads the local one today.

## Key files

- Managers to collapse: `reikai/domain/manga/MangaMergeManager`, `reikai/domain/novel/NovelMergeManager`, over the shared `reikai/domain/merge/MergeManager` interface and `reikai/data/merge/MergeGroupRepositoryImpl` (the repository is already content-type-parameterized at the method level and is untouched).
- Read wiring to extract: the `// RK` merge islands in `eu/kanade/tachiyomi/ui/manga/MangaViewModel` (the `relatedMangaIds` state, the membership observer, `buildMergeSources`, the selected-source collector) and `reikai/presentation/novel/details/NovelDetailsViewModel` (`relatedNovelIds`, `observeMergeGroup`, `observeMergeSourceChips`, `siblingSources`, `mergeChips`, `anchorNovelId`).
- Pattern to mirror: `reikai/presentation/details/EntryMergeActionHost` (the write-side host) and its composition islands in both ScreenModels.
- Neutral types already in place: `ManageMergeSourceRow` (in `ManageMergeSourcesDialog`), `EntryMergeSource` (in `EntryDetailsScreenState`), the shared `MergeSourceChips`.
- DI: `eu/kanade/domain/DomainModule` (the two manager factories).
- Tests: `MangaMergeManagerTest`, `NovelMergeManagerTest` (rewrite), `MergedChapterProviderTest` (mock rename).

## Status

**Phase A shipped** (`be37f01f0`): the two managers are now thin subclasses of one `EntryMergeManager(contentType, ...)`, with neutral method names swept across the ~20 callers; the manager tests were rewritten as the regression net and pass. No behaviour change (the managers are pure adapters over the merge group tables).

**Phase B shipped** (`99c2479da`): `EntryMergeGroupHost` now owns the shared read side (the group `relatedIds`, the selected-source chip, the membership observer, and the switcher-chip flow); both details models compose it (manga inside its `// RK` island, novel directly), mirroring `EntryMergeActionHost`. The two genuinely per-type pieces are injected seams: an `anchorChanges: Flow<Long>` (manga a constant re-emitted on membership change, novel the url+source lookup that also updates `anchorNovelId`) and a `resolveSources` closure (the novel one does the async plugin-load and populates `siblingSources`, which stays novel-side). The chapter combine and metadata load stay per-type and read the host's flows. Also retired the per-type chip DTOs `MangaViewModel.MergeSourceInfo` + `NovelMergeSourceInfo` onto the neutral `EntryMergeSource` (chips) / `EntryManageSourceInfo` (dialog), dropping a dead `isCurrent` field. Covered by `EntryMergeGroupHostTest` (seed + reactive observe) and verified on-device: merged manga details, merged novel details, and the novel reader source routing.

**Group ordering fixed in the repository (`412b95ba6`, 2026-08-04).** `merge()` rebuilt a group from scratch at the default priority, so it discarded both the hand-set manage-sources order and the group's `override_source_ranking`; reachable from a split Undo, adding a source to a group, and backup restore. It now takes its member order from the argument list, expanding an absorbed group in place, and ORs the override across the groups it absorbs. A **named** member keeps its argument position rather than being pulled forward by its group, which is what makes an undo an exact restoration: the scouted rule (expand each id's group at that id's position) fails when the split member sat in the middle, returning it to the end. `replaceInGroup` seats the arriving member in the outgoing one's slot, since a fresh row otherwise sorts last on the default priority, and member order is now written explicitly so `addMembers` appends rather than landing mid-order. No schema change: the insert queries already took a priority and the member reads already ordered by it. Eight new tests, each verified by deleting the clause it pins (one was vacuous until the case was changed to replace the LAST member, where the buggy and fixed behaviour actually differ). Device-verified: after a middle-source split and Undo, the group came back as `10, 505, 3` with the override still on, and the group id changed, proving the re-merge really ran.

**One group-forming primitive, and one unit of work with the favorite swap (2026-08-04).** A whole-system
audit of the migrate and merge systems found the ordering fix above had left two writers deciding the same
group-owned facts. `merge()` still rebuilt the group into a fresh row, so it had to re-carry the override
flag by hand and dropped `title_override` / `cover_override` by default, while `replaceInGroup` kept its
row and carried them for free. What landed:

- **`merge()` now reuses a surviving group row instead of rebuilding one.** The survivor is the group of
  the first id that has one, so it keeps its id, its members' order and every group-owned column by
  construction rather than by remembering to copy each. Arrivals are appended: argument order decides the
  order only for a group that never had one, which stops "add to existing group" (every call site names
  the newcomer first) from putting a never-ranked source on the trunk of a hand-ordered group.
- **The override flag comes from the surviving group, not an OR across absorbed ones.** The OR turned one
  flagged group into a fabricated ranking for everything it absorbed. `replaceInGroup` already followed
  the survivor rule, so the two now agree.
- **`materializeGroup(orderedIds, override)` is the new op for a caller that knows the whole answer**: it
  replaces whatever grouping those members had with exactly that group, order and flag. Split-Undo uses
  it, via a `GroupSnapshot` the action host captures BEFORE the split, since splitting a pair deletes the
  group row and takes the flag with it. This is the "one repository operation rather than merge plus a
  correction" the backup note below asks for, and backup restore should adopt it.
- **`replaceInGroup` takes an `onDissolve` hook and runs it inside its transaction** for the one branch
  that really breaks a group up (migrating onto a sibling, which leaves the target alone). Absorbing the
  arriving member's group is not a break-up and does not fire it. The manager's old claim that a replace
  never dissolves was false for two of four branches.
- **Both migration engines now put the group rewrite and the favorite swap in one transaction, swap last**
  (`Transactions`, a one-method seam so a use case can say "these commit together" and still be
  constructible in a plain JVM test). They were two transactions with a suspension point between them, so
  a cancelled batch could commit the swap and never reach the rewrite, leaving the source out of the
  library but still in the group, feeding chapters into it while invisible there and unreachable to
  unmerge.

Eight new repository tests plus two per engine, each verified by deleting the production clause it pins.
The audit that produced this is `docs/dev/audits/2026-08-04-migrate-and-merge-systems.md` (local).

**Restore resolved in one pass (2026-08-04).** `restoreMerges` called `merge()` once per backup group, so
each write landed on what the previous one left behind: two series grouped separately in the backup came
back as one whenever the device already had a group bridging them (backup `{A,B}` and `{C,D}` onto a
library where A was merged with C gave one four-member group, silently). Both restorers now hand their
resolved ids to one shared `RestoreMergeGroups`, which reads local membership ONCE and materialises each
group with `materializeGroup`, so the result no longer depends on the order the backup lists its groups.

The precedence rule is now stated rather than implied: **the backup is authoritative for the entries it
names.** An entry it groups leaves whatever local group it was in; local members the backup says nothing
about keep their own group, order and ranking when at least two remain, and go standalone otherwise. The
old "additive, absorbs any overlapping local group" wording could not survive contact with two backup
groups touching one local group, which is what produced the welding. Six tests, mutation-verified.

Two more from the same audit: the manga backup creator dropped a merge ref whose row has gone instead of
throwing (the novel creator already did, so one stale membership aborted the whole backup), and the
post-loop restore phases (merges, custom info) are wrapped per phase, since a throw there cancelled the
sibling content type's stream and escaped before the error log was written.

Backup still carries neither the order nor the flag, and that is a separate, parked gap (ROADMAP, Later -> Data & backup): the creator reads the unordered membership map instead of the ordered member query, and the group models have no flag field. The structural note recorded with it: restore should gain one repository operation that materialises a group with its order and flag, rather than calling `merge()` and then correcting its result, which would leave two writers deciding the same two facts.

## Decisions & tradeoffs

- **Two workstreams, Phase A first.** The manager collapse is mechanical and de-risks Phase B (the host consumes the unified manager). Shipping them separately keeps each diff reviewable.
- **The collapse helpers stay forked (do not consolidate).** `MangaMergeCollapse` and `NovelMergeCollapse` share only a ~12-line bucket-and-pick core, but their outputs are different types (in-place badge stamping on `List<LibraryItem>` vs a `CollapsedNovel` wrapper), take different arguments, treat singletons differently, and feed structurally different downstreams (the novel side unions tracks across group members; manga has no analogue). A merged generic would need a type parameter plus a stamp-vs-wrap strategy, more surface than the duplication removed. This confirms the merge-rebuild doc's prior call.
- **Compose the manga model, never dissolve it.** `MangaViewModel` is Mihon's, upstream-synced; the read host is composed inside `// RK` islands exactly like `EntryMergeActionHost`, so an upstream rename breaks the composition at compile time rather than drifting. The novel model, having no upstream, absorbs the host directly. Same shared host, two composition styles, dictated by which side has an upstream.
- **The novel source-resolution nuance is a seam, not a fork.** The async plugin load (`installer.ensureLoaded`) and the `siblingSources` map are novel-only, injected into the host as a per-type resolver, so they stay novel-side without duplicating the host.
- **Neutral method names over per-type aliases.** Renaming the ~two dozen call sites is the point of the collapse; keeping aliases would preserve the redundancy.

## Open questions (resolve before executing)

1. **DI shape for the unified manager**: two `EntryMergeManager` instances via Injekt qualifiers, or two thin `Manga`/`Novel` subclasses that fix `contentType` (smallest blast radius, keeps ~24 injection sites resolving by type). Recommend the subclass route unless a qualifier reads cleaner.
2. **Host scope**: should `EntryMergeGroupHost` also own the selected-source mirror and the chip flow, or only `relatedIds` + the membership observer? Recommend it owns all four read concerns (relatedIds, membership observer, chips, selectedSource); the chapter combine stays per-type and reads the host's `relatedIds`.
3. **`siblingSources`/plugin-load exposure**: keep the novel sibling-source map entirely novel-side (host returns only chips for manga, chips + sibling map for novel via the resolver), or hoist a neutral resolved-source map into the host for both. Recommend keeping it novel-side behind the resolver seam (manga has no equivalent need).
4. **`seriesGroupKeys` callers**: confirm both live in `NovelUpdatesViewModel` and that no separate manga updates model exists (the scout found the manga favorites path appears to run through that same file, worth a glance).
