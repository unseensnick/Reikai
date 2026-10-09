# Migrate

## Purpose

Migration moves a library entry's state onto the same series on another source: read and bookmarked chapters, reading history, categories, trackers, notes and a custom cover, then swaps which of the two is in the library (Migrate) or keeps both (Copy). Manga and novels run one Reikai-owned flow (screens, step order, search and commit) over a per-type adapter, so a change to the flow reaches both types. Only the two migrate engines underneath stay per type.

## How it works

### Routes in

Every route lands on one of two shapes.

- **The flow.** Library multi-select (`LibraryTab`), the details toolbar (`MangaScreen`, `NovelScreen`) and Library update errors (`UpdateErrorsScreen`) push `EntryMigrationSourcePickScreen`. A selection holding both content types gets no Migrate action at all (`forSelection` returns null), since each type migrates over its own id space. The Browse Migrate tab (`ReikaiMigrateSourceTab`) pushes `EntryMigrationFavoritesScreen` for one source, whose Continue pushes the config screen directly: choosing a source already answered the merge question.
- **The duplicate prompt.** Adding an entry that duplicates a library one (details, the catalogue and global search through `EntryAddDialogs`, Recents, the feed, MD follows) offers Migrate, which raises `EntryMigrateFor` from that surface's own dialog state. It loads the two stored entries and shows `EntryMigrateDialog` straight away, with no search.

### The flow's screens

1. **Merge pick** (`EntryMigrationSourcePickScreen`). `mergeGroupMembers` expands each selected id to its merge group. When the group adds nothing to the selection, the screen replaces itself with the config screen; otherwise the user ticks which grouped sources to migrate, the selected ones ticked by default.
2. **Config** (`EntryMigrationConfigScreen`). The type's enabled sources split into Selected (drag to reorder, the search priority) and Available, with select all, select none and select pinned. The tuning sheet (`MigrationTuningSheet`) holds the five search options. Continue is hidden on an empty selection.
3. **Count fork.** One entry replaces config with `EntryMigrationSearchScreen`; more than one with `EntryMigrationListScreen`. Both types take the same branch.

Each step replaces the one before it, so back from the results leaves the whole flow. Every intermediate screen implements `MigrationFlowScreen`, and finishing or stopping unwinds with `popUntil { it !is MigrationFlowScreen }`.

### Source selection and search options

`enabledSources()` lists only sources the user could browse: manga filters by enabled languages and the disabled-source set, novels read `GetEnabledNovelSources`. Both sort by `migrationSourceOrder` (name, then language). `selectMigrationSources` picks what is searched: the saved order, else the pinned sources, else everything enabled, and the config screen seeds from the same function. `persist` writes the order back while keeping saved sources that are not on screen (disabled or uninstalled) in their old slots.

`MigrationTuning` is the five options: an extra query, deep search, prioritize by chapters, hide unmatched, hide without updates. The four toggles persist per type (manga on `SourcePreferences.migration*`, novels on `NovelPreferences.novelMigration*`). The extra query is never persisted: config hands it to the next screen as a constructor argument. The list and search screens read the tuning once, so nothing can change a search while it runs; changing an option means entering Migrate again. Both types offer every option, since `SmartNovelSearchEngine` extends Mihon's `BaseSmartSearchEngine`.

### The adapter seam

`MigrationFlowAdapter` is the only per-type code. `MigrationAdapters.forType` picks `MangaMigrationFlowAdapter` or `NovelMigrationFlowAdapter`, and refuses `ContentType.ALL`. Everything above it works on neutral data: `MigrationEntry` (the entry being migrated), `MigrationCandidate` (a target on one source) and `MigrationDataFlag`. Per-type state rides in sealed slots: `MigrationPayload` (`OfManga` / `OfNovel`) for the entry, `MigrationHandle` (`MangaCandidateHandle` / `NovelCandidateHandle`) for a candidate, `MigrationSourceIcon` for a source.

Fetch timing is part of the contract:

