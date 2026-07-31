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
- **A category the chip empties is hidden.** Filtering to Manga hides novel-only categories rather than leaving a screen of empty headers. Note this is **not** simply the existing `showEmptyCategoriesWhileFiltering` preference finally applying: that preference has no novel reader at all (though the shared settings sheet renders its toggle under both chips, `ReikaiLibrarySettings.kt:67-70`, so on novels it is visible and inert today), novels already drop empty categories unconditionally at bucket time (`NovelLibraryScreenModel.kt:478-484`), and manga keeps them unless a filter is active with the preference off (`LibraryScreenModel.kt:228-229,243`). Unifying is a real behaviour change on both sides and needs deciding as one rule.
- **The manga global sort is the library-wide global sort** (ruled 2026-07-31). `library_sorting_mode` and `library_random_sort_seed` become the one global sort and Random seed both content types read; the novel keys (`novel_library_default_sort`, `novel_library_random_seed`) retire. The novel value is dropped, not migrated: a value never re-picked since the category unification can carry the old flag layout where Downloaded and TrackerMean are swapped, and the two readings are indistinguishable (see category-schema-unification.md, "sort-flag residues"), so a copy migration could silently rewrite the user's manga sort to a wrong-but-plausible mode. Cost of the clean fallback is one re-pick of a novel-only global sort; per-category overrides live in category flags and are untouched. Retired keys need a restore skip: `PreferenceRestorer`'s type check passes for keys absent from the live store (`prefs[key] is Long?` is true for null, `PreferenceRestorer.kt:103-106`), so an old backup would resurrect them without an explicit dead-key skip (precedent: `DEAD_LAST_USED_NOVEL_CATEGORY_KEY`, `:80`).
- **Collapse is per row, not per chip** (already shipped). A category collapses once, whichever chip is filtering.
- **The Default category follows the global sort on both types** (already shipped). Row 0 is universal, so an override on it could not mean two things.

## Approach

The shared layer already owns the selection, the dialogs, the settings sheet and the display config. What it does not own is **list assembly**: each provider still produces its own finished, bucketed, sorted list and the tab picks one. All-first inverts that.

**Providers yield rows, the shared layer assembles.** Each provider exposes its favourites as `List<LibraryItem>` already filtered and search-matched for its own type (that part is genuinely per type: different repositories, different source managers, different track tables). The shared layer concatenates the two lists, applies the chip predicate, **buckets into categories, then sorts within each category**, and hides the categories the predicate emptied. Bucket-before-sort is the order both sides already use, because the sort is per category: each category can carry its own override, so there is no single ordering to apply before bucketing.

**Everything is re-keyed on `EntryId`, not the raw row id.** This is the constraint that shapes the whole step. `LibraryItem.id` is the raw table id and a manga and a novel can share one (`LibraryItem.kt:27-31,41`), yet today's assembly structures are all `Long`-keyed: the bucket values, the favourites maps, the custom-info overlays, the tracker-mean maps, `memberIdsFor`, `novelRoutes`. Concatenating without re-keying produces silent cross-wiring rather than a crash, and the sharpest example is the Random sort, whose key is `Random(randomSeed + fields.id(it))` (`LibrarySortComparator.kt:79`), identical for a colliding pair.

**The chip becomes a predicate, not a branch.** `ContentType.ALL` stops being the case that fails loudly and becomes the default; Manga and Novels are `rows.filter { it.entryId.contentType == chip }`. The 8 remaining `isNovels` branches in `LibraryTab` fall out as the tab stops needing to know which type is showing.

