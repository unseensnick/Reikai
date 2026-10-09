# Content layer

## Purpose

Reikai serves manga and light novels from one Reikai-owned layer over a neutral `Entry` vocabulary, so a change written once reaches both content types. The two engines underneath (Mihon's manga stack and Reikai's novel stack) stay separate; what is shared is the orchestration, the screens and the rules. This is the overview to read first: each surface has its own doc, listed below. The rules a change must keep are binding law in [.claude/rules/content-layer.md](../../../.claude/rules/content-layer.md) and are not restated here.

## How it works

### Three layers

- **Engines, separate and unequal.** Mihon's manga engine (the `Manga` model, its repositories, sources, library and download machinery) stays upstream-tracked and minimally patched. Reikai's novel engine (`Novel` and `NovelChapter`, the `novel*` tables, `NovelSource` over LN plugins and the two APK kinds) is fully Reikai-owned. Interactors and repositories stay with their engine.
- **Adapters (or providers), the only seam.** The shared layer talks to each engine through one adapter per content type. On most surfaces the manga adapter wraps a live Mihon model (`MangaViewModel`, `LibraryViewModel`, `ReaderViewModel`, the two feed models), which stays on the render path and keeps syncing; a renamed upstream field breaks the adapter at compile time rather than in a pixel hunt. The novel adapter wraps the novel model, which is reshaped behind it rather than deleted.
- **The shared layer**, under `reikai.presentation.*` and `reikai.domain.*`: a neutral state, a behaviour contract or an engine that owns assembly, selection and the action verbs, and the `Entry*` composables that render both types.

### Identity: `EntryId`

Every entry in shared code is a sealed `EntryId` (`EntryId.Manga(rawId)` or `EntryId.Novel(rawId)`), never a raw `Long`. The two id spaces overlap (manga 12 and novel 12 both exist), so any map, set or key over a mixed list keys on `EntryId`, or it cross-wires silently. `EntryId` is code-only: novel rows carry real positive ids, and backups and the merge tables key on those, so it needed no schema or backup change. Two projections exist for caches that are not `EntryId`-typed: `customCoverKey` names the custom-cover file (manga `"12"`, novels `"novel:12"`), and `signedKey` negates a novel id for Mihon's `Long`-keyed `MangaCover.vibrantCoverColorMap`, a cache that rebuilds itself.

`ContentType` (`ALL`, `MANGA`, `NOVEL`) is the chip value and the category discriminator; `ContentType.labelRes` is the one place a content type is named in the UI.

### Surfaces

Each surface is at a different depth; the depth table in [.claude/rules/content-layer.md](../../../.claude/rules/content-layer.md) is the authority.

| Surface | Shape | Doc |
|---|---|---|
| Library | `LibraryEngine` over `MangaLibraryAdapter` / `NovelLibraryAdapter`; All is the real view, the chips are predicates | [library.md](library.md) |
| Details | `EntryDetailsBehavior` implemented by `MangaEntryAdapter` / `NovelEntryAdapter`, one `EntryDetailsContent` | [details.md](details.md) |
| Reader | `ReaderEngine` over two providers inside one `ReaderActivity` host | [reader.md](reader.md) |
| History, Updates | `RecentsEngine` over four feed models, one shared screen | [recents.md](recents.md) |
| Downloads | One queue engine (`EntryDownloadQueueViewModel`) over the two download managers | [downloads.md](downloads.md) |
| Browse, sources | One engine for the four multi-source lists, one catalogue screen, `NovelSource` over three kinds | [browse-and-sources.md](browse-and-sources.md) |
| Migrate | One flow (`EntryMigrationListViewModel` and the config screen) over two adapters | [migrate.md](migrate.md) |
| Merged series | One group store and stitch over both types | [merged-series.md](merged-series.md) |

### Capability slots

