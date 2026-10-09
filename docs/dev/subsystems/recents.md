# Recents (Updates, History and the combined tab)

## Purpose

Recents is the recent-activity surface: new chapters (Updates), what was read (History), and, behind a setting, one combined Recents tab that also shows newly added series and suggests what to read next. Manga and novels share one screen, one engine and one set of rules; only the feeds behind it are per type. A home-screen widget shows the same updates outside the app.

## How it works

### Surfaces, modes and lanes

A **lane** is one kind of activity, `RecentsLane`: `Read` (the chapter last opened), `Updated` (a fetched chapter) and `Added` (the series entered the library, so it carries no chapter). A **mode** (`RecentsMode`) is a render policy over some lanes: `UPDATES` draws the updated lane, `HISTORY` the read lane, and the two combined modes, `FEED` and `DIGEST` (shown as Grouped), draw all three. A **surface** (`RecentsSurface`) is one rendered tab: `UPDATES`, `HISTORY` or `RECENTS`.

With `combinedRecentsTab` (`pref_combined_recents_tab`, Appearance) off, the default, the bottom bar holds the Updates and History tabs, each rendering one mode. With it on, `HomeScreen` swaps both for `RecentsTab`, which renders all four modes behind a mode strip (`RECENTS_MODE_ORDER`: Grouped, Feed, History, Updates). The current mode is persisted in `recents_mode` by constant name; a surface ignores a stored mode it does not render. The two launcher shortcuts resolve through the setting, so they open the combined tab on the matching mode when it is on.

### The seam

Each surface builds one `RecentsEngine` (a ViewModel, through `rememberUpdatesEngine`, `rememberHistoryEngine` or `rememberRecentsEngine` in `RecentsEngines.kt`) over two `RecentsProvider`s, one per content type:

- `MangaRecentsAdapter` wraps Mihon's live `UpdatesViewModel` and `HistoryViewModel`.
- `NovelRecentsAdapter` wraps Reikai's `NovelUpdatesViewModel` and `NovelHistoryViewModel`.

The four models are now only feeds: they run the lane queries and nothing else. Each adapter is built per surface (`forUpdates`, `forHistory`, `forRecents`), so a History tab never builds an updates model or runs its query. All four models take their `RecentsSurface` as an assisted parameter, so each tab's feeds read that tab's own category selection; resolve them only through the helpers in `RecentsEngines.kt`.

A provider emits three lane flows (`RecentsLaneRows`, rows plus a `loaded` flag), membership, `lastUpdated`, `updating`, an `unreadEntries` set and a `targetInputs` signal, and answers per-row questions: `rowUi`, `downloadUi`, `targetChapter`, `targetRow`, `latestRead`, `open`, `detailsScreen`. `RecentsBehavior` holds the verbs: the four chapter verbs behind `RecentsChapterActions` (`MangaRecentsChapterActions`, `NovelRecentsChapterActions`, built from the graph on every surface), history removal, the add flow, `clearHistory` and `refresh`. A provider's own row type rides in `RecentsItem.payload` and is never read by shared code.

Identity is `EntryId` for entries and `ChapterRef` (an `EntryId` plus the raw chapter id) for chapters, because both id spaces overlap between the two tables.

### Assembly and rendering

1. **Collect.** The engine collects every lane its modes need from every provider, always. The Manga / Novels chip only selects which providers' rows enter assembly, so a chip flip never waits on a query.
2. **Assemble** (`assembled`). Rows from the active providers are ordered by `orderRecents` (newest first, ties broken by lane with Read first, then content type, then entry id), filtered by the search query, and tagged with the chip that produced them, since the flow lags a chip flip by one emission. Loading is derived over the active providers only.
3. **Render** (`rendered`). `renderRows` filters the assembly to the mode's own lanes, applies the row gate (`showsRow`), then the mode's policy in `RecentsPolicies.kt`:
   - `historyRows`: day headers, merged sources collapsed (SQL already gives one row per entry).
   - `updatesRows`: day headers; with Group by series on, a series' two or more chapters from one day become one expandable group row, merge-aware.
   - `flatRecentsRows` (Feed): one row per series (`collapseByGroup`), no headers.
   - `digestRows` (Grouped): collapsed across lanes first, so a series claims one section (its newest activity); then New chapters capped at four, Continue reading at nine minus that, Newly added at four. Sections sort by their newest row; the two chapter sections get a footer jumping to their single-lane mode.