**Beware: `ALL` crashes at composition today, and once that is fixed it degrades to manga.** The tab calls `engine.behaviorFor(libraryContentType)` unconditionally at `LibraryTab.kt:151`, and `behaviorFor` errors on a mixed view (`LibraryEngine.kt:105`), so flipping the chip to All crashes before anything renders (`LibraryEngineTest` pins this). That crash is the only loud failure: `isNovels` is `libraryContentType == ContentType.NOVELS` (`LibraryTab.kt:150`) and there is **no `when (contentType)`** in `LibraryEngine.kt` or `LibraryTab.kt`, so the moment `behaviorFor`/`settingsFor` learn an ALL answer, every `isNovels` consumer silently takes its manga branch, with every bulk verb manga-scoped because `entriesOf` can only yield `EntryId.Manga`. Removing `isNovels` therefore has to come **before** the chip is offered, not after, and the pinning test gets rewritten when assembly gives ALL a real answer.

**The per-type models stay live below it.** `LibraryScreenModel` keeps its favourites flow and its action verbs and stays upstream-synced until phase 5 deletes it; the novel model keeps its own. Neither assembles a list any more.

**What must move to make that possible**, and is deliberately not done yet so it is shaped once for the mixed case:

- `groupedFavorites` and the four `State` helpers that read it (`displayedCategories`, the lazy id index, `coercedActiveCategoryIndex` with `activeCategory`, `containsMerged` with `memberIdsFor`) move into the shared layer.
- `LibraryScreenModel.State.reikai` retires. Three readers remain, all inside the manga model: the grouping inputs and the empty-category drop (`LibraryScreenModel.kt:226,229`) and the toolbar title (`:1051`). The first two source from the engine's flow instead; the third goes with the title work.
- `itemCountForCategory` becomes one rule. Manga returns null unless the count preference is on or a search is active; novels always return a count. Under one list this is one computation over the filtered rows, honouring the preference.

## How the screen works today

Read this before the steps; the whole plan is a rearrangement of it.

`LibraryTab.Content()` builds three `ScreenModel`s: the manga `LibraryScreenModel`, the novel `NovelLibraryScreenModel`, and `LibraryEngine` over an adapter for each (`LibraryTab.kt:128-151`). It then collects **both** adapters' states eagerly and picks one by chip: `val libState = if (isNovels) novelLibState else mangaLibState` (`:160-162`). Everything the tab renders comes off that one `LibraryScreenState`, whose `categories` field is already a finished, bucketed, sorted list. Assembly replaces that pick.

Each side reaches its finished list the same way, and the split point step 1 needs is visible in both:

- **Manga.** `favorites.applyFilters(...)` produces `filteredFavorites` (`LibraryScreenModel.kt:171`), stored as `LibraryData.favorites` (`:197`). Bucketing happens later, in `applyGrouping` (`:237`, defined `:393`), then `applySort` (called `:242`, defined `:417`, global sort read `:444-445`). So the rows exist, filtered and unbucketed, at `:197`. Merge-collapse already ran: it happens inside `getFavoritesFlow` (`:570`), upstream of the filter, so the split point holds collapsed representatives and not group members.
- **Novels.** `items` is the filtered row list (`NovelLibraryScreenModel.kt:437`), `byId` its index (`:446`); bucketing into `byCategory` follows at `:455-461`. Same shape, same split point, and collapse likewise already ran.

**The two category lists overlap.** Manga reads `content_type IN (0,1)` and novels `IN (0,2)` (`categories.sq:35-44`, `:103-112`), so every universal row, including the system row 0, is bucketed **twice today**, once per model. Under All it must appear once. There is no union flow for the library: `CategoryRepository.getUnfiltered()` exists but has no library-facing interactor; its callers are the category manager (`CategoryActions.kt:37,89`) and `DeleteCategoryCleanup.kt:28`. Deciding what the mixed starting list is, and whether a manga-only category with no novels still shows under All, is part of assembly.

Three more things the bucketing step needs that are easy to miss:

- **Two visibility inputs, not just the category list.** `applyGrouping(categories, showSystemCategory, showHiddenCategories)` drops the system Default row unless some row is actually uncategorized, and drops hidden categories unless the user opted in. `showSystemCategory` is **derived from the rows** (`LibraryScreenModel.kt:170`), so under All it must be derived from the *mixed* rows, not from one type's.
- **Category order is applied at different points.** Manga reorders after bucketing and otherwise trusts the SQL `ORDER BY sort`; novels sort before bucketing and re-apply `sortedBy { it.order }`. A list assembled from two queries is not DB-ordered, so the shared step has to order explicitly rather than inherit it.
- **The display overlay is applied late, on purpose.** Custom titles and covers are carried alongside the rows but deliberately **not** applied to them (`LibraryScreenModel.kt:163-165`, and again at `:200-201`); they are applied at the display read, in `State.getItemsForCategory`, which is why `LibraryScreenState.itemsForCategory` is a function rather than a map. Assembly must keep that contract. Applying the overlay eagerly over the assembled list would either cost a whole-library map on every emission or, if skipped, silently drop the user's custom titles and covers.

The chip is a preference, `reikaiLibraryPreferences.libraryContentType`, exposed as `LibraryEngine.contentType` and written by `setContentType`, which also clears the selection because a selection can span types. `ContentType` is `MANGA`, `NOVELS`, `ALL`; the strip currently offers only the first two (`LibraryTab.kt:387-392`), and three engine methods fail loudly on `ALL` today: `behaviorFor` (`LibraryEngine.kt:105`), `settingsFor` (`:114`) and `openSettingsDialog` (`:284`), the first hit unconditionally at composition (`LibraryTab.kt:151`).

## Sequenced steps

0. **Unify the global sort** (per the 2026-07-31 ruling above). The novel pipeline's `miscFlow` reads `libraryPreferences.sortingMode` / `randomSortSeed` instead of the novel keys (`NovelLibraryScreenModel.kt:205-206`); `NovelLibraryAdapter` binds `globalSort` and routes `setSort`/`resetSort` exactly like `MangaLibraryAdapter` (through `SetSortModeForCategory` / `CategoryRepository`), which retires the novel model's own `setSort`/`resetSort`; the two novel preferences are deleted with a restore skip in `PreferenceRestorer`. No migration, no `versionCode` bump. This unblocks step 2's sort input and collapses `LibrarySettingsBinding.globalSort` to one shared source, resolving what were open questions 1 and 2.
1. **Rows out of the providers.** `LibraryProvider.rows` is a cold `Flow<List<LibraryItem>>` of filtered, unsorted, unbucketed rows, sourced from the two split points above. Both sides escape through state rather than a pipeline split: manga maps `libraryData.favorites`, and the novel `State` gained a `favorites` field carrying `items` in pipeline order (mirroring `LibraryData.favorites`), which proved simpler than splitting `buildState`. Each adapter's flow is `distinctUntilChanged`, since the backing state re-emits for grouping and collapse changes the rows are upstream of. Nothing consumes the flow yet, so it shipped inert.
2. **Assembly in the shared layer.** The engine concatenates, applies the chip predicate, buckets, sorts per category and hides emptied categories, producing what the tab renders instead of `libState.categories` and its item lookups. Everything it builds is keyed on `EntryId`. **Verification is a unit test, not a squint:** with one provider's rows and that provider's settings, the assembled output must equal what that model produces today, so pin it by feeding both paths the same fixture and comparing category order plus per-category id order. `LibraryEngineTest` already constructs the engine directly over `mockk<LibraryProvider>(relaxed = true)`, which is the pattern to extend; keep the assembled flow `by lazy`, since an eager one resolves Injekt and the scope at construction and fails every existing case. Device-check afterwards that nothing visible moved.
3. **Kill `isNovels` before offering the chip.** Every consumer of it must read the row's own `EntryId` or the assembled state instead, because `ALL` silently takes the manga branch of each (see the Approach note). The per-type navigation targets that legitimately survive (migration, update-errors, the getting-started action) need an explicit `ALL` answer rather than falling through. Only then flip `LibraryTab.kt:387-390`, which passes `types = listOf(ContentType.MANGA, ContentType.NOVELS)` to `ContentTypeFilterChips`; the component already defaults to `ContentType.entries`, so it needs no change. The three `ALL` `error(...)` sites resolve as part of this. First step with a wide device pass.
4. **Counts and empty categories** to the ruled behaviour, over the assembled list.
5. **Retire the per-type list state**: `State.reikai`, the four helpers, and the `isNovels` branches the assembly made unreachable.
6. **Parity closeout**: the toolbar title (still reads the manga state on both chips), novel search onto the query AST, and the hopper long-press scope.