- `suggest` finds the best match on one source and enriches only that match.
- `candidates` returns one search page for the override picker, with no detail or chapter fetch.
- `peekCounts` is a display-only count: stored chapters when the candidate has them, else one source read stored nowhere. A paged novel source returns null, since its first page undercounts.
- `resolve` is the only call a commit depends on: it stores the target, fetches its chapters when it has none, and returns `ResolvedTarget.syncedNow` when that fetch stored chapters.

Where the types differ: a manga hit is stored at search time (`networkToLocalManga`), so a manga handle always holds a row and `suggest` fetches the hit's chapter list. A novel hit is only a plugin `NovelItem` until `resolve` stores it, and `inLibrary` comes from a lookup that deliberately leaves the handle's `stored` empty. Novel `prepare()` loads the plugin host before any source is read. Novel prioritize-by-chapters walks every chapter page of each source's hit, and any failed or empty page fails the whole count, leaving the hit unranked. Both adapters count through `toSourceChapters`, so a count matches what a sync would store.

### The batch list

`EntryMigrationListViewModel` holds one `MigratingEntryRow` per entry: a stable object with its own supervisor scope and three independent cells.

- `search`: `Queued`, `Searching`, `NoMatch`, `Failed` (every source threw), `Found(suggestion)`.
- `commit`: `Idle`, `Committing(replace)`, `Failed(replace, flags)`, which carries what a retry needs.
- `acceptance`: `Untouched`, `Accepted(candidate)`, `Declined` (a target handed back).

One driver searches rows one at a time in list order, choosing the next eligible row on each pass and claiming it with a compare-and-set, so a source sees one request from the batch at a time. Per row, prioritize-by-chapters asks every source at once and keeps the hit with the highest latest chapter (a zero-chapter hit never wins); otherwise the first source with a match wins. A found suggestion then gets its counts peeked in the background.

The hide toggles remove a row rather than filter it, checked when the search lands and again when the peek lands. Only an `Untouched` row with its picker closed goes; a failed search and an unknown count are never hidden.

`MigrationRowRules` is the single source of each row's controls (`actions` returns `RowActions`) and status line (`status` returns `RowStatus`); the screen renders what they allow and the model refuses the rest. The row offers accept and un-accept, accept all (untouched rows only), skip (removes the row, no restore), Search manually, Migrate now and Copy now, and Retry on a failed commit. The row's target line (`target`, accepted else suggested) opens the target, and the count line shows the latest chapter on each side with the target's `shortfall` in the error colour.

**Manual picks.** Search manually opens the inline override picker: one `MigrationCandidateStrip` per configured source, published as loading before any source is asked, filled by `fanOutCandidates`. A strip header pushes `EntryCatalogueScreen` in migration-pick mode (`openDeepPicker`), whose tap offers the pick to `MigrationPickHandoff` and pops; the list collects it on return with `takePendingPick`. Every pick, strip or browsed, goes through `resolvePick`, which refuses a target that resolves with no chapters. Refusals reach the user once through `PickOutcome` (`Unavailable`, `SameEntry`, `NoChapters`).

**Commit.** The bottom bar offers Copy (n) and Migrate (n); the verb is chosen there once. The confirm dialog carries the flag checkboxes (`MigrationFlagChecks`, only flags `applicableFlags` finds a use for) and its button stays disabled until that scan lands. Confirming persists the flags once, then the batch commits each committable row through `commitMigration` under a non-dismissable progress dialog with Cancel. A migrated row leaves the list; a failed one stays with Retry, which reruns with the verb and flags it failed under and never writes the preference. `finishIfNothingFailed` is the one finish gate: an emptied list finishes, otherwise the screen pops only after a batch migrated something and no row is still searching, committing or committable. On finishing, a toast reports the count and the flow unwinds. Back asks before leaving while rows remain, also during a commit, and Stop cancels the commit before unwinding.

### The single-entry search and the dialog