4. **Prune.** The selection is pruned to what `rendered` draws, so a row a cap or filter took off screen is neither counted nor acted on.

Everything describing the list is one value on the engine: the chip, the query, loading, emptiness, `filterActive`, the selection, the dialog slot and the last-updated line. `lastUpdated` and `refreshing` (the update jobs' real running state) come from the active providers.

### Filters

- **Category.** Each surface has its own master toggle plus include and exclude sets (nine keys in `ReikaiSourcePreferences`), resolved by `recentsCategoryFilterFlow(surface)` into `RecentsCategoryFilter`. A disabled toggle arrives as empty lists, since emptiness is the queries' only "no constraint" signal. The predicate runs in SQL on all six feeds: both updates views, both history views and both recently-added queries. Uncategorized is sentinel id 0.
- **Chapter state** (unread, downloaded, started, bookmarked, from `UpdatesPreferences`). Only modes with `RecentsCapability.CHAPTER_FILTER` apply them (Updates, Feed, Grouped). The updated lane is filtered by its query; the read lane is judged in memory against its chapter state in `RecentsChapterFilters`, download state resolved on demand; the added lane is never judged. Started means in progress or not yet opened, and unread either way.
- **Caught-up series.** With `recents_show_read` off (the default), the combined modes drop a series with nothing unread (`RecentsRowGate.keeps`), asked once per emission from `recentsUnread.sq` through `unreadEntries`. A merged series is unread while its stored stitch has a unit with no read copy; excluded scanlators are left out on manga. The engine subscribes to that set only while `RecentsRowGate.needsUnread` holds.
- **Excluded scanlators** reach only a provider answering `RecentsTypeCapability.SCANLATOR_FILTER` (manga). `UPCOMING` gates the Upcoming calendar the same way. `chipCapabilities` is the union over the providers behind the chip.

`RecentsFilterSheet` has three tabs (General: categories and Show caught-up; Chapters: the four filters and the scanlator switch; Updates: Group by series) and draws every control on every surface, each page captioned with the sections it reaches. It writes through `UpdatesSettingsViewModel`, created per `RecentsSurface`.

### What a tap opens

`RecentsEngine.open` decides, and the row's label follows the same decision:

- **Updated row**: the chapter it names, in every mode, as Mihon's Updates tab does. Opened source-scoped (`RecentsLane.sourceScoped`).
- **Read row**: the recorded chapter while it is unfinished, else the oldest unread chapter of the series (`resumeInGroup`, `resumeTarget`), over the merge group's stitched list with what another source read counted as read; a recorded chapter the stitch dropped resumes in its own source's list. Opened in group scope.
- **Added row**: the group's first unread, else the entry's own (`addedTarget`).

The lane dispatch is `resolveRecentsTarget` in `RecentsTarget.kt`. Every provider hands the rules a list in ascending reading order (`RecentsChapter`), hidden chapters last.

**Continue-reading rows move onto their target.** In a combined mode, a read row whose recorded chapter reads as read (or whose entry is merged) resolves its target (`resolvesTarget`) and draws it whole: name, read state, bookmark, progress and download control (`targetRow`, `RecentsTargetRow`). History never resolves: its row names, dims and acts on the record it logs. Resolution is lazy, per drawn row, on IO, and memoized in `targets`, keyed by the recorded lane. The memo is emptied by `clearTargets` on any provider's `targetInputs` (chapters, the entry row, membership, the stitch, excluded scanlators, and the preferences a resolve reads) and on a mode switch; a search keystroke or download tick does not empty it. A resolve that straddles a clear is retried rather than stored (`targetsGeneration`). The four bulk verbs act on `actingChapters`, the chapter each selected row names, resolved before dispatch.

Tab reselect on a mode with a read lane resumes the newest read across the active providers (`resumeLatest`), from each type's unfiltered latest-history query; in Updates mode it opens the download queue.

### Rows

Updates draws the 56dp row (`UpdatesRowShell`), with group and child rows from `RecentsRows.kt`. History and the combined modes draw `RecentsCombinedRow`: title, chapter with unread dot, a time line with the lane's verb, and the progress line. In the combined modes a row older than today shows a date from `relativeDateText`; History and Updates rows show the clock time under their day header. The trailing control follows the lane: an update downloads, a read row deletes its record (and offers add-to-library when the entry is not a favorite), an added row has neither. Swipe (`ChapterSwipeBox`, the details list's two swipe preferences through `ChapterSwipeActions`) attaches to updated-lane rows only and leaves a running selection alone.

