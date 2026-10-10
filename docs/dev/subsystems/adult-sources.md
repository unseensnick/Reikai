# Adult sources

## Purpose

The adult-source subsystem (the `exh` packages, ported from Komikku and re-typed onto Mihon) gives a handful of gallery sites first-class treatment: E-Hentai, ExHentai, Pururin and nhentai.net ship built in, and installed extensions for nHentai, 8Muses, LANraragi, HentaiFox, AsmHentai and SchaleNetwork (Koharu) are wrapped so their galleries gain namespaced tag metadata. That metadata powers library tag search, rich browse rows and a metadata viewer. The same wrapping machinery carries the MangaDex enhanced source, which adds login, an MDList tracker and follows sync; it lives under `exh/md` for parity with Komikku, and is not adult-specific. Everything here is manga only.

## How it works

### Gates

`ExhPreferences.isHentaiEnabled` (`eh_is_hentai_enabled`, off by default, the "Enable adult sources" switch) registers the built-in adult sources. `enableExhentai` additionally registers ExHentai and is set by a successful login. Wrapping an installed extension is not gated by either: an extension is wrapped whenever it is installed, and `DelegateSourcePreferences.delegateSources` (`eh_delegate_sources`, on by default, the "Enable delegated sources" switch beside "Enable adult sources" in Browse and sources settings) decides on every call which source the wrapper routes through. Off, `EnhancedHttpSource.source()` and `getMainSource` both return the plain extension, so fetching and every delegate extra (metadata, MangaDex follows and settings, page previews, batch-add import) go together, and the switch needs no restart. Batch add stays gated by the adult-sources switch alone.

### Built-in and delegated sources

`AndroidSourceManager` (`// RK`) rebuilds its source map whenever the extensions or either gate change. With adult sources on it registers one `EHentai` per language id in `EHENTAI_EXT_SOURCES` (and `EXHENTAI_EXT_SOURCES` with ExHentai on), plus `Pururin` and `NHentaiNet`; while built-in EH is on the stock E-Hentai extension's sources are skipped (`BlacklistedSources`), since they share ids and would shadow it. `ExtensionManager` hides that extension from the list too.

Every other installed source passes through `toEnhancedSource`, which looks it up in `DELEGATED_SOURCES` **by source name first**, then by qualified class name or factory package prefix, and wraps a match in `EnhancedHttpSource` over a `DelegatedHttpSource` subclass (`NHentai`, `EightMuses`, `Lanraragi`, `HentaiFox`, `AsmHentai`, `Koharu`, `MangaDex`). The match is recorded in `currentDelegatedSources`, from which `nHentaiDelegatedSourceIds` is derived, because the nHentai extension's id varies by version.

### Metadata

A metadata source implements `MetadataSource` (source-api), which owns the round trip: read stored metadata, `parseIntoMetadata`, store it, and build the `SManga` from it. The models are `RaisedSearchMetadata` subclasses in `source-api/src/main/kotlin/exh/metadata/metadata/` (one per site plus `MangaDexSearchMetadata`), flattened into the `search_metadata`, `search_tags` and `search_titles` tables keyed by manga id. A wrapper refreshes through `layeredMangaUpdate`: it starts from the extension's own details and chapters, so their status, author and fetch-once update strategy survive, and lays the parsed metadata over them. A built-in gallery source returns one chapter, the gallery itself (`singleChapterGalleryUpdate`).

Metadata feeds four places: the details card (`GalleryInfoBox`, with a curated E-Hentai grid and a MangaDex rating row; stars round to the nearest half), `MetadataViewScreen` (the full `getExtraInfoPairs` dump behind More info), namespaced tag chips, and library tag search through the query engine in `exh/search` (`SearchEngine`, `namespace:tag` with wildcards). The full E-Hentai tag catalogue in `exh/eh/tags/` drives browse autocomplete.

### E-Hentai browse

The site's `next=` parameter is a gallery-id cursor, not a page number. `EHentai.genericMangaParse` returns a `MetadataMangasPage` (`// RK` in source-api's `MangasPage`) carrying the last gallery's id as `nextKey` plus each row's metadata, and `SourcePagingSource` (`// RK`) takes that cursor over its own page counter. Browse pages `Pair<Manga, RaisedSearchMetadata?>`, preferring stored metadata and falling back to the carried one, and E-Hentai sources draw `BrowseSourceEHentaiList` rows (cover, uploader, rating, category badge, language, page count, date) while `ExhPreferences.enhancedEHentaiView` is on (read once when the screen opens). Other adult sources keep the standard grid.

### Account features (E-Hentai)