`EntryMigrationSearchScreen` searches the configured sources with the entry's title (plus the extra query) and shows one strip per source, re-searchable from the toolbar. Its has-results chip shares `SourcePreferences.globalSearchFilterState` with global search, but a source still loading or failed stays visible (`hasSomethingToSay`). Tapping a result runs `resolvePick` behind a progress bar, a later pick superseding one in flight, then opens `EntryMigrateDialog` on the resolved target.

`EntryMigrateDialog` is the single-item dialog both the search screen and the duplicate prompt use: flags, Copy and Migrate, and Show (the target from the search screen, the library copy from the duplicate prompt). After a migration from the search screen, `openDetailsAfterCommit` replaces the details page underneath when it showed the entry migrated away, else pushes the target.

### The engines

`commitMigration` is the one commit path: `resolve`, then `migrate` with `targetJustSynced` set from `syncedNow` so the engine skips a second identical fetch. The adapters call `MigrateMangaUseCase` and `MigrateNovelUseCase`, which do the same work:

- Refresh the target's chapters unless told it was just synced; a failed refresh fails the row.
- Under the chapter flag, carry read, bookmark and fetch date by recognized chapter number, mark everything at or below the highest read number read, and raise page progress, never lower it (manga `lastPageRead`, novels `lastTextProgress`). Each matched chapter's history merges onto the target's through `mergedHistory`. The carry is one checked write, so a half-carried state fails the row.
- Categories, notes and a custom cover under their flags; trackers always (enhanced manga trackers through `migrateTrack`); chapter and viewer flags always.
- Remove the old entry's downloads under that flag (manga only while its source is installed; novels await the delete). Nothing is re-downloaded onto the target.
- Last, in one transaction: the merge-group rewrite (`replaceInGroup` on Migrate, a merge on Copy of a grouped entry, see [merged-series.md](merged-series.md)) and the favorite swap, which is checked. The source tracker is then told of the move.

Both engines refuse a self-target and a missing target source, and rethrow every failure after logging it.

## Key files

- `app/src/main/java/reikai/presentation/migrate/flow/MigrationFlowAdapter.kt`: the seam, `MigrationEntry`, `MigrationCandidate`, `MigrationTuning`, `MigrationDataFlag`, `MigrationAdapters`.
- `app/src/main/java/reikai/presentation/migrate/flow/MangaMigrationFlowAdapter.kt` and `NovelMigrationFlowAdapter.kt`: the two adapters and their handles.
- `app/src/main/java/reikai/presentation/migrate/flow/MigrationAdapterRules.kt`: `isOwnListing`, `mergeGroupPickMembers`, `applicableFlagsOf`.
- `app/src/main/java/reikai/presentation/migrate/flow/MigrationSourceSearch.kt`: `selectMigrationSources`, `sourcesFor`, `fanOutCandidates`, `takePendingPick`, `resolvePick`, `StripResult`.
- `app/src/main/java/reikai/presentation/migrate/flow/MigratingEntryRow.kt`: the row cells and `MigrationRowRules`.
- `app/src/main/java/reikai/presentation/migrate/flow/EntryMigrationListViewModel.kt` and `EntryMigrationListScreen.kt`: the driver, commits, finish gate and the list UI.
- `app/src/main/java/reikai/presentation/migrate/flow/EntryMigrationSourcePickScreen.kt`, `EntryMigrationConfigScreen.kt`, `EntryMigrationFavoritesScreen.kt`, `EntryMigrationSearchScreen.kt`, `MigrationTuningSheet.kt`: the other flow steps.
- `app/src/main/java/reikai/presentation/migrate/flow/EntryMigrateDialog.kt` and `EntryMigrateHost.kt`: the single-item dialog and `EntryMigrateFor`.
- `app/src/main/java/reikai/presentation/migrate/flow/MigrationCommit.kt`: `commitMigration`.
- `app/src/main/java/reikai/presentation/migrate/flow/MigrationPickHandoff.kt` and `MigrationDeepPicker.kt`: `MigrationPickHandoff`, `PickOutcome`, `openDeepPicker`.
- `app/src/main/java/reikai/presentation/migrate/flow/MigrationCandidateUi.kt`: `openDetails`, `openDetailsAfterCommit`.
- `app/src/main/java/reikai/presentation/migrate/MigrationSourcePick.kt`: `MigrationSourcePickContent`, `PickMember`.
- `app/src/main/java/mihon/domain/migration/usecases/MigrateMangaUseCase.kt` and `app/src/main/java/reikai/domain/novel/interactor/MigrateNovelUseCase.kt`: the engines; `computeChapterMigration`, `computeHistoryMigration`.
- `app/src/main/java/mihon/feature/migration/list/search/BaseSmartSearchEngine.kt`, `SmartSourceSearchEngine.kt` and `app/src/main/java/reikai/novel/source/SmartNovelSearchEngine.kt`: title matching.
- `app/src/main/java/reikai/presentation/browse/migrate/ReikaiMigrateSourceTab.kt`: the Migrate tab's source list.