### Widget

`UnifiedUpdatesGlanceWidget` shows recent manga and novel updates in two labelled strips, refreshed by `UnifiedUpdatesWidgetManager`. Both read one flow, `unifiedWidgetUpdates`, which asks unread in SQL on both types (manga through `GetUpdates.subscribe(read = false)`, novels through the filtered updates query with `unread = true`). It lives in the app module because the novel query and `NovelCover` do. Mihon's `UpdatesGridGlanceWidget` stays the manga-only widget.

## Key files

- `app/src/main/java/reikai/presentation/recents/RecentsEngine.kt`: `RecentsEngine` (`assembled`, `rendered`, `open`, `targetRow`, `actingChapters`, `resumeLatest`), `recentsFilterActive`.
- `app/src/main/java/reikai/presentation/recents/RecentsProvider.kt` and `RecentsBehavior.kt`: the seam, `recentsTargetInputs`, `recentsAddedLane`, `RecentsChapterActions`.
- `app/src/main/java/reikai/presentation/recents/MangaRecentsAdapter.kt`, `NovelRecentsAdapter.kt`: the two providers and their per-surface factories.
- `app/src/main/java/reikai/presentation/recents/MangaRecentsChapterActions.kt`, `NovelRecentsChapterActions.kt`: the chapter verbs.
- `app/src/main/java/reikai/presentation/recents/RecentsItem.kt`: `RecentsItem`, `RecentsLane`, `ChapterRef`.
- `app/src/main/java/reikai/presentation/recents/RecentsMode.kt`: `RecentsMode`, `RecentsCapability`, `RECENTS_MODE_ORDER`.
- `app/src/main/java/reikai/presentation/recents/RecentsAssembly.kt` and `RecentsPolicies.kt`: `orderRecents`, `collapseByGroup`, `renderRows`, the four policies.
- `app/src/main/java/reikai/presentation/recents/RecentsTarget.kt`: `resolveRecentsTarget`, `resumeInGroup`, `resumeTarget`, `addedTarget`.
- `app/src/main/java/reikai/presentation/recents/RecentsChapterFilters.kt`: `RecentsChapterFilters`, `RecentsRowGate`.
- `app/src/main/java/reikai/presentation/recents/RecentsScreen.kt`, `RecentsRows.kt`, `RecentsFilterSheet.kt`: the shared screen, rows and sheet.
- `app/src/main/java/reikai/presentation/recents/RecentsEngines.kt`, `RecentsTab.kt`, `RecentsTabBody.kt`, `ShowsUpdatesBadge.kt`: engine builders and the tab hosts.
- `app/src/main/java/reikai/domain/category/RecentsCategoryFilter.kt`: `RecentsSurface`, `recentsCategoryFilterFlow`, `seedRecentsSurfaceFromUpdates`.
- `app/src/main/java/reikai/domain/recents/RecentsFeedBounds.kt`: `RECENTS_FEED_LIMIT`, `recentsFeedCutoff`.
- `data/src/main/sqldelight/tachiyomi/view/updatesView.sq`, `novelUpdatesView.sq`, `historyView.sq`, `novelHistoryView.sq`, `recentlyAddedView.sq`, `recentsUnread.sq`: the feeds.
- `data/src/main/java/reikai/data/recents/RecentlyAddedRepositoryImpl.kt`: the added lane for both types.
- `app/src/main/java/reikai/presentation/updates/NovelUpdatesViewModel.kt`, `app/src/main/java/reikai/presentation/history/NovelHistoryViewModel.kt`, `HistoryFeedFailure.kt` (`emptyOnFailure`).
- `app/src/main/java/reikai/presentation/widget/UnifiedWidgetUpdates.kt`, `UnifiedUpdatesGlanceWidget.kt`, `UnifiedUpdatesWidgetManager.kt`.
- `app/src/main/java/reikai/presentation/components/ReadProgressLabel.kt`: `pageProgressLabel`, `percentProgressLabel`.

