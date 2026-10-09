# Browse and sources

## Purpose

Browse is where a reader finds new series: the Sources, Extensions and Migrate lists, one source's catalogue, global search, the opt-in Feed tab and saved searches, and the long-press add-to-library flow every one of those shares. Manga and novels run through one Reikai-owned layer, so a Browse change is written once and reaches both types. Underneath, every novel feature reaches a source through one seam, `NovelSource`, over three source kinds: LNReader plugins, tachiyomi-format novel APKs and IReader APKs, the APKs arriving through Mihon's one extension pipeline. The plugin engine itself is [ln-plugin-host.md](ln-plugin-host.md).

## How it works

### Two shapes

Browse holds two kinds of surface, and they take opposite shapes.

- **The four multi-source lists** (Sources, Extensions, Migrate, global search) are All-first: one engine assembles rows from a manga provider and a novel provider into one list, and the sticky `All / Manga / Novels` chip (`ReikaiSourcePreferences.browseContentType`, held by `ReikaiBrowseViewModel`) is a predicate over it. Loading, emptiness, sectioning and search are derived once, over the providers the chip shows.
- **The per-source catalogue** is not All-first. A source is manga or novel before the screen opens, so `EntryCatalogueScreen` takes the details surface's shape: a neutral state and behaviour contract (`EntryBrowseBehavior`, `EntryBrowseScreenState`) over two adapters.

Every source is keyed by the sealed `SourceKey` (`Manga(Long)` / `Novel(String)`), the browse analogue of `EntryId`. Its `serialize` form (`manga:123`, `novel:novelbin`) is what the last-used preference and the saved-search tables store.

### The multi-source lists