## Invariants and traps

- **Nothing above the seam branches on content type.** The one exception is opening a details page (`MigrationCandidateUi.kt`), an exhaustive `when` over the sealed payload and handle with no `else`.
- **Map flags by concept, never by bit.** The manga and novel flag enums carry the same five concepts on different bit layouts; manga maps by enum name, novels through `toNeutral` / `toNovelFlag`.
- **Flags are persisted once, at confirm, and travel as a value.** `migrate` writes no preference, so a batch cannot rewrite the pref per row and a retry cannot revert a newer choice.
- **`resolve` is idempotent, and `syncedNow` describes that call only.** A fetch that stored nothing is not a sync; claiming it makes the engine skip its own refresh and migrate onto an empty row.
- **A peek must never persist.** It runs on every found suggestion and every accept, so a peek that wrote chapter rows would store entries the user never chose.
- **A row's controls and its handlers read the same rule.** Add a control to `RowActions` and gate its handler on that flag; a hand-picked subset of the rules is how controls rendered on rows that refused them.
- **The batch claims a row, then re-reads its target and checks list membership.** It iterates a snapshot, so a row skipped while the batch worked ahead would otherwise still be migrated.
- **Every finish condition lives in `finishIfNothingFailed`.** Call it from any path that can settle the last row; never decide finishing at a call site.
- **A commit and a dialog are two cells.** `CommitActivity` is what runs, `Dialog` is what the user asked to see, and `State.visibleProgress` / `visibleDialog` decide what renders. Folding them let back clear the busy state under a running commit.
- **Use only `MigrationPickHandoff` to hand a pick back across screens.** It is a consume-once slot keyed by entry, cleared when the asking model is cleared; an event channel drops a pick made while the list is off screen.
- **The favorite swap goes last, inside the same transaction as the group rewrite.** Split, a cancelled batch could unfavorite the source and leave it feeding a group it no longer shows in.
- **There is no Compose UI test here.** After a bulk deletion on a flow screen, diff its composables against the previous commit and check every model API has a caller; a regex once removed the progress dialog with every test green.

## Decisions

- **One flow over both types, not parity patches.** Mihon's migration fixes only ever reached manga; owning the flow ends that. Upstream changes to the deleted flow files are triaged by hand through the off-path manifest.
- **The engines stay per type.** `MigrateMangaUseCase` is Mihon's and synced, `MigrateNovelUseCase` its Reikai twin, pinned by `MigrateEngineConformanceTest`. Merging them would re-type Mihon's manga stack.
- **Global search is not part of the flow.** The search screen runs on the adapter, so `SearchViewModel` stays upstream's.
- **The count fork stays.** One entry goes to results, many to the list, as upstream does; always using the list was declined for the friction it adds to the common single case.
- **A decided row leaves the list; only a failure stays.** This is upstream's model, and it removed a skipped flag and a migrated phase that every rule had to consult. Skip has no restore as a result.
- **Search options are settled on config and read once.** Changing them mid-list meant rebuilding rows under live work; the cost is re-entering Migrate to change one.
- **Prefer upstream's answer when a capability needs a new row axis.** Restorable skip and mid-list tuning both cost more cross-cutting state than they returned.
- **The favorites picker runs the shared selection kernel, long press included.** `EntryMigrationFavoritesViewModel` routes tap, select all, invert, clear and a long-press range through `EntrySelection`, as every other Reikai multi-select does; Mihon's picker has tap and clear only.
- **A row names its source by `sourceDisplayName`**, which names an uninstalled source by what it was last seen as (manga through `getOrStub`, novels through `NovelSourceManager.nameOf`).
- **The favorites picker keeps its selection on Continue, where Mihon clears it.** Backing out of config is the only way to adjust a large set, and the list never returns here.
- **Mihon's "select enabled sources" is not offered.** `enabledSources()` never lists a disabled source, so there is nothing for it to filter.
- **Novels never auto re-download after a migration.** It matches manga and avoids spending metered data silently.
- **A chapter read on both copies keeps the later read time and the longer duration, not the sum,** which is what a backup restore does too.