## Invariants and traps

- **Never gate a provider's collection on the chip.** The chip selects providers at assembly; gating collection is what once let an unloaded novel lane hold the manga chip's spinner.
- **Every keyed structure keys on `EntryId` or `ChapterRef`.** A `Long`-keyed map over a mixed feed cross-wires silently instead of crashing.
- **Ask lane-dependent questions of the mode on screen, not of the surface.** A surface rendering four modes always has the updated lane somewhere; `filterActive`, `renderRows` and the trailing control all once answered for the surface and were wrong on the combined tab.
- **A row's label and its tap are one decision.** Both go through `resolvesTarget`; a new row shape that names one chapter and opens another is the defect the target memo exists to prevent.
- **Never empty the target memo on a lane emission.** The manga updated lane re-emits on every download tick, which re-resolved every drawn row several times a second.
- **Hand the target rules ascending reading order.** Manga's merged list arrives newest-first; unsorted, "next" means "previous".
- **A provider's lane carries its own `loaded` flag.** A lane mapped off seeded state emits an empty list on subscribe, so "has emitted" reads loaded with nothing on screen. Both history models seed their feed `null` for not-loaded, and a failed history query emits empty through `emptyOnFailure` rather than loading forever.
- **The two updates feeds bound on different columns.** Manga on chapter upload date, novels on fetch date, because many novel sources leave upload date at zero.
- **The widget reads the filtered novel updates query with only `unread = true`.** Changing that query's filters or defaults changes the widget.
- **`InMemoryPreferenceStore.changes()` emits nothing.** A rule combining preference flows reads only its seed in a test; pin it as a pure function, or use `EmittingPreferenceStore`.
- **A pin on `assembled` cannot catch eager sharing**: its upstream runs on `Dispatchers.Default` outside the test scheduler.
- **A merged row's own chapter is always among its copies** (`copiesOfChapters`, `copiesOfNovelChapters`), even when its scanlator is excluded or its owner left the library; without it the row's lookup throws while drawing.
- **Download state is looked up by the stored title, not the custom one.** Both history models overwrite `title` with the user's custom title while the download folder is named from the stored one, so rows carry `storedTitle` for the lookup.
- **The downloaded filter re-evaluates with the feed, not the download index.** A chapter downloaded while a filtered History-lane view is open holds its place until the next emission.
- **The merge repository rejects the mixed content type.** Only the providers reach it, each with its own type; assembly never passes the chip down.

## Decisions

- **All-first.** All is the real feed and the chips are predicates over it; storing list state per type is what let the two replaced screens disagree with themselves (two searches, two clear-all prompts).
- **One category selection and one chip per rendered surface.** With the combined tab off, Updates and History must behave as independent tabs; the combined tab seeds its own from Updates once (`seedRecentsSurfaceFromUpdates`). Void if the two-tab shape is removed.
- **The selection is `Set<String>` in the shared category-id registry, not upstream's `List<Long>` keys.** The registry is what makes category delete, scrub and restore remap reach every key; upstream's SQL and interactor halves are taken.
- **The category master toggle stays.** It parks a selection without applying it, and the library carries the identical switch.
- **A single-type chip does not ignore a filter it cannot satisfy.** Ignoring it would be a silent no-op for one type; the empty feed says a filter caused it and offers the sheet instead.
- **The seam owns the whole add flow.** `addToLibrary` runs the shared add decision once and raises the duplicate, category and migrate prompts on the engine's one dialog slot, through the two adders rather than the models.
- **Bounded, not paged.** Each lane is capped (three months and 500 rows for updates and added; the read lane is one row per entry by SQL), so deduplication is one pass over a known set.
- **The digest drops Yokai's twelve-hour upload tiebreak.** Novel updates carry no upload date, so it would order half a mixed section by a clock the other half lacks.
- **Resuming a finished chapter opens the oldest unread, diverging from upstream**, which reopens the chapter just finished. It is what a continue row is for, and it makes every row the caught-up filter keeps resolve something.
- **The target rule reads no per-entry chapter filters.** Narrowing would let the gate keep a row that then resolves nothing; the cost is that an entry with its own filters can resume differently here than from its details page.
- **The chapter-state filters judge the read row's record, not its target.** Judging targets would be bulk resolution inside the render transform.
- **Marking read here does not push to trackers**, matching upstream's Updates tab.
- **Swipe is on updated-lane rows only.** History rows carry state a swipe could read, but giving History swipe is its own decision.
- **History takes selection and a download control on every row.** What a row can do follows its chapter, never the tab drawing it.
- **The two Mihon models stay live as the manga providers.** The takeover stops at orchestration; their upstream churn is low.
- **Upcoming is manga-only**, because novel sources rarely expose a release cadence.

