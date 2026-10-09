# Library

## Purpose

The library is one screen listing every saved manga and light novel together, grouped into categories or dynamic groups, filtered, sorted and searched by one set of rules. All is the real view; the Manga and Novels chips narrow it. It also owns the categories themselves, the per-category sort, and the background jobs that keep library entries up to date.

## How it works

### Engine, providers and the tab

`LibraryTab` (a Mihon file, `// RK`-patched) resolves `LibraryViewModel` (manga) and `NovelLibraryViewModel` (novels), then builds one `LibraryEngine` through its assisted factory, handing it a `MangaLibraryAdapter` and a `NovelLibraryAdapter`. The factory builds the adapters, so exactly one pair lives as long as the engine; a separately `remember`ed pair would leave the tab and the engine holding different adapters after a tab switch.

Each adapter is a `LibraryProvider`: it yields `rows` (its favourites as `LibraryItem`s, merge-collapsed, filtered and search-matched for its own type, unbucketed and unsorted), `trackerMeans`, `trackKey`, `overlaid` (the custom-info overlay for one row), `dynamicGroupingFeed`, its settings, and the action verbs of `LibraryBehavior` (read, download, merge, categories, delete, and the questions the dialogs ask). The per-type models keep their favourites flow, their filtering and search, and their interactor calls; neither assembles a list.

`LibraryEngine` (an AndroidX `ViewModel`) owns everything that describes the list rather than a content type:

- the chip (`contentType`, the `libraryContentType` preference), and `setContentType`, which drops the selection through `EntrySelection.afterChipFlip` and hands the outgoing view's search query to the incoming one, so the library has one search;
- `assembled`, the list the tab renders;
- the selection as `Set<EntryId>` in a `SelectionStore`, and every selection verb;
- `dialog` (`LibraryDialog`: `ChangeCategory`, `Delete`, `Settings`), built by the engine from the providers' answers;
- `display` (`LibraryDisplayState`: display mode, columns, tabs, counts, Reikai's display block);
- collapse, one library-wide value written to the shared preferences;
- the pager seed (`initialPageFor`, `lastUsedCategoryPref`), `randomEntry`, `refresh`, and `settingsFor`.

`providersFor(ALL)` fans out to both providers. `behaviorFor(ALL)` fails loudly by design, reached only by explicit per-type callers (migration, update errors), since a mixed view has no single provider.

### Assembly

`LibraryEngine.assembleFor` combines the active providers' row flows with the category table (`CategoryRepository.getUnfilteredAsFlow`, narrowed for the chip by `categoriesForContentType`) and the assembly preferences, then:

- **By category** (`assembleLibrary` in `LibraryAssembly.kt`): bucket the concatenated rows into categories, order the categories, sort each bucket by `sortForCategory` (the category's override, else the global sort) through `librarySortComparator`, and drop every bucket left empty. An empty category is hidden on every chip, whether the chip, a filter, a search or plain emptiness emptied it.
- **By a dynamic group** (tag, author, source, language, status, tracking status, ungrouped): concatenate the providers' `dynamicGroupingFeed` outputs, call `LibraryDynamicGrouping.build` once over the union, resolve ids through the rows (dropping any with no row, then any empty bucket), and sort each bucket by the global sort. A source group stays per content type, since a manga source and a novel source are different sources; a tag shared by both types is one group.

The output is a `LibraryAssembled`: ordered `LibraryBucket`s (a sealed type, a real category or a dynamic group, and only the real case answers a `Category`), a per-bucket item read that applies each provider's `overlaid` lazily and memoizes per assembly, and the count rule (the chip-filtered bucket size, shown when the count preference is on or a search is active). Assembly is tagged with its chip; the tab renders it only when the chip matches. Assembly waits for every provider in the view: a provider's rows are null (`loadedRows`) until its model has loaded, and no assembly is emitted while one is null, so a cold start shows loading rather than an empty or half library.

`presentIdsOf` is what the selection is pruned to: the ids the assembly kept, not the rows it started from. A collapsed category and a pager page out of view are navigation and keep their selection.

### Rows, filter, sort and search

Both types reach the shared kernels as Mihon's `LibraryItem`. Novels build it before filtering (`NovelLibraryItem.kt`), carrying the novel's own positive id, `sourceName`, `sourceLanguage`, notes and update-interval fields; the row cannot say a novel's source in `Manga.source` (a `Long` against a plugin slug), so source facts travel beside it (`badges.source`, the typed `SourceBadge`, and the query fields).

- **Filter**: `libraryFilterMatches` over `LibraryFilterFields`, one pass with the tracker axis folded in. The stored filter preferences are one set for both libraries (the manga keys), read through `libraryFilterSettingsFlow`; `LibraryFilterSettings` owns `resolve` (what the predicate applies) and `isActive` (whether the filter icon lights; the custom-interval axis counts only while its release-period restriction is on). Downloaded-only mode is applied at the match step and never lights the icon.
- **Lewd**: `isAdultEntry` (`reikai/util/MangaLewd.kt`) is one rule for both types: a source marked 18+ (manga extensions and the novel APK kinds), an adult genre tag, or on manga an adult site name. LN plugins carry no rating, so tags alone decide them. A merged row is judged over every member's source and tags. An extension marked Mixed does not count here; `AdultContentChecker` runs the same kernel with Mixed counted. The adult-source set resolves only while the filter is set, behind a capped wait for the extension scan.
- **Sort**: `librarySortComparator` (`domain/.../LibrarySortComparator.kt`) over one persisted flags layout. The global sort and Random seed are one pair (`library_sorting_mode`, `library_random_sort_seed`). The alphabetical tiebreak stays A to Z under a descending sort, zero-unread entries sort last either way, Random is a per-id seeded comparator, and alphabetical uses the locale collator. `mixedLibraryItemSortFields` feeds the Random rank a type-unique key (`signedKey`). The tracker mean drops non-positive scores and dedupes by tracker.
- **Merged rows**: `LibraryMergeMembers.kt` (`memberIds`, `memberIdsOf`, `anyMerged`) answers which entries a row stands for, so every bulk verb, the tracker mean and `chapter:` search expand a group the same way. `mergedGroupTracks` (one track per tracker, the furthest-read) and `groupTrackStatus` feed the tracker filter, sort and tracking-status grouping. Collapse into one row per group is [merged-series.md](merged-series.md).
- **Search**: Mihon's query grammar (lexer, parser, AST) stays upstream; the evaluator is Reikai's, `libraryQueryMatches` over `LibraryQueryFields`, bound by `libraryItemQueryFields` for both types. It adds `chapter:` (alias `ch:`), resolved only for the terms typed (`chapterSearchTerms`) through a per-type chapter-name `LIKE` query. A custom title, author and the other overridable fields replace the source value for search through `LibraryQueryOverlay`, while filter, sort and grouping read source values. A gallery entry also answers the adult tag grammar per term (`matchesTagTerm`, `GallerySearchIndex`): `namespace:tag`, aliases, `*` and `?` wildcards (a real regex, `Text.asRegex`), `-` exclusion, quotes and `$` exact; the tag index is read only while a search runs over a library holding a gallery. User grammar: [library-search.md](../../library-search.md).
- **Group by language** labels with `browseLanguageLabel` and keys a bucket on the code (`normalizeDynamicKey`). A novel keeps its language and source name after its plugin is uninstalled (`NovelSourceManager.langOf`, `nameOf`).
- **Tracking-status grouping** orders by reading progress (`LibraryTrackingStatusOrder`) and ignores the A to Z category sort.

### Views

Two views share one assembly, and every library feature works in both: Mihon's tabbed pager (one page per bucket) and the single list (`ReikaiLibraryContent`, `show_all_categories`), one `FastScrollLazyVerticalGrid` of headers (`ReikaiLibraryCategoryHeader`: sort indicator, refresh, select-all) and cells (`LibraryItemCell`, which also draws the panorama mode). `ReikaiLibraryHopperOverlay` draws the floating hopper (`ReikaiCategoryHopper`) over either view, with its picker sheet and a long-press table whose six actions go through the engine; the jump itself stays in the tab, which owns the pager and list state. Each chip's list is its own composition (`key(libraryContentType)`), and the single-list scroll and pager page are kept per chip. The toolbar title is `LibraryToolbarTitleRule`.

### Categories

One `category` table serves both types with a `content_type` column (0 universal, 1 manga, 2 novel; default 1). Row 0 is the universal Default with a delete guard; `isSystemCategory` is an `id == 0` check. Membership stays in two junctions (`manga_category`, `novels_categories`) whose `insert` is an `INSERT ... SELECT` keeping only universal and own-type categories, so a wrong-type link is dropped at the database for every writer, restore included.

`CategoryScreen` (a whole-file rewrite of Mihon's) is one list for both types with an All / Manga / Novels chip (`categoriesForContentType`). A category's type is picked at creation and fixed. Create, reorder and delete go through `CategoryActions`, which renumbers the whole table; drag-reorder is offered only under All, because a drop index from a narrowed list would land elsewhere. Delete is deferred behind an undo snackbar, each delete its own batch, committed through `deleteCategoryAndCleanup` (renumber, then scrub the id from every category-id preference in `CategoryIdPreferences`). Hidden is bit 7 of `flags` (`CATEGORY_HIDDEN_MASK`); hidden state and per-category sort are properties of the category and apply wherever it shows.

Restore translates every category-id preference through `translateCategoryId` (old id, name, new id). `BackupCategory` carries the type at proto 8001 (default manga). A universal category is written in both the manga and novel backup lists, because `BackupNovel.categories` resolves by order within the novel list.

### Per-category sort

With "Per-category setting for sort" (`categorizedDisplaySettings`) on, every category follows the global sort unless bit 0 of its flags (`CATEGORY_SORT_CUSTOMIZED`) marks an override (`sortForCategory`, `isSortOverridden` in `CategorySortOverride.kt`). The toolbar sort always writes the global; a single-list header sets an override; `ResetToGlobalSortItem` clears one; turning the setting off clears every bit (`ResetCategoryFlags`, the `clearSortOverrides` query). `canOverrideSort` keeps the universal Default row on the global sort on read and write. Backups carry the bit verbatim plus marker field 719; `CategoriesRestorer.restoreCategorizedDisplay` turns the setting on when a restored category carries the bit, and a pre-719 backup is marked through `markLegacySortOverride` only where its sorts differ.

### Library updates

Each type has its own job on one schedule: Mihon's `LibraryUpdateWorker` and `NovelUpdateWorker`, both through `LibraryUpdateSchedule.kt` (constraints, flex window, manual tag, and the deferral rule for a scheduled run during a manual one or off Wi-Fi below Android 9). Both pick entries through `isUpdateScope` (a manual update of one category covers it alone, otherwise `matchesCategoryFilter`) and `smartUpdateSkip` (fetch-once with chapters, completed, has unread, unstarted, outside the predicted release window, in that order), reading only library rows (`smartUpdateFacts`). The novel job refreshes each novel through `refreshNovelFromSource`, the same helper the details refresh uses, which re-parses, stores source metadata (`storeRefreshedNovel`; a missing field keeps the stored one through `keptDetail`), syncs page 1 and walks newly opened pages; the new chapters are what the syncs report as new. Auto-download, the Updates badge and the notifications follow; the novel notifications use their own channels, and both notifiers share `NewChaptersSummary` and `NewChaptersDescription` (chapters named, cover icon, Download hidden above 15 new chapters).

Failures are recorded per content type (`trackUpdateErrors` and its novel half, on by default) into `library_update_errors` / `novel_update_errors` and shown by `UpdateErrorsScreen`; with recording off they go to the shared dump (`UpdateErrorLog`, one section per job). `updateErrorPendingIntent` decides where a failure notification's tap goes. The gallery update checker writes only the dump.

## Key files

- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`: the host, the engine factory call, the views and navigation.
- `app/src/main/java/reikai/presentation/library/LibraryEngine.kt`: `LibraryEngine`, `assembleFor`, `setContentType`, `initialPageFor`, `randomEntry`.
- `app/src/main/java/reikai/presentation/library/LibraryProvider.kt` and `app/src/main/java/reikai/presentation/library/LibraryBehavior.kt`: the per-type seam, `loadedRows`.
- `app/src/main/java/reikai/presentation/library/MangaLibraryAdapter.kt` and `app/src/main/java/reikai/presentation/library/NovelLibraryAdapter.kt`: the adapters.
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt` and `app/src/main/java/reikai/presentation/library/novels/NovelLibraryViewModel.kt`: the per-type providers' models.
- `app/src/main/java/reikai/presentation/library/LibraryAssembly.kt`: `assembleLibrary`, `LibraryAssembled`, `presentIdsOf`, `mixedLibraryItemSortFields`.
- `app/src/main/java/reikai/presentation/library/LibraryBucket.kt` and `app/src/main/java/reikai/presentation/library/LibraryDynamicGrouping.kt`: `LibraryBucket`, `libraryDynamicGroupingFeed`, `normalizeDynamicKey`.
- `app/src/main/java/reikai/presentation/library/LibraryItemFields.kt`, `app/src/main/java/reikai/presentation/library/LibraryFilter.kt`, `app/src/main/java/reikai/presentation/library/LibraryQueryMatch.kt`: `libraryFilterMatches`, `LibraryFilterSettings`, `libraryQueryMatches`, `LibraryQueryOverlay`, `matchesTagTerm`.
- `domain/src/main/java/reikai/domain/library/LibrarySortComparator.kt` and `domain/src/main/java/reikai/domain/library/CategorySortOverride.kt`: `librarySortComparator`, `sortForCategory`, `canOverrideSort`, `markLegacySortOverride`.
- `app/src/main/java/reikai/presentation/library/LibraryMergeMembers.kt` and `app/src/main/java/reikai/presentation/library/LibraryTrackerMeans.kt`: `memberIds`, `mergedGroupTracks`, `groupTrackStatus`.
- `app/src/main/java/reikai/util/MangaLewd.kt`: `isAdultEntry`, `hasLewdGenre`.
- `app/src/main/java/reikai/presentation/library/GallerySearchIndex.kt` and `app/src/main/java/exh/search/SearchEngine.kt`: the gallery tag grammar.
- `app/src/main/java/reikai/presentation/library/ReikaiLibraryContent.kt`, `app/src/main/java/reikai/presentation/library/ReikaiLibraryHopperOverlay.kt`, `app/src/main/java/reikai/presentation/library/LibrarySettingsSheet.kt`: the single list, the hopper, the one settings sheet.
- `app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt`: the library-wide preferences and the retired `DEAD_*` keys, which `app/src/main/java/reikai/data/backup/AppPreferenceCarry.kt` skips on restore.
- `data/src/main/sqldelight/tachiyomi/data/category.sq`, `data/src/main/sqldelight/tachiyomi/data/manga_category.sq`, `data/src/main/sqldelight/tachiyomi/data/novels_categories.sq`: the table, `clearSortOverrides`, the guarded inserts.
- `app/src/main/java/eu/kanade/tachiyomi/ui/category/CategoryScreen.kt`, `app/src/main/java/eu/kanade/tachiyomi/ui/category/CategoryViewModel.kt`, `app/src/main/java/reikai/presentation/category/CategoryActions.kt`: the one category screen.
- `domain/src/main/java/reikai/domain/category/DeleteCategoryCleanup.kt`, `app/src/main/java/reikai/domain/category/CategoryIdPreferences.kt`, `app/src/main/java/reikai/domain/category/CategoryFilter.kt`: `deleteCategoryAndCleanup`, `translateCategoryId`, `matchesCategoryFilter`, `isUpdateScope`.
- `app/src/main/java/reikai/data/novel/update/NovelUpdateWorker.kt`, `app/src/main/java/reikai/data/library/LibraryUpdateSchedule.kt`, `app/src/main/java/reikai/domain/library/SmartUpdateSkip.kt`, `app/src/main/java/reikai/data/novel/NovelRefresh.kt`: the novel job and its shared rules.
- `app/src/main/java/reikai/presentation/library/updateerror/UpdateErrorsScreen.kt`, `app/src/main/java/reikai/data/updateerror/UpdateErrorLog.kt`, `app/src/main/java/reikai/data/updateerror/UpdateErrorDestination.kt`: update errors.

## Invariants and traps

- **Key everything in assembly on `EntryId`.** `LibraryItem.id` is the raw table id, and a manga and a novel can share one.
- **The engine's preference-backed flows stay `by lazy`.** `LibraryEngineTest` constructs the engine directly; an eager flow resolves the graph and a scope at construction and breaks every case.
- **Construct the adapters only in the engine's factory call.** The engine outlives the composition.
- **Keep the overlay lazy and keyed.** `LibraryScreenState.overlayKey` carries each type's custom-info identity so an overlay-only edit re-emits the assembly; without it the state conflates and a rename never reaches the screen.
- **A track write re-runs assembly through `trackKey`.** The tracker-score sort and tracking-status groups read tracks on demand and the rows do not change on a track edit.
- **`isLibraryEmpty` is counted after filters.** Anything keying on it needs the no-active-filter guard, or a filtered-to-nothing library reads as empty.
- **Keep collapse out of the row flows.** A collapse input in a provider's pipeline makes every collapse tap rebuild that type's whole list.
- **Each chip's pager seeds only from its own saved page.** `updateActiveCategoryIndex` writes only that chip's key; no model holds a page index.
- **`MergeGroupRepository` refuses `ContentType.ALL`.** Never pass the chip down; Merge is enabled only for two or more entries of one type, and migration hides on a mixed selection.
- **The novel flag translation is a pure swap of two sort values** (Downloaded and TrackerMean, `novelCategoryFlagsToMangaLayout`). It ran once at its version gate; a later repair migration cannot tell an untranslated value from a correct one, and a novel category restored verbatim from an old backup can still show the other of the two.
- **Upgrade migrations here gate on their own version**: `SetupCategorySortOverrideMigration` (183), `MigrateNovelCustomCoverKeysMigration` (186), `MigrateNovelCategoriesToSharedTableMigration` (187, with an app-state marker so a rerun is a no-op), `CategoryPreferencesContentTypeCleanupMigration` (188).

## Decisions

- **All-first, with the chips as predicates.** Merging two independently assembled lists would look right while leaving both pipelines forked; every list-shaped value now has one answer.
- **Providers keep their own filtering and search.** They read different repositories and source managers; only assembly is shared.
- **The shared row is Mihon's `LibraryItem`.** Every grid composable already consumes it; a new neutral row would re-type them all. This is not novels-as-manga, which is about storage.
- **`LibraryViewModel` stays live as the manga provider.** It has real callers through `MangaLibraryAdapter`, so it is an engine file; members the takeover left without a reader are deleted, with an `// RK:` note where one moved.
- **One global sort, filter set and group-by for both types.** The novel keys are retired, not migrated, because the old novel sort layout is ambiguous; the cost is one re-pick.
- **Downloads never fan out across a merge group.** The library downloads the group's deduplicated list through `DownloadCandidates.forGroup`, skipping a chapter any member holds.
- **One category table with a content type, membership in two junctions.** Entry tables stay separate; a universal category answers what a mixed bucket means.
- **A category's type is fixed at creation.** Retyping would orphan memberships in the junction that no longer matches.
- **Universal categories stay in both backup lists.** Emitting one once would drop novel memberships, which resolve by order inside the novel list.
- **The toolbar sort is always global.** The tabbed pager has no per-category sort control; overrides are set from the single list.
- **Smart update reuses the manga restriction constants.** They are content-agnostic tags; renaming would touch manga code for nothing.

## Upstream divergences

`// RK` islands in `LibraryTab`, `LibraryViewModel` (the provider remainder, filters through the shared kernels, gallery search index), `LibrarySettingsViewModel`, `LibraryUpdateWorker` (update-error record, smart update, scope), `SetSortModeForCategory` and `ResetCategoryFlags`, `CategoriesRestorer` and `PreferenceRestorer`, `SettingsLibraryScreen` (the novel update group), and `category.sq`. `CategoryScreen` is a whole-file rewrite. Mihon's `LibrarySettingsDialog`, `QueryNodeExtensions` and the scoped category interactors (`CreateCategoryWithName`, `ReorderCategory`, `DeleteCategory`) are deleted and manifested in [off-path-manifest.md](../off-path-manifest.md). Recorded divergences: [upstream-sync.md](../upstream-sync.md) "Deliberate divergences".

## Extending

- **A new filter axis**: add it to `LibraryFilterFields` and `LibraryFilterSettings`, list it in each provider's `filterAxes`, and pin it in `LibraryFilterTest`. A type without the axis omits it from its list.
- **A new sort mode**: add it to `librarySortComparator` and `LibrarySortFields`, then `LibrarySortComparatorTest`.
- **A new group mode**: extend `libraryDynamicGroupingFeed` and `LibraryDynamicGrouping.build`; `LibraryDynamicGroupingFeedTest` runs every mode over both row types.
- **A new search field**: add it to `LibraryQueryFields` and `libraryItemQueryFields`, and to the user grammar page.
- **A new bulk verb**: put it on `LibraryBehavior`, implement it in both adapters, and dispatch it from the engine over the selection.
- **A new category-id preference**: register it in `CategoryIdPreferences`, so delete, upgrade and restore all handle it.

## Tests

Engine and assembly: `LibraryEngineTest`, `LibraryAssemblyTest`, `LibraryScreenStateTest`, `LibraryCategoryCollapseTest`. Kernels: `LibrarySortComparatorTest`, `LibraryFilterTest`, `LibraryQueryMatchTest`, `GallerySearchIndexTest`, `SearchEngineTest`, `LibraryDynamicGroupingTest`, `LibraryDynamicGroupingFeedTest`, `LibraryTrackingStatusOrderTest`, `LibraryTrackerMeansTest`, `LibraryMergeMembersTest`, `LibraryLewdConformanceTest`, `NovelLibraryItemTest`, `EntryIdKeysTest`. Categories: `CategorySortOverrideTest`, `CategoryViewModelTest`, `CategoryLinkGuardTest`, `CategoriesRestorerTest`, `CategorizedDisplayRestoreTest`, `DeleteCategoryCleanupTest`, `NovelCategoryFlagsMigrationTest`. Updates: `LibraryUpdateScheduleTest`, `NovelUpdateDefaultsTest`, `DownloadNewChaptersConformanceTest`, `UnsentDetailConformanceTest`, `NewChaptersTest`, `UpdateErrorConformanceTest`. Library tests run under `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`; the domain kernels under `:domain:test`. Device passes need both views and all three chips.

## Related

- User docs: [library-layout.md](../../library-layout.md), [library-search.md](../../library-search.md), [guides/categories.md](../../guides/categories.md).
- [merged-series.md](merged-series.md) for collapse, group counts and group tracking; [content-layer.md](content-layer.md) for the shared vocabulary.