## Key files

Paths are from the repo root, which **is** `.../yokai-y2k/app`. App-module sources therefore live under `app/src/main/java/`, and the sibling reference clones are at `../refs/`, outside the repo.

Under `app/src/main/java/reikai/presentation/library/`:

- `LibraryEngine.kt`: the shared engine, a Voyager `ScreenModel`. Owns selection, dialogs, display config, the chip, collapse, and the settings-description lookup. **This is where assembly lands.**
- `LibraryProvider.kt` and `LibraryBehavior.kt`: the per-type seam (`LibraryBehavior` has the 17 action members; `LibraryProvider` adds `contentType` and `settings`). Providers answer about entries and perform writes; they do not open dialogs and do not own collapse.
- `LibraryScreenState.kt`: the neutral per-type state the tab renders. Only genuinely per-type content lives here; library-wide values belong on the engine.
- `MangaLibraryAdapter.kt`, `NovelLibraryAdapter.kt`: the two adapters, constructed by the engine's own factory.
- Type-neutral shared kernels, all unit-tested: `LibraryItemFields.kt`, `LibraryFilter.kt`, `LibraryQueryMatch.kt`, `LibraryDynamicGrouping.kt`.
- The two grouping builders, deliberately side by side: `MangaDynamicGrouping.kt` and `novels/NovelDynamicGrouping.kt`.

Elsewhere:

- `domain/src/main/java/reikai/domain/library/LibrarySortComparator.kt`: the shared comparator both types sort through. `CategorySortOverride.kt` beside it owns the per-category override rule.
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`: the consumer, 8 `isNovels` branches left (the definition, `libState`, the two per-type scroll/pager states, the update-errors preference and its screen seed, the migration screen, and the manga-only getting-started action).
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt`: Mihon's, live and `// RK`-patched, deleted and manifested at the end of the programme.
- `app/src/main/java/reikai/presentation/library/novels/NovelLibraryScreenModel.kt`: Reikai's, the novel twin.
- Tests: `app/src/test/java/reikai/presentation/library/`.

## Status

**Steps 0 and 1 are built (compile, spotless and the unit suite green) and step 0 is device-verified on the emulator; step 2 is next; nothing is gated on an open question any more** (the sort ruling above resolved what gated step 2). The step 0 pass confirmed: a sort picked under the Novels chip writes `library_sorting_mode` (the retired novel key stays untouched), both lists and headers re-sort live, and picking Random under Novels regenerates the shared Int seed. Not exercised: a per-category override winning over the new shared global (no device category carries one; the read path is `sortForCategory`, unit-tested and unchanged). The preparatory phases are done and device-verified: the behaviour seam, one settings sheet, and the two phase 3 prerequisites (collapse as one library-wide value, and the novel grouping builder extracted so the two cannot drift). The full record of those, and of the shared-pipeline work under them, is [content-layer-library-surface.md](content-layer-library-surface.md); the programme-level design is [content-layer-architecture.md](content-layer-architecture.md).

## Decisions & tradeoffs

