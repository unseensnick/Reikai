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

## How the screen works today

Read this before the steps; the whole plan is a rearrangement of it.

`LibraryTab.Content()` builds three `ScreenModel`s: the manga `LibraryScreenModel`, the novel `NovelLibraryScreenModel`, and `LibraryEngine` over an adapter for each (`LibraryTab.kt:128-151`). It then collects **both** adapters' states eagerly and picks one by chip: `val libState = if (isNovels) novelLibState else mangaLibState` (`:160-162`). Everything the tab renders comes off that one `LibraryScreenState`, whose `categories` field is already a finished, bucketed, sorted list. Assembly replaces that pick.

Each side reaches its finished list the same way, and the split point step 1 needs is visible in both:

- **Manga.** `favorites.applyFilters(...)` produces `filteredFavorites` (`LibraryScreenModel.kt:171`), stored as `LibraryData.favorites` (`:197`). Bucketing happens later, in `applyGrouping` (`:237`, defined `:393`), then `applySort` (`:444-446`). So the rows exist, filtered and unbucketed, at `:197`. Merge-collapse already ran: it happens inside `getFavoritesFlow` (`:570`), upstream of the filter, so the split point holds collapsed representatives and not group members.
- **Novels.** `items` is the filtered row list (`NovelLibraryScreenModel.kt:437`), `byId` its index (`:446`); bucketing into `byCategory` follows at `:455-461`. Same shape, same split point, and collapse likewise already ran.

Two things the bucketing step needs that are easy to miss:

- **Two visibility inputs, not just the category list.** `applyGrouping(categories, showSystemCategory, showHiddenCategories)` drops the system Default row unless some row is actually uncategorized, and drops hidden categories unless the user opted in. `showSystemCategory` is **derived from the rows** (`LibraryScreenModel.kt:170`), so under All it must be derived from the *mixed* rows, not from one type's.
- **The display overlay is applied late, on purpose.** Custom titles and covers are carried alongside the rows but deliberately **not** applied to them (`LibraryScreenModel.kt:163-165`, and again at `:199`); they are applied at the display read, in `State.getItemsForCategory`, which is why `LibraryScreenState.itemsForCategory` is a function rather than a map. Assembly must keep that contract. Applying the overlay eagerly over the assembled list would either cost a whole-library map on every emission or, if skipped, silently drop the user's custom titles and covers.

The chip is a preference, `reikaiLibraryPreferences.libraryContentType`, exposed as `LibraryEngine.contentType` and written by `setContentType`, which also clears the selection because a selection can span types. `ContentType` is `MANGA`, `NOVELS`, `ALL`; the strip currently offers only the first two (`LibraryTab.kt:391-396` area), and three engine methods fail loudly on `ALL` today: `behaviorFor` (`LibraryEngine.kt:105`), `settingsFor` (`:114`) and `openSettingsDialog` (`:284`).

## Sequenced steps

1. **Rows out of the providers.** Add a neutral row flow to `LibraryProvider` yielding filtered, unsorted, unbucketed `List<LibraryItem>`, sourced from the two split points above. Nothing consumes it yet, so it ships inert and the app is unchanged.
2. **Assembly in the shared layer.** The engine concatenates, sorts, buckets and hides emptied categories, producing what the tab renders instead of `libState.categories` and its item lookups. **Verification is a unit test, not a squint:** with one provider's rows and that provider's settings, the assembled output must equal what that model produces today, so pin it by feeding both paths the same fixture and comparing category order plus per-category id order. `LibraryEngineTest` already constructs the engine directly over `mockk<LibraryProvider>(relaxed = true)`, which is the pattern to extend. Device-check afterwards that nothing visible moved.
3. **The chip becomes a predicate.** `providersFor(ALL)` already fans out to both. Make the three `ALL` failure sites resolve and apply `rows.filter { it.entryId.contentType == chip }` for the single-type chips. Adding the chip itself is one line: `LibraryTab.kt:387-390` passes `types = listOf(ContentType.MANGA, ContentType.NOVELS)` to `ContentTypeFilterChips`, whose default is already `ContentType.entries`, so the component needs no change. The mixed list is real from here, so this is the first step with a wide device pass.
4. **Counts and empty categories** to the ruled behaviour, over the assembled list.
5. **Retire the per-type list state**: `State.reikai`, the four helpers, and the `isNovels` branches the assembly made unreachable.
6. **Parity closeout**: the toolbar title (still reads the manga state on both chips), novel search onto the query AST, and the hopper long-press scope.

## Key files

Paths are from the repo root, which **is** `.../yokai-y2k/app`. App-module sources therefore live under `app/src/main/java/`, and the sibling reference clones are at `../refs/`, outside the repo.