- **Login** goes through `EhLoginActivity` (a WebView), storing the member id, pass hash and igneous cookies as private preferences.
- **uconfig.** `EHConfigurator` and `EhUConfigBuilder` push image quality, Hentai@Home and tag thresholds to the account's settings profile. The settings live in `SettingsEhScreen`, opened from the Browse and sources settings (`SettingsBrowseScreen`).
- **Favorites backup is one way.** `EHentai` is a `SourceTracker` with chapter tracking off and `supportsFavoritesTracking = isFavoritesBackupOn()` (ExHentai on and the backup switch on). Every add path reaches `onFavorited` through the source-tracker dispatcher (see [tracking.md](tracking.md)), which waits 3 seconds and pushes only if the gallery is still in the library; a failure shows the shared "could not sync to its site" toast. `EhFavoritesBackupWorker` is the one-off "back up all favorites now" backfill. Nothing is ever pulled from the account.
- **Remove from account** is an opt-in tick in the details remove confirm. `removeGallery` runs on `RemoteFirstRemoval`: the account removal first, the library removal only once it succeeds, a failure toasted with the gallery kept.
- **Gallery update checker.** Favorited E-Hentai galleries are excluded from the library update (below), so `EHentaiUpdateWorker` is their only update path: it checks for a newer version on its own schedule (`exhAutoUpdateFrequency`, `isSkipped`) and `EHentaiUpdateHelper` reconciles the version chain, folding chapters, read state, history and categories into the accepted copy. `findAcceptedRootAndDiscardOthers` returns the chapters `updateFromRemote` stored, with real ids, so the new-chapter notification's actions work.

`LibraryUpdateWorker` (`// RK`) drops `LIBRARY_UPDATE_EXCLUDED_SOURCES` (every EH and ExH id, Pururin, nhentai.net) and `nHentaiDelegatedSourceIds` from the sweep, since re-fetching saved galleries on every update risks rate limits and bans.

Import entry points: `InterceptActivity` opens a tapped gallery or MangaDex link, and `BatchAddScreen` adds a list of gallery URLs (`UrlImportableSource`, implemented by `EHentai`, `NHentai`, `NHentaiNet`, `EightMuses`, `Pururin` and `MangaDex`). Both resolve the link through `GalleryAdder.pickSource`, which reaches a delegate through `getMainSource`, so a delegate stops importing when delegation is off. When no source imports a tapped link, `InterceptActivity` hands it to the installed extension that also opens it, which sends the app its own search intent; with no such extension the import fails with a message. Built-in sources draw bundled logos from `BuiltInSourceLogo`.

### MangaDex enhanced source

`MangaDex` wraps the installed MangaDex extension (`factory = true`, matched by the name "MangaDex") and implements `MetadataSource`, `LoginSource`, `FollowsSource`, `RandomMangaSource` and `NamespaceSource`. Details are enriched through `MangaHandler` and the parse-only `ApiMangaParser`; chapters, pages, browse and search are the extension's. The enhanced details honour the extension's own "Alternative titles in description" and "Final chapter in description" switches, read from its `source_<id>` preferences.