- **All-first rather than a third chip.** Chosen by the owner as a purpose-built rebuild. The alternative, merging two independently assembled lists at the end, produces a correct-looking mixed list while leaving both pipelines forked, which is the failure this programme exists to remove.
- **Providers keep their own filtering and search.** They read different repositories and resolve different source managers, so filtering at the provider is honest; only assembly is shared. The row type is Mihon's `LibraryItem` on both sides, and novels already convert to it before filter and sort.
- **Interactors and repositories stay Mihon's.** The takeover stops at orchestration. Any step that starts reimplementing what `setReadStatus` or `DownloadManager` does has gone too far.
- **A source bucket stays per content type.** Manga encodes a numeric source id in the dynamic group's key where novels encode a plugin slug, so the two never merge into one bucket, which is correct: a manga source and a novel source are different sources. Pinned by `LibraryDynamicGroupingTest` over a mixed list.
- **No schema or backup change.** Categories are already one table with a `content_type` column and one id space; rows already carry real positive ids behind a neutral `EntryId`. Assembly is code-only.
- **The global sort unified by retiring the novel keys, not by migrating them** (ruled 2026-07-31; the fallback question and its Random-seed twin, plus whether the Sort tab stays per-type, were open questions until then). The clean fallback was chosen over a value-adopting middle path: adopting the novel value only when it decodes outside the ambiguous {Downloaded, TrackerMean} set was considered and declined, since it keeps a two-preference read path alive for one release to save one re-pick.

## Gotchas worth knowing before starting

- `LibraryEngine`'s preference-backed flows are `by lazy` on purpose: `LibraryEngineTest` constructs the engine directly with mocked providers, and eager properties resolve Injekt and a coroutine scope at construction, which broke every case. Any new preference-backed member needs the same treatment.
- The engine outlives the composition, so the adapters must be constructed by the engine's own factory, never `remember`ed separately, or a tab switch hands the tab one pair while the engine dispatches through another.
- `isLibraryEmpty` is counted **after** filters on both content types, so anything keying on it needs the no-active-filter guard the tab already applies, or a filtered-to-nothing library reads as empty.
- Nothing validates `content_type` on the category junction tables, so a category assigned to the wrong type writes a row that appears in no picker and can never be removed. This is why the change-categories flow intersects the per-type assignable lists rather than merging them.
- **`itemsForCategory` and `itemCountForCategory` are functions on the neutral state for a reason**, not a precomputed map: they apply the per-entry custom-info overlay lazily, only for the categories actually rendered. Keep them lazy through the assembly, or a large library pays a whole-library map on every emission.
- **Merging the two flows couples their emission rates.** Each side has its own download-cache tick, and one completed chapter download would re-run assembly over both types' rows. The guards are also unequal: the manga combine has `distinctUntilChanged` (twice, one on a deliberately narrowed grouping key), the novel combine has none, and each side debounces search on its own 250 ms timer, so a keystroke burst reaches a naive assembler twice. Hoist the query above both, and do not let the weaker guard become the shared one.
- **Keep collapse out of the row flows.** The novel pipeline currently takes the dynamic-collapse preference as a combine input (`NovelLibraryScreenModel.kt:164-168`) while the manga side reads collapse off the engine's display state and never re-enters its pipeline. Downstream of assembly, that asymmetry would make every collapse tap rebuild both content types.
- **Several verbs assume a single type today and would misfire on a mixed selection:** migration flattens `rawId` across two id spaces (`LibraryTab.kt:413`), and Merge's enable gate counts the whole selection (`:425`), so a mixed two-plus-two would create two separate groups from one gesture. Neither is a crash, so neither announces itself.
- **`MergeGroupRepository` rejects `ContentType.ALL` explicitly** (eleven `error(ALL_UNSUPPORTED)` sites in `reikai/data/merge/MergeGroupRepositoryImpl.kt`, one of them the enum-to-string mapper the other ten route through). That is correct and should stay: merge groups are per type, and only the providers should reach it, each with its own hardcoded type. Assembly must never pass the chip down.
- Library-layer tests run under `:app:testDebugUnitTest`, not `:domain:test`.
- Device passes need both view modes (the tabbed pager and the single-list hopper) and, once All exists, all three chips.