Under `app/src/main/java/reikai/presentation/library/`:

- `LibraryEngine.kt`: the shared engine, a Voyager `ScreenModel`. Owns selection, dialogs, display config, the chip, collapse, and the settings-description lookup. **This is where assembly lands.**
- `LibraryProvider.kt` and `LibraryBehavior.kt`: the per-type seam, 17 members. Providers answer about entries and perform writes; they do not open dialogs and do not own collapse.
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

**Not started, and step 2 is gated on open question 1 below** (which global sort a mixed category falls back to). Steps 1 can proceed without it. The preparatory phases are done and device-verified: the behaviour seam, one settings sheet, and the two phase 3 prerequisites (collapse as one library-wide value, and the novel grouping builder extracted so the two cannot drift). The full record of those, and of the shared-pipeline work under them, is [content-layer-library-surface.md](content-layer-library-surface.md); the programme-level design is [content-layer-architecture.md](content-layer-architecture.md).

## Decisions & tradeoffs

- **All-first rather than a third chip.** Chosen by the owner as a purpose-built rebuild. The alternative, merging two independently assembled lists at the end, produces a correct-looking mixed list while leaving both pipelines forked, which is the failure this programme exists to remove.
- **Providers keep their own filtering and search.** They read different repositories and resolve different source managers, so filtering at the provider is honest; only assembly is shared. The row type is Mihon's `LibraryItem` on both sides, and novels already convert to it before filter and sort.
- **Interactors and repositories stay Mihon's.** The takeover stops at orchestration. Any step that starts reimplementing what `setReadStatus` or `DownloadManager` does has gone too far.
- **A source bucket stays per content type.** Manga encodes a numeric source id in the dynamic group's key where novels encode a plugin slug, so the two never merge into one bucket, which is correct: a manga source and a novel source are different sources. Pinned by `LibraryDynamicGroupingTest` over a mixed list.
- **No schema or backup change.** Categories are already one table with a `content_type` column and one id space; rows already carry real positive ids behind a neutral `EntryId`. Assembly is code-only.

## Open questions

**1. Which global sort does a mixed category use? Blocking step 2.** Per-category overrides are fine: they live in the shared `categories` table's flags, so one row carries one override. The **fallback** is not. Manga reads `libraryPreferences.sortingMode` (a `LibrarySort`, key `library_sorting_mode`) and novels read `reikaiLibraryPreferences.novelLibraryDefaultSort` (a raw `Long` flag, key `novel_library_default_sort`); a category with no override currently gets whichever the active model supplies. Under one assembled list a mixed category needs one answer. Options: promote one preference to the library-wide global sort and migrate the other into it, keep both and let the chip choose (which contradicts the All-first rule, since All would then have no answer), or read the manga preference as the global and retire the novel one. **The same question applies to the Random seed** (`library_random_sort_seed` against `novel_library_random_seed`), which must be one value or a mixed Random sort is unstable across the two halves. Needs an owner ruling before assembly is written, because the shape of the sort input depends on it.

**2. Does the sort tab still offer a per-type global sort? Follows from 1, non-blocking.** If the global sort unifies, the settings sheet's Sort tab writes one preference for both types and the `globalSort` member on `LibrarySettingsBinding` (`LibrarySettingsBinding.kt`) collapses to one shared flow. Cheap either way, but it is the visible half of question 1.

## Gotchas worth knowing before starting

- `LibraryEngine`'s preference-backed flows are `by lazy` on purpose: `LibraryEngineTest` constructs the engine directly with mocked providers, and eager properties resolve Injekt and a coroutine scope at construction, which broke every case. Any new preference-backed member needs the same treatment.
- The engine outlives the composition, so the adapters must be constructed by the engine's own factory, never `remember`ed separately, or a tab switch hands the tab one pair while the engine dispatches through another.
- `isLibraryEmpty` is counted **after** filters on both content types, so anything keying on it needs the no-active-filter guard the tab already applies, or a filtered-to-nothing library reads as empty.
- Nothing validates `content_type` on the category junction tables, so a category assigned to the wrong type writes a row that appears in no picker and can never be removed. This is why the change-categories flow intersects the per-type assignable lists rather than merging them.
- **`itemsForCategory` and `itemCountForCategory` are functions on the neutral state for a reason**, not a precomputed map: they apply the per-entry custom-info overlay lazily, only for the categories actually rendered. Keep them lazy through the assembly, or a large library pays a whole-library map on every emission.
- Library-layer tests run under `:app:testDebugUnitTest`, not `:domain:test`.
- Device passes need both view modes (the tabbed pager and the single-list hopper) and, once All exists, all three chips.