- **Login and MDList.** Sign-in is browser OAuth with PKCE from Settings, Tracking, returning to `MangaDexLoginActivity` on `tachiyomisy://mangadex-auth` (Komikku's grandfathered public client; MangaDex no longer registers new ones). The token is stored as the `MdList` tracker's (`TrackerManager.MDLIST`, id 60), which binds a title and round-trips follow status and rating through `FollowsHandler`. `MangaDexAuthInterceptor` refreshes under a lock; only a refresh token MangaDex rejects (400 or 401) clears the login.
- **Follows.** `MangaDexFollowsScreen` (a Follows button in the browse filter sheet) pages the user's follows by offset over a `BrowseSourceViewModel` subclass. A Random button opens `/manga/random`.
- **Sync.** `SettingsMangaDexScreen` (under Browse and sources settings) holds the preferred MangaDex language (`preferredMangaDexId`, read by `MdUtil.getEnabledMangaDex` on every call, falling back to the first enabled source), the follow statuses to import, and two actions run by `MangaDexSyncWorker`: import follows into the library, and push library MangaDex entries as MDList-tracked follows.
- **Links.** A tapped `mangadex.org` (or `www.`) `/title/`, `/manga/` or `/chapter/` link opens in `InterceptActivity` and imports into the library. `mapUrlToMangaUrl` reads the title id; a chapter link resolves its title through `MangaHandler.getMangaFromChapterId` (the chapter's `manga` relationship) and the reader opens on that chapter when the source's language has it. Several enabled MangaDex languages ask which one to import into, as E-Hentai does.
- **Tracker search** loads covers through `MangaDexTrackCoverFactory` with the extension's headers and batches the details and rating calls.

`MANGADEX_IDS` (61 language ids) gates the sync and the metadata surfaces; it is a separate gate from the name match that wraps the source, so both must hold.

### Debug menu

Settings, Advanced, Debugging opens Komikku's debug menu whole: `DebugFunctions` listed by reflection and the six `DebugToggles` under Komikku's `eh_debug_toggle_<name>` keys, read where Komikku reads them (the root redirect in `MangaViewModel`, the overlay in debug builds, pull-to-root and root-only versions in `EHentai`, the checker's one-day floor). Hidden covers apply to every cover of both content types through `MangaCover`.

## Key files

- `source-api/src/main/kotlin/exh/source/`: `EnhancedHttpSource`, `DelegatedHttpSource`, `BlacklistedSources`, `SourceIds.kt` (`EHENTAI_EXT_SOURCES`, `LIBRARY_UPDATE_EXCLUDED_SOURCES`, `MANGADEX_IDS`).
- `source-api/src/main/kotlin/eu/kanade/tachiyomi/source/online/MetadataSource.kt`, `LoginSource.kt`, `FollowsSource.kt`, `RandomMangaSource.kt`.
- `source-api/src/main/kotlin/exh/metadata/metadata/`: `RaisedSearchMetadata`, `EHentaiSearchMetadata`, `MangaDexSearchMetadata`.
- `core/common/src/main/kotlin/exh/source/ExhPreferences.kt` and `core/common/src/main/kotlin/exh/pref/DelegateSourcePreferences.kt`: the gates.
- `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt`: `toEnhancedSource`, `DELEGATED_SOURCES`, `nHentaiDelegatedSourceIds`.
- `app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentai.kt` (`genericMangaParse`, `onFavorited`), `NHentaiNet.kt`, `MangaDex.kt`; `app/src/main/java/eu/kanade/tachiyomi/source/online/english/Pururin.kt`, `EightMuses.kt`.
- `app/src/main/java/exh/source/LayeredMangaUpdate.kt`, `GalleryMangaUpdate.kt`.
- `app/src/main/java/exh/eh/`: `EHentaiUpdateWorker`, `EHentaiUpdateHelper`, `EHentaiUpdateNotifier`; `exh/eh/tags/`.
- `app/src/main/java/exh/favorites/EhFavoritesBackupWorker.kt`, `EhGalleryRemoval.kt` (`removeGallery`); `app/src/main/java/exh/uconfig/`.
- `app/src/main/java/exh/ui/`: `login/EhLoginActivity`, `metadata/MetadataViewScreen`, `intercept/InterceptActivity`, `batchadd/BatchAddScreen`.
- `app/src/main/java/exh/search/SearchEngine.kt`: library tag queries.
- `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceEHentaiList.kt` and `data/src/main/java/tachiyomi/data/source/SourcePagingSource.kt`.
- `app/src/main/java/exh/md/`: `handlers/`, `network/MangaDexAuthInterceptor.kt`, `follows/`, `MangaDexSyncWorker.kt`, `utils/MdUtil.kt`; `app/src/main/java/eu/kanade/tachiyomi/data/track/mdlist/MdList.kt`.
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsEhScreen.kt`, `SettingsMangaDexScreen.kt`.
- `app/src/main/java/exh/debug/`: `DebugFunctions`, `DebugToggles`, `SettingsDebugScreen`, `HiddenCover`.
- `app/src/main/java/exh/assets/BuiltInSourceLogo.kt`.

## Invariants and traps

- **Delegated sources match by name, not class.** R8 renames a factory extension's source class (HentaiFox's becomes a top-level `a`), so a class match never fires on a minified extension.
- **Every MangaDex API, auth and cover call carries the extension's headers.** MangaDex answers Reikai's injected browser User-Agent with HTTP 400 (the web app instead of JSON), which silently breaks details, login, token refresh, Random and covers. Never build `MangaDexService` or the login helper on the bare network client; Komikku does, and has these bugs.
- **E-Hentai paging needs the cursor.** Feeding `next=` a page number returns an empty or malformed page after page 1.
- **Never let the library update fetch EH, ExH, Pururin or nHentai galleries.** A new built-in gallery source goes into `LIBRARY_UPDATE_EXCLUDED_SOURCES`, or into a derived list like nHentai's when its id is not fixed.
- **Reach a delegate's extras through `getMainSource`, never `enhancedSource`.** `getMainSource` (like `EnhancedHttpSource.source()` and `configurableSource`) honours the delegated-sources switch; a direct `enhancedSource` read keeps a delegate feature alive after the user turns delegation off.
- **The account backup is one way.** Nothing may pull the account's favorites into the library or remove a gallery from the account without the user's tick.
- **An `apply {}` block on an `SManga` can shadow a model property with a local**, which crashed the port once; use `also {}` or explicit receivers.
- **A converted gallery's downloads stay under the old source's folder** after the debug EH and ExH conversion, as in Komikku.

## Decisions

- **Port Komikku, re-typed; do not reinvent.** The subsystem is Komikku's behaviour on Mihon's models; the Reikai work is the re-typing and the `// RK` wiring. Void if Komikku stops being maintained.
- **Built-in E-Hentai for every extension language id.** Saved galleries from the stock extension resolve without a migration, so Komikku's `EXHMigrations` id remapper is not needed.
- **The account backup rides the source-tracker hook.** That reaches every add path (details, browse, search, feed, recents, bulk, batch add, shared links) instead of the details page alone.
- **Two-way favorites sync is not built.** It is the one feature that would mutate the library from a remote; it stays parked.
- **A wrapper's refresh layers metadata over the extension's details**, at the cost of one extra details request, so the extension's status and update strategy survive.
- **MangaDex delegates chapters and pages to the extension.** The extension already handles the external aggregators (MangaPlus, Bilibili and others), so Komikku's handlers for them are not ported, nor are data-saver and cover settings, which the extension exposes.
- **The MangaDex similar carousel is dropped.** Its only data source is frozen and unmaintained, and `/relation` returns exact relations, not discovery.
- **Sync is a dedicated worker**, never a `LibraryUpdateWorker` target.
- **Two Komikku debug functions are not ported.** `killSyncJobs` has no library sync to kill, and `migrateLangNhentaiToMultiLangSource` has no fixed multi-language nHentai id to move to. `addAllMangaInDatabaseToLibrary` adds through `MangaLibraryAdder`, so categories and trackers apply as for any add.
- **Four candidate adult sites (Luscious, HentaiNexus, 3Hentai, Hitomi.la) are not wrapped**: no stock extension to wrap, or too little structured metadata.
- **A link no source imports goes back to its extension, not to an error.** With delegation off the MangaDex extension's own link activity still claims the link, and handing it on gives the extension's search scoped to that title, so turning delegation off never strands a link.

## Upstream divergences

`// RK` islands: `AndroidSourceManager` (gates, built-ins, wrapping), `ExtensionManager` (blacklist), `LibraryUpdateWorker` (exclusion), `MangasPage` and `SourcePagingSource` (the metadata page and cursor), `BrowseSourceViewModel` (metadata pairing, follows hooks, the enhanced-view gate), `MangaViewModel` and `MangaScreen` (remove-from-account confirm, metadata viewer, root redirect), `TrackerManager` and `SettingsTrackingScreen` (MDList), `SettingsBrowseScreen` and `SettingsAdvancedScreen` (the settings and debug entries), the browse source icon, the manifest (the OAuth callback, `InterceptActivity`), and `app/proguard-rules.pro` (`DebugFunctions`, reached by reflection). Where Reikai is ahead of Komikku (the version-merge chapter ids, the MDList token refresh) is recorded in [feature-ports.md](../feature-ports.md).

## Extending

- **A new enhanced wrapper**: a `DelegatedHttpSource` subclass implementing `MetadataSource` with a `RaisedSearchMetadata` model, refreshing through `layeredMangaUpdate`, and a `DELEGATED_SOURCES` entry keyed by the extension's source name. Add its id to the update exclusion if its galleries must not be re-fetched.
- **A new built-in gallery source**: register it in the `isHentaiEnabled` block of `AndroidSourceManager`, add its id to `LIBRARY_UPDATE_EXCLUDED_SOURCES`, give it a `BuiltInSourceLogo`, and list it in the user doc's table.
- **A new debug function**: a public member of `DebugFunctions`; the menu finds it by reflection.

## Tests

Sources and metadata: `LayeredMangaUpdateTest`, `GalleryMangaUpdateTest`, `MetadataSourceTagSearchTest`, `SearchEngineTest`, `SearchMetadataChipsTest`, `MangaRestoreSearchMetadataTest`, `NHentaiApiTest`, `SourceHelpersTest`, `EnhancedEhViewTest`, `BuiltInSourceLogoTest`, `SourceApiContractTest`. E-Hentai account: `EHentaiUpdateHelperTest`, `EHentaiUpdateWorkerSkipTest`, `EhGalleryRemovalTest`, `EHentaiAccountBackupTest`, `ThrottleManagerTest`, `GalleryAdderTest`. MangaDex: `MangaDexLinkImportTest`, `MangaDexAuthInterceptorTest`, `MangaDexSyncDetailTest`, `MangaDexTrackCoverTest`, `MdUtilTest`. Debug: `DebugTogglesTest`, `SettingsDebugViewModelTest`, `DebugDatabaseRepositoryImplTest`.

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`.

## Related

- User docs: [adult-sources.md](../../adult-sources.md), [built-in-sources.md](../../built-in-sources.md).