## Upstream divergences

The migrate flow files of Mihon (`mihon/feature/migration/` config, list and dialogs, `MigrateSearchScreen`, the `ui/browse/migration/search/` and `ui/browse/migration/manga/` screens and their models) are deleted and listed in [off-path-manifest.md](../off-path-manifest.md), with this flow named as their replacement. Patched in `// RK` islands: `MigrateMangaUseCase` (flags as a value, `skipTargetRefresh`, the self-target and missing-source checks, the history carry, the rising page carry, the checked chapter write, the transactional group rewrite and favorite swap, the source tracker, rethrow on failure) and `GlobalSearchToolbar` (no scroll behaviour, the busy bar). Recorded in [upstream-sync.md](../upstream-sync.md) "Deliberate divergences" (`MigrateMangaUseCase`, `GlobalSearchToolbar`). `MigrationFlag`, the smart-search engines and `SearchViewModel` sync normally.

## Extending

- **A new search option**: add it to `MigrationTuning`, both adapters' `readTuning` / `persistTuning` (a new pref per type), `MigrationTuningSheet`, and whichever of `suggest` or `MigrationRowRules.shouldHide` reads it.
- **A new data flag**: add the concept to `MigrationDataFlag`, map it in both adapters, label it in `MigrationFlagChecks`, gate it in `applicableFlagsOf`, and carry it in both engines with a `MigrateEngineConformanceTest` case.
- **A new row control**: add a flag to `RowActions`, derive it in `MigrationRowRules.actions`, render it from that flag and refuse it in the model on the same flag.
- **A new route in**: push `EntryMigrationSourcePickScreen` (or `forSelection` for a multi-select), or raise `EntryMigrateFor` when both entries are already known.
- **A new rule both adapters must follow**: put it in `MigrationAdapterRules.kt` and add a `MigrationAdapterConformanceTest` case.

## Tests

Flow logic: `MigrationRowRulesTest`, `MigratingEntryRowTest`, `EntryMigrationListViewModelTest` (driver, claim, finish gate, over `FakeMigrationFlowAdapter`), `EntryMigrationConfigViewModelTest`, `EntryMigrationFavoritesViewModelTest`, `EntryMigrationSearchViewModelTest`, `MigrationSourceSearchTest`, `SelectMigrationSourcesTest`, `MigrationRouteTest`. Adapters: `MigrationAdapterConformanceTest` (both real adapters through the same cases), `MigrationAdapterRulesTest`, `NovelMigrationFlowAdapterTest`. Engines: `MigrateEngineConformanceTest` (every shared rule over both use cases), `MigrateNovelUseCaseTest` (novel-only behaviour), `MergedHistoryTest`. Matching: `SmartNovelSearchEngineTest`. The Migrate tab: `CompareMigrateRowsTest`, `MigrateNovelSourcesTest`, `MigrateNovelSourcesViewModelTest`.

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`, or `:domain:test` for `MergedHistoryTest`.

## Related

- User doc: [source-migration.md](../../guides/source-migration.md).
- Merge-group handling during a migration: [merged-series.md](merged-series.md).