`BrowseTab` (Mihon's, patched) builds the tabs from Reikai wrappers: `reikaiSourcesTab`, the optional `reikaiFeedTab`, `reikaiExtensionsTab` and `reikaiMigrateSourceTab`. The Browse search bar's query lives on `ReikaiBrowseViewModel`, since it sits above the tabs and filters one list serving both types. Opening Browse also runs `LnPluginUpdateChecker.runIfStale`, so the Extensions badge (manga plus novel updates, `ExtensionUpdateCounts`) is fresh.

Each engine (`SourcesEngine`, `ExtensionsEngine`, `MigrateSourcesEngine`) wraps live per-type ViewModels as providers: Mihon's `SourcesViewModel`, `ExtensionsViewModel` and `MigrateSourceViewModel` for manga, and `NovelSourcesViewModel`, `LnPluginManagerViewModel` (plugins) plus the novel APK listings, and `MigrateNovelSourcesViewModel` for novels. A provider answers only about its own rows and carries the source object opaquely; only the leaf that renders a type unwraps it.

- **Load state.** `ProviderLoad.of` reports loading only while every shown provider is still null, and `hasPending` while any is. A slow plugin repo never holds back manga rows that are ready, and a half still on its way never reads as "nothing found" (`ProviderList.isEmpty`).
- **Sources.** `sectionSources` orders Last used, Pinned, then one section per language, with manga and novel sources sharing a language section and a content-type badge drawn only under All. The last-used source is one preference for both types (`ReikaiSourcePreferences.lastUsedSource`, an app-state key, skipped while incognito); the manga provider gets its duplicate Last used row from `GetEnabledSources` and the novel provider adds the same copy, so the source shows under Last used and again in its language. Rows carry the extension-name suffix (only when the source's name differs from its extension's, which a plugin never does), the content-warning badge, a language flag, the Latest button (hidden by `hideSourceLatestButton`) and a long-press sheet (`EntrySourceOptionsDialog`) for pin, disable and incognito. Incognito is keyed by `GetIncognitoState.incognitoKey(SourceKey)`, so a manga source switches with its whole extension, a novel app's sources with their app, and a plugin by itself.
- **Language order.** `compareBrowseLanguages` is the one order for every Browse list: multi-language first, then each language by its own name for itself, the local source's `other` and sources declaring none last. A plugin's language arrives as an ISO code (`NovelSource.lang`), so nothing on this surface normalises it again.
- **Extensions.** One Updates section with one Update all spanning both types, then Installed and Available, plus pull-to-refresh, the install-permission banner, the loading and empty states and a Back that clears the search. The unified list hosts Mihon's `ExtensionTrustDialog` and `ExtensionUninstallConfirmation` itself, because it re-wires the manga row's clicks over a shared `ExtensionItem`. A plugin row answers a tap and a long press as the matching manga row does; a plugin that failed to load and has an update rides under Updates as a `NovelPluginUpdateRow` carrying its failure, so its gestures open the not-loaded dialog.
- **Migrate.** `compareMigrateRows` sorts both types under one header, a gone source first in either mode (upstream's rule). A novel source that is not installed reads "Not installed" like a manga stub.
- **The sources filter.** `EntrySourcesFilterScreen` is one screen with a Manga / Novels chip, drawn by one `SourceFilterList` over each half's pure `toSections`. Storage stays per type: a manga language is off unless listed (`SourcePreferences.enabledLanguages`, Mihon's), a novel language on unless listed (`disabledNovelLanguages`).

### Global search

`EntryGlobalSearchScreen` is one screen over `GlobalSearchEngine`, which owns the query, the content-type tabs (a `HeaderTabRow` above the Pinned / All source filter, since two chip rows would put an All beside an All), the has-results toggle (Mihon's `globalSearchFilterState`, one preference for both halves), one comparator and the same-query guard. `MangaGlobalSearchProvider` and `NovelGlobalSearchProvider` answer only which sources to search and how to run one. Re-running the same query, filter and tab is a no-op; widening Pinned to All for the same query keeps the rows that already succeeded.

A search's scope is an assisted value: one opened from an entry is scoped to that entry's type and never writes the Browse chip back, and only Browse's own search opens on the chip (`searchIntentScreen` routes deep links and shares).

Rows fill through `fillEntryRows` in `EntryRowFill.kt`: one `Semaphore` of `SOURCE_SEARCH_CONCURRENCY` (5) per content type, each result written inside one state update, and a superseded pass dropped rather than written over its replacement. The feed calls the same kernel; migration's candidate search calls the `fanOutPerSource` below it.

### The catalogue

`EntryCatalogueScreen` owns the toolbar, the listing chips, `EntryBrowseCatalogue` (the body, with the loading, empty and fetch-error states and the three grid layouts), the selection bar, saved-search chips and every dialog the neutral state can describe. Each per-type branch supplies only what nothing neutral can hold: the filter sheet, the source settings and the adapter. `MangaBrowseAdapter` wraps Mihon's `BrowseSourceViewModel`; `NovelBrowseAdapter` wraps `NovelBrowseViewModel`. `MangaDexFollowsScreen` renders the same body with its own chrome.

- **Paging.** Both types page through Paging 3 (`BaseSourcePagingSource` in Mihon's `SourcePagingSource.kt`, `BaseNovelPagingSource` in `NovelPagingSource.kt`), and both run each page through `CataloguePaging`: entries deduped by url or path, upstream's refresh key, an empty first load reported as no results and an empty later page ending the list quietly. A source that reports its own end (`CatalogueEnd.Reported`: every manga source and both novel app formats) is trusted; an LNReader plugin reports none (`CatalogueEnd.Inferred`), so its list ends at a page bringing nothing new.
- **Hide in library.** Both read Mihon's `hideInLibraryItems` once at construction and filter the paging stream; a novel is in the library when `FavoritedNovels.contains(sourceId, url)` says so.
- **Listing and filters.** The Latest chip shows only when the source `supportsLatest`. The filter sheet follows the source's filter kind, never its content type: a `FilterList` renders Mihon's `SourceFilterDialog`, an LNReader schema renders `NovelSourceFilterSheet`. The Filter chip means "a search-shaped listing is showing", upstream's meaning: manga lights it for any `Listing.Search`, novels when a query is committed or Apply ran since the last listing switch (`filterChipActive` on each adapter). A manga row tap must name Popular out loud (`mangaListingQuery`), since `Listing.valueOf(null)` is a Search.
- **Display.** Display mode travels in each model's state through `trackDisplayMode` (manga on Mihon's catalogue key, novels on `novelBrowseDisplayMode`); column counts follow the library's through `trackBrowseColumns`. `EntryBrowseRowStyle.Gallery` is the adult-source layout, manga only.
- **Search and back.** Back while a query is showing clears it (`navigateUp`); the X beside the field only empties the text, as upstream's does. Returning from the WebView refreshes the listing, so clearing a challenge there does not leave the error up.
- **Migration pick.** Opened with `migrateForId`, the screen fills `MigrationPickCapability`: a tap reports the pick to `MigrationPickHandoff` and pops, for both types.

### Result rows

A catalogue, global-search or feed result is one `EntryBrowseRow`, built by the kernels in `EntryBrowseRows.kt`: `mangaBrowseRow` (the catalogue pager), `liveMangaRow` (a search or feed manga, following its stored row only while a cell collects it), and `novelBrowseRow`. Selection keys are `mangaRowKey` and `novelRowKey`, and which details page a result opens is `detailsScreen`. A row's neutral content is a derived view (`mapState`) that launches nothing, so the only collector is the cell drawing it. An in-library novel result draws its library row's cover through `novelResultCover`. Failures read through `sourceFailureMessage`: offline, an error the formatter has no wording for reads "No Internet connection", since a source can swallow the network error and fail later on an empty answer.

Cells take their haptics from `EntryCellHaptics`: silent while choosing, the platform's own while browsing.

### Adding to the library

One sequence for both types and every path, in `AddOutcome.kt`: resolve the categories as a pure read, then favorite, then file (`addEntry`). A picker defers both writes to its confirm (`finishAdd`), so dismissing it adds nothing, and a failed favorite write returns `Failed` with nothing filed. The default-category rule is `resolveDefaultCategoryIds`; a group join files into the group's categories first (`groupOrDefaultCategoryIds`), and no write files the system category (`withoutSystemCategory`).

A long press runs `decideAdd`: in the library offers removal, a possible duplicate asks first, anything else adds. Both adders (`MangaLibraryAdder`, `NovelLibraryAdder`) read the stored row when the press lands (`isInLibrary`), not what the list drew. `duplicatePrompt` builds the one `DuplicatePrompt` (rows, source labels, group ids, the grouping offer from `suggestGroupingOnAdd`), and `findDuplicates` on each adder is its only caller. `EntryDuplicateDialog` renders it for every surface, mapping each type's row to `EntryDuplicateCardUi`.

`EntryAddFlow` holds the raised question for one type (`MangaAddFlow`, `NovelAddFlow`) on the hosting model's scope, so an add left pending while a duplicate is opened is still there on return. Every host (the catalogue, global search, the feed, MangaDex follows) renders it with `EntryAddDialogs`. Add-time grouping favorites and merges in one transaction (`joinGroup`), only after a picker it raises is confirmed. Bulk adds go through `EntryBulkFavoriteViewModel` (facades `BulkFavoriteViewModel`, `NovelBulkFavoriteViewModel`), which asks each facade `isInLibrary` when the add runs; global search and the feed share one `MixedBulkSelection`, and a batch spanning both types prompts for categories once per type. Manga adds outside the details page go through `MangaLibraryAdder.favoriteFromBrowse`; adds with no screen to ask on (a gallery batch, a shared link, the MangaDex follows sync) file into the default category through `addWithoutAsking`. Recents adds through `RecentsEngine.addToLibrary` over the same adders.

### Feed and saved searches

A saved search is a named query plus filters for one source, in `saved_search`, keyed by a serialized `SourceKey` so plugin slugs fit. The filter payload is one opaque column read by a typed slot per type (`SavedSearchFilters`): manga stores `FilterSerializer` output, novels store an LNReader value map or, for a `FilterList` source, the manga encoding. Every apply goes through `restore`, onto filters the source builds for that call, so a value the search does not carry reads today's default. Manga filters are matched by kind and name, repeats consumed in order, and a saved `Select` or `Sort` is re-resolved by option text (`afterDeserialize`); a value with no match keeps the source's default.

`SavedSearchViewModel` holds a catalogue's chips for both types. A catalogue opened for a feed row's search applies it from a `LaunchedEffect` once the state reads Loaded (`savedSearchToOpen`), guarded by a `rememberSaveable` flag so neither a config change nor saving a new search re-applies it. The manga adapter also sets the filter sheet's list, so the sheet shows what was applied.

The Feed tab (`ReikaiFeedTab` over `FeedViewModel`) is opt-in (`showFeedTab`, `feedTabInFront`, `hideInLibraryFeedItems`). Each row is one source's page one: its saved search when it has one, else Latest, else Popular (`FeedProvider.load`). Rows live in `feed_saved_search` (`global`, `feed_order`, cascading off the saved search), capped at `MAX_FEED_ROWS` (20) and deduplicated inside the repository's insert transaction. The tab follows each type's registry through `FeedProvider.sourceChanges`, so a source installed or removed later rebuilds the rows; a row whose source is missing stays as `EntrySearchState.Unavailable`, still removable. The add-source picker offers exactly what the Sources tab lists. Reorder is a mode inside the tab committing when a drag settles; selection swaps in a toolbar through `TabContent.actionModeToolbar`.

Backups carry saved searches and feed rows (fields 715, 716) by value, not by id, with `feed_order` restored as a sort key (`FeedRestorer`); a restore honours the cap and is a no-op when repeated.

### Novel sources: one seam, three kinds

`NovelSource` is the only door: browse, global search, details and chapter refresh, the reader's text load, downloads, the library update job and migration reach a source through it and `NovelSourceManager`. Each kind has one adapter: `LnPluginSource` (a plugin on the QuickJS host), `TachiyomiNovelSource` (one catalogue of a tachiyomi-format novel APK) and `IReaderNovelSource` (one IReader source). Nothing above the seam checks the kind; what differs is a typed capability the source answers:

- **Filters** (`NovelFilters`): an LNReader schema, rendered by `NovelSourceFilterSheet`, or a Mihon `FilterList`, rendered by Mihon's `SourceFilterDialog`. IReader filters are translated both ways into a `FilterList` (its `Check` is both checkbox and tri-state, its `Group` mixes types, its query travels as a `Title` filter). `applyToSearch` says where filters apply: a plugin's search takes no options, so its filters narrow listings and drop the query; a `FilterList` travels with the search.
- **Settings**: an LNReader schema (`NovelSourceSettingsSheet` over `NovelSourceSettingsModel`, reading the plugin's storage off the main thread and reloading on every open), a `ConfigurableSource` preference screen hosted by `SourcePreferencesScreen` through a resolver keyed on the novel source's text id, or none (IReader).
- **Images** (`NovelImageRequests`): each source's client and headers for covers, chapter pictures, downloads and the WebView reader, answered without running a plugin (a plugin's site and `imageRequestInit` headers come from its last load). A chapter picture gets the source's client and headers only on the source's own host or a subdomain; any other host gets the app's client with the source's user agent and its site as Referer. Covers go through Mihon's `MangaCoverFetcher`, handed a `CoverRequestClient` by `NovelCoverFactory`, so both types share one cover cache. An APK source's icon is its package icon, addressed `reikai-extension-icon://<package>`; an icon with no visible pixel borrows its store's, else one listed for the same site (`NovelIconHints`).
- **Listings**: `supportsLatest`; an IReader source maps its first two listings onto Popular and Latest.
- **Optional capabilities**, null where unsupported: `pageFetch` (IReader's WebView fetch commands, saving a page the user loaded in the in-app browser through `NovelChapterSaver`), `links` (shared-link resolution), `chapterStylesheet` (a plugin's registry `customCSS`, WebView reading mode only), `minimumRequestDelayMs` (a `RateLimited` app's floor on download pacing).

`NovelSourceManager.get` and `getAll` await the first plugin load once and never retry; a failed plugin is retried only where a novel screen opens and where an update or download run starts, which call `ensureLoaded` directly. A screen that shows whether a source is installed reads `loadedSources()`. Every call into an app's catalogue runs through `appSourceCall`, on IO, rethrowing a `LinkageError` from an outdated app as an `Exception` so it fails the call instead of crashing.

**Identity.** Novel sources are text ids: a plugin's own id, or `tachiyomi:<id>` / `ireader:<id>` from the running source object. The two ecosystems share 26 numeric ids, and seven more collide with manga sources, so a bare number is never an id. A download folder is the id through `DiskUtil.buildValidFilename`.

### The extension pipeline

`ExtensionLoader` (Mihon's, `// RK`) recognises three manifest features: `tachiyomi.extension` (manga, library 1.4 or 1.6), `tachiyomi.novelextension` (novel, same gate, `tachiyomix.*` keys shared) and `ireader` (library major 2, classes from `source.class`, built from `(Dependencies)`). `Extension.Kind.fromFeatures` reads the kind; an APK declaring both manga and novel loads as manga. `ExtensionManager` files novel kinds into maps of their own as they arrive, so nothing reading the manga maps (source registration, stubs, lists, badges, backups) sees a novel APK; a reader that should list both reads the novel maps beside them. A store's available entry takes its kind per entry (`Extension.Kind.fromIndex`: field 8000, or the novel package namespace in an old-format index), so one store may mix kinds. IReader's index is recognised by its entries and kept as a keyless legacy store at its own `index.min.json` address.

Trust is Mihon's: a store's signing key covers its extensions, a keyless store's are trusted per version, and IReader's own repo carries the key its APKs are signed with. A keyless store's listing counts for an installed APK only when no added store's key signs it. Update badges come from `kindListing`, judged per APK against the stores it can come from (`canComeFrom`); while any of those failed or is new since the fetch, the APK keeps its statuses, and once all answered an empty listing clears its update flag. `ExtensionManager.readStores` reads the stores with their keys at each scan.

`RepositoriesScreen` manages manga stores and LN plugin repos in one card list, with one Add that detects the repository's kind; `LnRepoRegistries` caches the plugin registries.

**The tachiyomi-format adapter.** Each APK carries its own `NovelSource` interface copy the host cannot reference, so source-api's `Source` declares `fetchPageText(page)` with a throwing default and the extension's method answers across class loaders. Popular and search go to `getPopularManga` / `getSearchManga`, details and chapters to `getMangaUpdate`, text to `getPageList` then `fetchPageText` per page, joined; page addresses are the extension's. The newest-first chapter list is reversed into reading order. A listing page reports its own end (`NovelItemsPage.end`). A page named only as a picture (the ReadWN theme) is fetched from the picture address.

**The IReader runtime.** IReader's API is the pinned artifact `io.github.ireaderorg:source-api-android:1.5.1` (only `ireader.*`), with Ktor, IReader's Ksoup and Kermit, and Koin excluded. `IReaderHostServices` builds each source's `Dependencies`: Ktor over Reikai's OkHttp client (so FlareSolverr, the cookie jar and the cache apply; only Gson content negotiation is kept; the app's user agent set per request unless the extension named one), IReader's own WebView `BrowserEngine` built on first use, and a preference store under `ireader_storage::<package>::`. `MadaraChapterEndpoint`, the first interceptor on that client, answers a Madara chapter request the site refuses from the novel's own address. A chapter's `Text` and `ImageUrl` pages are assembled into chapter HTML.

### Source-side tracking, auto-bind and shared links

- **`SourceTrackerKernel`** runs an extension's own `SourceTracker` for both types: chapter events per entry held three seconds and merged, adds and removes sent only if the library still agrees after the wait, category names without the uncategorized one, a toast per failed call. Reads come from `SetReadStatus`, `SetNovelReadStatus`, the manga reader and the notification action; migration notifies behind `source_tracker_run_on_migration`; restore never does. `RateLimited` paces novel downloads only (`floorFor`).
- **Auto-bind** (`AutoBindTracker`, `bindOnAdd`, `offerTrackers` in `AutoBind.kt`) binds every logged-in tracker accepting the entry's source after the favorite write lands, for both types (`AddTracks.bindEnhancedTrackers` through `EnhancedAutoBind` for manga, `BindNovelTrackers` for novels, started by `AutoBindOnAdd`).
- **NovelUpdates owns its site.** Reikai's tracker does release marking, opt-in unread push (`PushNovelUnread`, `progressAfterUnread`) and never-move-backwards; `OwnedSites` keeps the extension's tracking hooks from ever being called, and `SourcePreferencesScreen` removes the six settings the tracker took over.
- **Shared links** (`SharedLink`, behind `ResolveMangaLink` and `ResolveNovelLink`) try a source's own reading of the link, then a stored row, then a checked guess from the address, manga before novel within each tier. A guess opens only when one source matches, the page parses to a named entry, and the parent address is not the same entry.

## Key files

- `domain/src/main/java/reikai/domain/source/SourceKey.kt`: `SourceKey`, `serialize`, `parse`.
- `app/src/main/java/reikai/presentation/browse/ReikaiBrowseViewModel.kt`: the chip, the Browse query, update counts.
- `app/src/main/java/reikai/presentation/browse/source/SourcesEngine.kt`, `BrowseSourceRow.kt` (`sectionSources`), `SourcesProvider.kt`, `EntrySourcesFilterScreen.kt`.
- `app/src/main/java/reikai/presentation/browse/extension/ExtensionsEngine.kt`, `ExtensionsProvider.kt` (`novelExtensionRows`), `ReikaiExtensionsTab.kt`, `LnPluginManagerViewModel.kt`.
- `app/src/main/java/reikai/presentation/browse/migrate/MigrateSourcesEngine.kt`, `BrowseMigrateRow.kt` (`compareMigrateRows`).
- `app/src/main/java/reikai/presentation/browse/ProviderLoad.kt` and `BrowseLanguageOrder.kt` (`compareBrowseLanguages`).
- `app/src/main/java/reikai/presentation/browse/globalsearch/GlobalSearchEngine.kt`, `GlobalSearchProvider.kt`, `EntryGlobalSearchScreen.kt`, `SearchIntentScreen.kt`.
- `app/src/main/java/reikai/presentation/browse/EntryRowFill.kt`: `fillEntryRows`, `fanOutPerSource`.
- `app/src/main/java/reikai/presentation/browse/catalogue/EntryCatalogueScreen.kt`: `mangaListingQuery`, `savedSearchToOpen`; `EntryBrowseBehavior.kt`, `EntryBrowseScreenState.kt`, `MangaBrowseAdapter.kt`, `NovelBrowseAdapter.kt` (`filterChipActive`), `EntryBrowseCatalogue.kt`, `BrowseDisplayMode.kt`, `BrowseColumns.kt`.
- `domain/src/main/java/reikai/domain/source/CataloguePaging.kt`: `CataloguePaging`, `CatalogueEnd`; `app/src/main/java/reikai/novel/source/NovelPagingSource.kt`.
- `app/src/main/java/reikai/presentation/novel/browse/NovelBrowseViewModel.kt`, `NovelSourceFilterSheet.kt`, `NovelSourceSettingsSheet.kt`.
- `app/src/main/java/reikai/presentation/browse/EntryBrowseRows.kt`: `mangaBrowseRow`, `liveMangaRow`, `novelBrowseRow`, `detailsScreen`; `catalogue/EntryBrowseRow.kt` (`mapState`).
- `app/src/main/java/reikai/presentation/browse/SourceFailureMessage.kt` and `EntryGestures.kt` (`EntryCellHaptics`).
- `domain/src/main/java/reikai/domain/novel/FavoritedNovels.kt` and `domain/src/main/java/reikai/domain/novel/model/NovelCover.kt` (`novelResultCover`).
- `app/src/main/java/reikai/presentation/browse/AddOutcome.kt` (`addEntry`, `finishAdd`), `AddDecision.kt` (`decideAdd`), `DuplicatePrompt.kt`, `EntryAddFlow.kt`, `MangaAddFlow.kt`, `EntryAddDialogs.kt`, `MangaLibraryAdder.kt`, `EntryBulkFavoriteViewModel.kt`, `BulkSelectionSupport.kt` (`MixedBulkSelection`).
- `app/src/main/java/reikai/presentation/novel/browse/NovelLibraryAdder.kt` and `NovelAddFlow.kt`.
- `app/src/main/java/reikai/domain/category/DefaultCategoryResolution.kt`: `resolveDefaultCategoryIds`, `groupOrDefaultCategoryIds`, `withoutSystemCategory`.
- `app/src/main/java/reikai/presentation/browse/components/EntryDuplicateDialog.kt` and `EntryRemoveDialog.kt`.
- `data/src/main/sqldelight/tachiyomi/data/saved_search.sq` and `feed_saved_search.sq`; `data/src/main/java/reikai/data/source/FeedSavedSearchRepositoryImpl.kt`; `domain/src/main/java/reikai/domain/source/FeedSavedSearchRepository.kt` (`MAX_FEED_ROWS`).
- `app/src/main/java/reikai/domain/source/filter/SavedSearchFilters.kt`, `FilterSerializer.kt`, `FilterSerializerModels.kt`; `app/src/main/java/reikai/presentation/novel/browse/NovelSavedSearchRun.kt`.
- `app/src/main/java/reikai/presentation/browse/catalogue/SavedSearchViewModel.kt` and `SavedSearchDialogs.kt`.
- `app/src/main/java/reikai/presentation/browse/feed/FeedViewModel.kt`, `FeedProvider.kt`, `ReikaiFeedTab.kt`, `FeedOrderList.kt`.
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/FeedBackupCreator.kt` and `restore/restorers/FeedRestorer.kt`.
- `app/src/main/java/reikai/novel/source/NovelSource.kt` (`NovelSource`, `appSourceCall`), `NovelSourceCapabilities.kt` (`NovelFilters`), `NovelSourceManager.kt` (`loadedSources`).
- `app/src/main/java/reikai/novel/source/LnPluginSource.kt`, `TachiyomiNovelSource.kt`, `ireader/IReaderNovelSource.kt`, `ireader/IReaderHostServices.kt`, `ireader/MadaraChapterEndpoint.kt`.
- `app/src/main/java/reikai/novel/network/NovelImageRequests.kt` and `app/src/main/java/reikai/domain/source/NovelIconHints.kt`.
- `source-api/src/main/kotlin/eu/kanade/tachiyomi/source/Source.kt` (`fetchPageText`), `SourceTracker.kt`, `RateLimited.kt`.
- `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`, `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt` (`readStores`), `domain/src/main/java/eu/kanade/tachiyomi/extension/model/Extension.kt` (`Kind`, `fromFeatures`, `fromIndex`, `canComeFrom`).
- `app/src/main/java/reikai/domain/extension/KindListing.kt` (`kindListing`) and `app/src/main/java/reikai/presentation/browse/repos/RepositoriesScreen.kt`.
- `app/src/main/java/reikai/domain/track/source/SourceTrackerKernel.kt`, `reikai/domain/track/autobind/AutoBind.kt` (`bindOnAdd`, `offerTrackers`), `BindNovelTrackers.kt`, `AutoBindOnAdd.kt`, `reikai/domain/track/site/OwnedSites.kt`.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/novelupdates/NovelUpdatesReleases.kt` (`progressAfterUnread`) and `app/src/main/java/reikai/domain/novel/track/PushNovelUnread.kt`.
- `app/src/main/java/reikai/domain/source/SharedLink.kt`, `ResolveNovelLink.kt`, `ResolveMangaLink.kt`.

## Invariants and traps

- **A verb that reads its payload from the live dialog finds null.** `EntryDuplicateDialog`, `EntryRemoveDialog` and Mihon's `ChangeCategoryDialog` call `onDismissRequest()` before the action, so `EntryAddFlow` keeps what it raised in `raised` and `dismiss` only hides the question. Read a verb's subject from the flow, never from dialog state.
- **Route a search or feed row by its `SourceKey`, never by casting its payload.** The manga provider stores the extension-facing `Source`, so the obvious cast compiles and throws on tap.
- **Derive per-row state with `mapState`, never `stateIn` per row.** A per-row `stateIn` downstream of `cachedIn` starts eagerly and lives as long as the ViewModel, so a browsing session accumulates one collector per result ever loaded.
- **Display mode must ride the model's state.** The catalogue renders from a flow that cannot observe a Compose value, so a mode kept only in composition writes a preference nothing re-reads; both models call `trackDisplayMode`.
- **A capability on one side of a per-type leaf is invisible to the neutral contract.** A Latest button, a version line, a long press and select-all each went missing on one type because nothing forced the other side to answer. A verb routed through an adapter needs a device pass, not only a reading.
- **The catalogue's top bar consumes pointer input** (`pointerInput(Unit) {}`), or a drag starting on the toolbar or chips scrolls the grid behind it.
- **An LNReader plugin cannot take a query and filters together.** Its search takes no options, so `NovelSavedSearchRun` saves and runs a query or filters, never both; a filters-only search pages Popular. The catalogue and the feed both run that rule.
- **A saved search waits for a loaded state before applying.** A novel model whose plugin is still resolving drops the search, and the once-only flag would never let it retry.
- **Two filters of the same kind and name stay positional among themselves.** Matching by kind and name cannot separate them.
- **Never check a source's kind above `NovelSource`.** A difference between kinds is a typed capability the source answers, hidden where it is null.
- **Never key a novel source by a bare number.** Ids collide across the two APK ecosystems and with manga sources; a tsundoku-format extension's own settings file is `source_<number>`, which a manga extension for the same site shares, and no host choice can prevent that.
- **Never add a parallel extension loader.** Store, install, trust, signature and library-version checks exist once, in Mihon's pipeline, as `// RK` islands; novel kinds split off in `ExtensionManager`, not in each reader.
- **APK chapter text is untrusted input**, sanitized like plugin output.
- **One scan or load pass at a time, on each side.** `ExtensionManager.loadMutex` serializes the APK scans and `LnPluginInstaller.loadMutex` the plugin loads, because each pass assigns its maps wholesale from a store list read when it started; a slow pass landing late would undo a re-trust or reload. The pair stands in for a conformance test.
- **Keep IReader's API at 1.5.1** until an upgrade is checked: its `main` branch adds copies of `eu.kanade.tachiyomi` classes that clash with Reikai's source-api.
- **R8 must keep what extensions call by name**: `ireader`, `io.ktor`, `kotlinx.io`, `com.fleeksoft.ksoup`, Kermit and `kotlinx.datetime` in `app/proguard-rules.pro`, and `SourceTracker`, `RateLimited` with their `DefaultImpls` and `source.online.**` in `source-api/consumer-proguard.pro`. Verify on a minified build.
- **A taken-over NovelUpdates setting is hidden by key.** If an extension update renames one, it reappears doing nothing until the table is updated.

## Decisions

- **The lists are All-first, the catalogue is details-shaped.** The chips are predicates only while language, not content type, is the outer grouping; a catalogue's type is fixed before it opens. Void if a source could ever serve both types.
- **Novels page through Paging 3.** It is upstream's own mechanism (`SourcePagingSource` is a typealias over androidx `PagingSource`), so adopting it reimplements nothing of Mihon's spine.
- **The filter dispatch stays split, by source kind.** A `FilterList` and an LNReader JSON schema share nothing; a novel source with a `FilterList` uses Mihon's dialog. The kind is the typed `NovelFilters` capability.
- **An empty later page ends the list quietly**, where Mihon throws `NoResultsException` and shows Retry over the results. Ending every list at a page with nothing new was declined: it would cut a manga Latest listing short during bulk uploads. Recorded in [upstream-sync.md](../upstream-sync.md) "Deliberate divergences".
- **The Filter chip keeps upstream's meaning.** "Filters differ from defaults" needs the defaults snapshotted at open, and `getFilterList()` builds a fresh list per call that some sources build from preferences. Filtering manga's Latest stays impossible: `getLatestUpdates(page)` takes no filters.
- **Language storage stays per type.** Mihon owns manga's enabled-language allowlist; a plugin is installed deliberately, so a novel language is on unless denied, and a new language needs no migration.
- **Global search is one screen with tabs, not two sharing an engine**, and a mixed batch prompts per type because novel categories are a different partition of the same table.
- **`MangaDexFollowsScreen` keeps its own chrome.** What it does not share is a toolbar with no chips and no search; folding it in would add flags whose only job is to blank chrome.
- **The NSFW-only list filter was dropped.** It has no both-types form, and the content-warning badge already marks adult sources.
- **The feed reuses the global-search fill kernel** rather than copying Komikku's fan-out, since two Reikai-owned copies get no engine-split exemption.
- **The feed is opt-in, with no per-source feed.** A source opens on its catalogue as in Mihon, and its saved searches are chips there; the Sources row keeps its Latest button because nothing else reaches Latest in one tap.
- **A saved query is not sanitized before a feed fetch**, since the catalogue does not sanitize a typed query; sanitize both or neither.
- **An unreadable saved filter applies what it can, silently.** Decoding degrades per element, so there is nothing to abort.
- **The adult-source saved-search specialization is not ported.** Revisit if the adult browse path needs a deserialized filter list held per search.
- **Ids are prefixed by format, not by app** (`tachiyomi:`, not `tsundoku:`): the prefix names a manifest format any repository can publish.
- **Novel kinds split off in `ExtensionManager`, not in `AndroidSourceManager`.** Sixteen readers of the manga maps would otherwise list a novel APK as manga; filtering where results arrive covers them and any later reader.
- **`isNovelSource` stays off `Source`; the kind comes from the manifest.** Every APK's own `NovelSource` copy gives it a default body, so a host default beside it would fail at the call. `fetchPageText` is safe because that copy leaves it abstract.
- **Update badges are judged per APK, not per kind.** A whole-kind judgement turned the apps only a failed store lists Orphaned during an outage.
- **IReader's own repo is trusted by its signing key.** IReader itself has no trust gate; trust on first use and no gate at all were declined.
- **The NovelUpdates tracker owns the site.** The extension's notes sync is destructive, so double writes and skipping it only for bound novels were declined. Auto-binding is a Reikai-owned capability rather than a split of Mihon's `EnhancedTracker`, which would hide the tracker from every other source.
- **No novel tracker pulls progress down, re-points on migration or acts as a source's own server.** A novel copy of manga's pull-style sync, a shared migration re-point kernel and the server-tracker screen behaviours were declined on that premise; revisit if a novel tracker ever does one.
- **Rate limits pace downloads only.** The extensions declaring a minimum already pace their own client, so an app-wide host throttle was not taken from tsundoku.
- **The per-book commands sheet was not built.** No published IReader extension declares a command it reads. Revisit if one does.
- **A page action runs on `NovelPageActionRunner`'s own scope, not WorkManager**, whose 10 KB input cannot carry a page's HTML.
- **An LNReader plugin cannot read a link.** The plugin type declares only `resolveUrl`, path to address, so a plugin's novel is reached through the address guess.

## Upstream divergences

`// RK` islands in `BrowseTab` (Reikai tabs, the derived Extensions page index), `BrowseSourceViewModel` (shared last-used, display and column tracking, the add flow; its `Dialog` keeps only `Filter`), `SearchViewModel` and `GlobalSearchViewModel` (provider hooks; the search state and dialogs moved out), `SourcesViewModel`, `ExtensionsViewModel`, `MigrateSourceViewModel`, `SourceFilterDialog`, `GetEnabledSources` (the shared last-used key), `SourcePreferences` (`lastUsedSource` removed), `SourcePagingSource` (`CataloguePaging`), and the backup models, creator and restorer (fields 715, 716). `SourcesScreen.kt` and `ExtensionsScreen.kt` are partially collapsed, keeping the live row leaves `SourceItem` and `ExtensionItem`. The replaced files (`ExtensionsTab.kt`, both `GlobalSearchScreen.kt`, `MigrateSourceScreen.kt`, both `BrowseSourceScreen.kt`, the grid containers, `DuplicateMangaDialog`, `BrowseSourceDialogs.kt`, the sources-filter screens) are rows in [off-path-manifest.md](../off-path-manifest.md). For the source kinds: `Source` (`fetchPageText`) and the new `SourceTracker` / `RateLimited` in source-api with their keep island, `ExtensionLoader`, `ExtensionManager` (novel maps, `readStores`, `kindListing`), `Extension`, the store models, `SourcePreferencesScreen` (novel ids, the hidden NovelUpdates settings), `AddTracks` (`bindEnhancedTrackers` into `bindOnAdd`), `SetReadStatus` (source tracking), and `DeepLinkViewModel` / `DeepLinkScreen` (shared-link tiers). Mihon's five repository screens are deleted and manifested in favour of `RepositoriesScreen`. Recorded divergences: [upstream-sync.md](../upstream-sync.md) "Deliberate divergences" (catalogue paging, the cleared update badge on a complete fetch).

## Extending

- **A new multi-source list**: give each type a provider answering only about its own rows, assemble in one engine over `ProviderLoad`, and key every row by `SourceKey`.
- **A catalogue capability one type lacks**: add a slot to `EntryBrowseCapabilities` that the other adapter leaves null, so the affordance hides rather than no-ops. A verb both types answer goes on `EntryBrowseBehavior`.
- **A new surface listing results**: build rows with the `EntryBrowseRows.kt` kernels, hold an `EntryAddFlow` per type on the model's scope and render it with `EntryAddDialogs`.
- **A new add path**: call `addEntry` (or `addEntryOrPrompt`) with the type's own verbs; never write categories before the favorite.
- **A new saved filter shape**: answer it in `SavedSearchFilters` and add a case to `SavedSearchFiltersConformanceTest`.
- **A new difference between source kinds**: a typed member on `NovelSource` with a null or empty default, answered by the adapters that support it, and a case in `NovelSourceConformanceTest` that declares it unsupported for the rest.
- **A new source kind**: one `NovelSource` adapter, a manifest feature and gate in `ExtensionLoader`, a `Kind` entry, and its own maps in `ExtensionManager`; never a loader of its own.

## Tests

Lists: `SourcesProviderTest`, `SourceListConformanceTest`, `SourceRowSearchTest`, `SourceIncognitoConformanceTest`, `SourceFilterSectionsTest`, `NovelExtensionRowsTest`, `NovelSourceListsLoadTest`. Catalogue: `FilterChipConformanceTest`, `CataloguePagingTest`, `SourceFailureMessageTest`, `SearchResultRowsConformanceTest`, `FavoritedNovelsTest`, `EntryGesturesTest`. Add flow: `AddSequenceTest`, `AddDecisionConformanceTest`, `EntryAddFlowConformanceTest`, `AddToGroupConformanceTest`, `NovelLibraryAdderTest`. Saved searches and feed: `SavedSearchFiltersConformanceTest`, `SavedSearchConformanceTest`, `SavedSearchToOpenTest`, `SavedSearchViewModelTest`, `FeedViewModelTest`, `MangaFeedProviderTest`, `NovelFeedProviderTest`, `FeedRestorerTest`, `SavedSearchRepositoryTest`, `FeedSavedSearchRepositoryTest`, `GlobalFeedFullTest`. Source kinds: `NovelSourceConformanceTest`, `NovelSourceManagerTest`, `NovelSourceLookupTest`, `TachiyomiNovelSourceTest`, `IReaderNovelSourceTest`, `IReaderFiltersTest`, `IReaderPagesTest`, `IReaderHttpClientsTest`, `IReaderPreferenceStoreTest`, `MadaraChapterEndpointTest`, `NovelImageRequestsTest`, `NovelIconHintsTest`, `NovelSourceSettingsModelTest`, `SourceApiContractTest`. Pipeline: `ExtensionKindTest`, `StoreIndexKindTest`, `IReaderStoreIndexTest`, `KindListingTest`. Tracking and links: `SourceTrackerKernelTest`, `AutoBindTest`, `NovelUpdatesReleasesTest`, `NovelUpdatesPushTest`, `NovelUpdatesAutoBindTest`, `SharedLinkTest`, `ResolveNovelLinkTest`, `ResolveMangaLinkTest`.

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`, or `:data:test` / `:domain:test` for the classes under those modules.

## Related

- User docs: [feed.md](../../feed.md), [extensions.md](../../faq/browse/extensions.md).
- The plugin engine: [ln-plugin-host.md](ln-plugin-host.md). Migration's own flow: [migrate.md](migrate.md).
