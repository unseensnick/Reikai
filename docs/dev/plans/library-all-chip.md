# Library: the All chip

## Goal

One library screen that lists manga and light novels together. **All is the real view**; the Manga and Novels chips are filter predicates over that one assembled list, not two separate screens with a switch between them. A category that holds both types appears once and holds both, interleaved under one sort.

## Why

The library is the last surface where the two content types are still two screens wearing one skin. Everything below the screen is already shared (the filter, sort, query, grouping and merge-collapse kernels all run for both types), but the list itself is still assembled twice and picked between, so anything list-shaped has to be decided twice and can drift.

Building All as the real version rather than bolting a third chip onto the existing pair is a deliberate choice by the owner: a composite stitched over two states inherits both states' quirks, and every value keyed to a row (its collapse state, its count, its identity) would keep needing a per-chip answer. With All as the base, those questions have one answer each.

## The rulings that shape it

Locked by the owner; treat these as settled and do not re-litigate.

- **All is the full version, the chips are filters.** Anything that describes the list or a row in it is one value. Anything that describes a content type stays per type.
- **Counts follow the active chip.** A universal category holding 3 manga and 2 novels shows 5 under All and 3 under Manga. The count is computed over the filtered rows, not stored on the category.
- **A category the chip empties is hidden.** Filtering to Manga hides novel-only categories rather than leaving a screen of empty headers. This is the existing `showEmptyCategoriesWhileFiltering` preference reaching its natural job, since under All the chip *is* a filter.
- **Collapse is per row, not per chip** (already shipped). A category collapses once, whichever chip is filtering.
- **The Default category follows the global sort on both types** (already shipped). Row 0 is universal, so an override on it could not mean two things.

## Approach

The shared layer already owns the selection, the dialogs, the settings sheet and the display config. What it does not own is **list assembly**: each provider still produces its own finished, bucketed, sorted list and the tab picks one. All-first inverts that.

**Providers yield rows, the shared layer assembles.** Each provider exposes its favourites as `List<LibraryItem>` already filtered and search-matched for its own type (that part is genuinely per type: different repositories, different source managers, different track tables). The shared layer concatenates the two lists, runs one sort over the mixed result, buckets it into categories from the one `categories` table, applies the chip predicate, drops the categories that predicate emptied, and hands the tab one list.

**The chip becomes a predicate, not a branch.** `ContentType.ALL` stops being the case that fails loudly and becomes the default; Manga and Novels are `rows.filter { it.entryId.contentType == chip }`. The 8 remaining `isNovels` branches in `LibraryTab` fall out as the tab stops needing to know which type is showing.

**The per-type models stay live below it.** `LibraryScreenModel` keeps its favourites flow and its action verbs and stays upstream-synced until phase 5 deletes it; the novel model keeps its own. Neither assembles a list any more.

**What must move to make that possible**, and is deliberately not done yet so it is shaped once for the mixed case:

- `groupedFavorites` and the four `State` helpers that read it (`displayedCategories`, the lazy id index, `coercedActiveCategoryIndex` with `activeCategory`, `containsMerged` with `memberIdsFor`) move into the shared layer.
- `LibraryScreenModel.State.reikai` retires. Three readers remain, all inside the manga model: the grouping inputs and the empty-category drop (`LibraryScreenModel.kt:226,229`) and the toolbar title (`:1051`). The first two source from the engine's flow instead; the third goes with the title work.
- `itemCountForCategory` becomes one rule. Manga returns null unless the count preference is on or a search is active; novels always return a count. Under one list this is one computation over the filtered rows, honouring the preference.

## Sequenced steps

1. **Rows out of the providers.** Add a neutral row flow to `LibraryProvider` that yields filtered, unsorted, unbucketed `List<LibraryItem>`. Both models already produce exactly this internally, before their own bucketing. Nothing consumes it yet, so it ships inert.
2. **Assembly in the shared layer.** The engine concatenates, sorts, buckets and hides emptied categories, producing the list the tab renders. Behind the existing chips it must produce byte-identical output to today, which is the test: switch the tab to the assembled list with the chip predicate applied and nothing visible changes.
3. **The chip becomes a predicate.** `providersFor(ALL)` already fans out to both; make `behaviorFor` and the assembly path stop failing on ALL, and add the All chip to the strip. The mixed list is real from here.
4. **Counts and empty categories** to the ruled behaviour, over the assembled list.
5. **Retire the per-type list state**: `State.reikai`, the four helpers, and the `isNovels` branches the assembly made unreachable.
6. **Parity closeout**: the toolbar title (still reads the manga state on both chips), novel search onto the query AST, and the hopper long-press scope.