Where one type has something the other cannot, the shared state carries a typed slot, filled by the adapter that can and left null by the other, never a nullable field the shared code branches on. Examples: `EntryCapabilities` on details (the novel page selector, manga page previews, the related carousel, gallery metadata), `UndatedChapterDate` (manga shows "N/A", novels stay blank, because novel sources rarely date chapters), the browse filter dispatch (a typed `FilterList` against an LN plugin's JSON schema), and the per-type ports behind tracking (`EntryTrackPort`, picked by `EntryTrackPorts`). A capability a type cannot support is hidden for that type, never shown disabled.

### Kernels

A rule that must hold for both types lives once, as a pure function both sides call. The ones a maintainer meets most:

- Selection: `EntrySelection` (`reikai/presentation/selection/EntrySelection.kt`) holds the anchor inside `SelectionState` and offers `toggle`, `range`, `rangeOrToggle`, `rangeOrToggleBlock`, `selectAll`, `toggleBlock`, `invert`, `retain`, `afterChipFlip` and `clear`. Every multi-select surface (library, recents, both chapter lists, categories, update errors, recommendations, the migration favorites list, the manage-sources and duplicate dialogs) calls it. `SelectionStore` writes the state and the published set under one lock for the engines whose prune runs off the main thread.
- Categories: `resolveDefaultCategoryIds`, `categoryDiff`, `categoriesForContentType`, `matchesCategoryFilter`.
- Custom info: `EntryCustomInfo` and its `withCustomInfo` / `overlayCustomInfo` overlays (`domain/src/main/java/reikai/domain/entry/EntryCustomInfo.kt`).
- Chapters: `ReadingOrder`, `ChapterGap`, `DownloadCandidates`, `hiddenChapterKey`, `chapterSelectionOffers`.
- Library: `librarySortComparator`, `libraryFilterMatches`, `libraryQueryMatches`, `libraryDynamicGroupingFeed`.
- Updates: `smartUpdateSkip`, `isUpdateScope`, `keptDetail`, the `NewChaptersSummary` and `NewChaptersDescription` notification rules.
- Backup: `backupEntries` over `BackupEntryParts` (`reikai/data/backup/BackupEntryDriver.kt`) decides which series are backed up and gates chapters, categories, tracks, history and custom info once, while each type keeps its frozen wire fields.

### Conformance tests

Where the engines are genuinely separate and no kernel reaches, one test parameterized over both adapters pins the rule (`*ConformanceTest`, for example `DetailsGapConformanceTest`, `MergeGroupCountsConformanceTest`, `MarkReadDeleteConformanceTest`, `EntryTrackPortConformanceTest`). A `twin of` comment names the kernel, capability or test that pins it, in the spelling the rules fix.

### Replaced Mihon files

A pure-UI Mihon file fully replaced by a shared component is deleted and given a row in [off-path-manifest.md](../off-path-manifest.md); `refs/mihon` holds the diff base, and `scripts/off-path-check.ps1` fails a sync that touches a manifested path. Engine files (the per-type models, repositories, source managers) stay live and `// RK`-patched.

## Key files

- `app/src/main/java/reikai/domain/entry/EntryId.kt`: `EntryId`, `customCoverKey`, `signedKey`.
- `domain/src/main/java/reikai/domain/library/ContentType.kt` and `app/src/main/java/reikai/domain/library/ContentTypeLabel.kt`: `ContentType`, `labelRes`.
- `domain/src/main/java/reikai/domain/entry/EntryCustomInfo.kt`: `EntryCustomInfo`, `withCustomInfo`, `overlayCustomInfo`.
- `app/src/main/java/reikai/presentation/selection/EntrySelection.kt` and `app/src/main/java/reikai/presentation/selection/SelectionStore.kt`: the selection kernel.
- `app/src/main/java/reikai/presentation/library/LibraryEngine.kt`, `app/src/main/java/reikai/presentation/details/EntryDetailsBehavior.kt`, `app/src/main/java/reikai/presentation/reader/ReaderEngine.kt`, `app/src/main/java/reikai/presentation/recents/RecentsEngine.kt`: the surface seams.
- `app/src/main/java/reikai/domain/track/EntryTrackPort.kt`: `EntryTrackPort`, `EntryTrackPorts`.
- `app/src/main/java/reikai/data/backup/BackupEntryDriver.kt`: `backupEntries`, `BackupEntryParts`.
- `docs/dev/off-path-manifest.md` and `scripts/off-path-check.ps1`: the deleted Mihon files and the sync check.

## Invariants and traps

- **Key mixed collections on `EntryId`.** A `Long`-keyed map over manga and novels cross-wires a colliding pair with no crash, and a sort tie-breaker keyed that way ties every colliding pair.
- **A shared component either derives a piece of state or does not own it.** `EntryMergeGroupHost` keeps the group, its chips and the picked chip in one cell: a picked chip held beside them goes stale when its source is migrated out, which crashes the manga list and renders a stale source on novels.
- **A selection is pruned to what the surface excluded, never to what navigation hides.** A hidden category, an emptied bucket or a filtered row leaves the selection; a collapsed category or a pager page one swipe away keeps it. The library prunes to the ids its assembly kept, recents to the rows it drew.
- **`ContentType.ALL` never reaches a per-type store.** `MergeGroupRepository` and the per-type providers refuse it; only the engines fan out over a mixed view.
- **Adapters keep the engines' own write paths.** Read, download, filter and sort stay in the per-type interactors; a shared-layer step that reimplements `SetReadStatus` or `DownloadManager` has gone too far.

## Decisions

- **Separate tables and `EntryId`, not novels-as-manga.** Storing novels as `manga` rows (Tsundoku's shape) would fork `mangas.source` semantics, spray novel branches through Mihon's engine and risk source-id hash collisions. Void only if Reikai stops tracking Mihon.
- **The engines are never merged.** `source-api` is the contract installed extensions compile against, so a manga-shaped source boundary survives any merge, and re-typing `Manga` would make every Mihon file a hand-merge. The exemption covers the implementation, never the rule, and only a Reikai-to-Mihon twin.
- **The manga model stays live behind its adapter; the novel model is reshaped, not deleted.** The manga model is the sync surface; the novel model's engine work has nowhere else to go, so "dissolve" means the UI body and behaviour contract collapsed while both models remain.
- **Takeovers stop at orchestration.** Library, recents, browse, migrate, reader and downloads own assembly, selection and verbs above live per-type models, because a mixed list or a mixed selection cannot be computed by either model alone.
- **Scanlator filtering is a content-type-gated capability.** Novels store a chapter's translator group, but the novel filter is parked ([parked.md](../parked.md)); it is not a Tsundoku port, which gets it only through novels-as-manga.

## Extending

- **A new user-visible behaviour**: land it for both types in the same change, through the surface's engine or contract. If one type truly cannot support it, cite the mechanism and hide the control for that type.
- **A new rule both types need**: write a kernel both adapters call; failing that, a typed capability; failing that, one conformance test over both adapters.
- **A new keyed structure in shared code**: key it on `EntryId`.
- **Replacing a Mihon file wholesale**: delete it and add an [off-path-manifest.md](../off-path-manifest.md) row in the same commit.

## Tests

`EntrySelectionTest`, `SelectionStoreTest`, `EntryIdKeysTest` (the manga cover key must stay the plain id, or every manga custom cover orphans), `EntryCustomInfoOverlayTest`, `RecentsMappingTest`, `BackupEntryDriverTest`, and the `*ConformanceTest` classes under `app/src/test/java/reikai/`. Run one with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`.

## Related

- Binding rules: [.claude/rules/content-layer.md](../../../.claude/rules/content-layer.md).
- Upstream sync and the manifest: [upstream-sync.md](../upstream-sync.md), [off-path-manifest.md](../off-path-manifest.md).