## Upstream divergences

`// RK` islands in: `UpdatesViewModel` (feed category filter and custom-info overlay, download-state override drop; its chapter verbs live in `MangaRecentsChapterActions`), `UpdatesSettingsViewModel` (per-surface keys, Show read, Group by series), `UpdatesTab` and `HistoryTab` (thin hosts over `RecentsTabBody`), `HistoryViewModel` (surface, null seed, `emptyOnFailure`), `historyView.sq`, `HistoryRepository`, `HistoryRepositoryImpl`, `HistoryMapper`, `GetHistory`, `RemoveHistory` and `HistoryWithRelations` (the category predicate and chapter state), `updatesView.sq`, `HomeScreen` (tab set, badge, shortcut resolution), `UiPreferences` (`combinedRecentsTab`). Mihon's `UpdatesFilterDialog` and the old screens are deleted and listed in [off-path-manifest.md](../off-path-manifest.md). Recorded in [upstream-sync.md](../upstream-sync.md) "Deliberate divergences": the category-filter shape, `UpdatesViewModel` having no chapter verbs, and the history null seed.

## Extending

- **A new mode**: add a `RecentsMode` case (its name is on-disk), its `lanes` and `capabilities`, a policy in `RecentsPolicies.kt` with a case in `RecentsAssemblyTest`, and a slot in `RECENTS_MODE_ORDER`.
- **A new lane-dependent rule**: ask it of `mode.lanes`, and check what it does when a mode draws three lanes.
- **A new per-type affordance**: add a `RecentsTypeCapability` case every provider must answer, and hide the control where `chipCapabilities` lacks it.
- **A new chapter verb**: add it to `RecentsChapterActions` for both types, dispatch through `actingChapters`, and pin it in `RecentsChapterActionsConformanceTest`.
- **A new input to a target** (a table or preference a resolve reads): add it to the provider's `targetInputs`, or rows keep naming a stale chapter.
- **A new feed query**: carry the category predicate and pin it in `RecentsFilterQueriesTest` for both types.

## Tests

Kernel and policies: `RecentsAssemblyTest`, `RecentsTargetTest`, `RecentsTargetRowTest`, `RecentsRowGateTest`, `RecentsChapterFiltersTest`, `RecentsSweepOrderTest`, `RecentsListTopTest`. Engine: `RecentsEngineTest` over `FakeRecentsProvider`, `RecentsTargetInputsTest`, `RecentsFeedSurfaceTest`. Mapping and verbs: `RecentsMappingTest`, `RecentsAddedLaneTest`, `RecentsChapterActionsConformanceTest`, `RecentsRowDownloadTest`, `RecentsRowCopiesIndexTest`. Queries: `RecentsFilterQueriesTest`, `HistoryFeedConformanceTest`, `RecentsUnreadRepositoryTest`, `ChapterCopiesQueryTest`, `RecentsStartedConformanceTest`. Preferences and sheet: `RecentsCategoryFilterPrefsTest`, `UpdatesSettingsViewModelTest`, `TriStateToBooleanTest`. History failure: `HistoryFeedFailureConformanceTest`. Widget: `UnifiedWidgetUpdatesTest`, `UnifiedWidgetRefreshesTest`.

The adapters cannot be built in a unit test (their factories need live models), so the seam is pinned at the engine over fakes, and no Compose UI test covers the rows. Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`, or `:data:test` for the query tests.

## Related

- User doc: [recents.md](../../recents.md).
- Merged series, whose stitch the targets and copies read: [merged-series.md](merged-series.md).