## Key files

- `reikai/presentation/library/LibraryEngine.kt`: the shared engine, a Voyager `ScreenModel`. Owns selection, dialogs, display config, the chip, collapse, and the settings-description lookup. This is where assembly lands.
- `reikai/presentation/library/LibraryProvider.kt` and `LibraryBehavior.kt`: the per-type seam, 17 members. Providers answer about entries and perform writes; they do not open dialogs and do not own collapse.
- `reikai/presentation/library/LibraryScreenState.kt`: the neutral per-type state. Only genuinely per-type content lives here; library-wide values belong on the engine.
- `reikai/presentation/library/MangaLibraryAdapter.kt`, `NovelLibraryAdapter.kt`: the two adapters.
- The shared kernels, all already type-neutral and unit-tested: `LibraryItemFields.kt`, `LibraryFilter.kt`, `LibraryQueryMatch.kt`, `LibraryDynamicGrouping.kt`, `domain/reikai/domain/library/LibrarySortComparator.kt`.
- The two grouping builders, deliberately side by side: `MangaDynamicGrouping.kt` and `novels/NovelDynamicGrouping.kt`.
- `eu/kanade/tachiyomi/ui/library/LibraryTab.kt`: the consumer, 8 `isNovels` branches left (the definition, `libState`, the two per-type scroll/pager states, the update-errors preference and its screen seed, the migration screen, and the manga-only getting-started action).
- `eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt`: Mihon's, live and `// RK`-patched, deleted and manifested at the end of the programme.

## Status

Not started. The preparatory phases are done and device-verified: the behaviour seam, one settings sheet, and the two phase 3 prerequisites (collapse as one library-wide value, and the novel grouping builder extracted so the two cannot drift). The full record of those, and of the shared-pipeline work under them, is [content-layer-library-surface.md](content-layer-library-surface.md); the programme-level design is [content-layer-architecture.md](content-layer-architecture.md).

## Decisions & tradeoffs

- **All-first rather than a third chip.** Chosen by the owner as a purpose-built rebuild. The alternative, merging two independently assembled lists at the end, produces a correct-looking mixed list while leaving both pipelines forked, which is the failure this programme exists to remove.
- **Providers keep their own filtering and search.** They read different repositories and resolve different source managers, so filtering at the provider is honest; only assembly is shared. The row type is Mihon's `LibraryItem` on both sides, and novels already convert to it before filter and sort.
- **Interactors and repositories stay Mihon's.** The takeover stops at orchestration. Any step that starts reimplementing what `setReadStatus` or `DownloadManager` does has gone too far.
- **A source bucket stays per content type.** Manga encodes a numeric source id in the dynamic group's key where novels encode a plugin slug, so the two never merge into one bucket, which is correct: a manga source and a novel source are different sources. Pinned by `LibraryDynamicGroupingTest` over a mixed list.
- **No schema or backup change.** Categories are already one table with a `content_type` column and one id space; rows already carry real positive ids behind a neutral `EntryId`. Assembly is code-only.

## Gotchas worth knowing before starting

- `LibraryEngine`'s preference-backed flows are `by lazy` on purpose: `LibraryEngineTest` constructs the engine directly with mocked providers, and eager properties resolve Injekt and a coroutine scope at construction, which broke every case. Any new preference-backed member needs the same treatment.
- The engine outlives the composition, so the adapters must be constructed by the engine's own factory, never `remember`ed separately, or a tab switch hands the tab one pair while the engine dispatches through another.
- `isLibraryEmpty` is counted **after** filters on both content types, so anything keying on it needs the no-active-filter guard the tab already applies, or a filtered-to-nothing library reads as empty.
- Nothing validates `content_type` on the category junction tables, so a category assigned to the wrong type writes a row that appears in no picker and can never be removed. This is why the change-categories flow intersects the per-type assignable lists rather than merging them.
- Library-layer tests run under `:app:testDebugUnitTest`, not `:domain:test`.
- Device passes need both view modes (the tabbed pager and the single-list hopper) and, once All exists, all three chips.
