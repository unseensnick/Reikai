# Novel browse & sources

How light-novel sources become installable and reach manga-browse parity inside Reikai's unified Browse: install plugins, list novel sources next to manga, search across all of them, and browse one source with the same grid, filters, pinning, and add-to-library the manga catalogue has.

## Goal

Give light novels a first-class Browse surface that matches the manga one. From the existing Browse tab a user can install a light-novel source from a repository, see it listed beside manga sources, search every installed novel source at once, and open a single source to browse popular/latest titles with filters, pinning, paging, and long-press add-to-library.

## Why

Mihon manga sources are Android APK extensions: the system installs them and class-loads the binary. Light-novel sources are LNReader-style JavaScript plugins run inside a headless QuickJS host (see [novel-plugin-host.md](novel-plugin-host.md) and [ln-plugin-host.md](../ln-plugin-host.md)), so there is no installer or class-loader to lean on. They needed their own install, registry, and update path.

The product principle is one library, one Browse, with content type treated as metadata rather than a separate destination (see [library-tabbed-shell.md](library-tabbed-shell.md)). So rather than a separate "novels" tab, novel sources fold into the same Sources and Extensions tabs behind a content-type filter, and the per-source novel browse mirrors the manga catalogue closely enough that the two feel like one feature.

## Approach

### How LN plugins are installed

An LNReader-style repository is a JSON index (`plugins.min.json`) listing plugins, each with a URL to its `.js` file. A repo URL is added, the app fetches the index, the user picks a plugin, and the app downloads its JavaScript, loads it into the QuickJS host, and persists the URL so it reloads on every launch.

`LnPluginInstaller` ([LnPluginInstaller.kt](../../app/src/main/java/reikai/novel/install/LnPluginInstaller.kt)) owns the app-scoped host and does the work. `fetchRepo(repoJsonUrl)` downloads and parses the index. `installFromUrl(url, metadata?)` downloads the plugin, loads it, registers a `LnPluginSource` with `NovelSourceManager`, and persists the URL. `ensureLoaded()` is idempotent and self-healing: it re-loads any installed-but-not-yet-loaded URL (so a plugin whose download failed once, on a network blip or Cloudflare, recovers the next time a novel screen opens) and is called on entry to the novel surfaces. A plugin's identity is its host-reported id, not its URL, so reinstalling the same plugin from a different URL replaces the old install rather than duplicating it.

The install/manage/update UI lives on the **Extensions** tab, mirroring how manga extensions are installed there (not on Sources, which only browses what is already installed). `LnPluginManagerScreenModel` ([LnPluginManagerScreenModel.kt](../../app/src/main/java/reikai/presentation/browse/extension/LnPluginManagerScreenModel.kt)) renders the same three sections  as Mihon's `ExtensionsScreenModel`: Updates, Installed, and Available. It fans `fetchRepo` across every added repo concurrently as the single source of network truth, diffing versions to fill both the Available list (repo minus installed) and the Updates list, and writes the pending-update count back to preferences so the tab badge can show it.

Repos are managed through Mihon's own `ExtensionStoresScreen`, extended to manage both manga and light-novel repo URLs rather than a separate Reikai screen. The Extensions overflow opens it via a single "Repos" action.

### How Browse hosts novel sources

The Browse tab keeps its existing Sources and Extensions sub-tabs. A sticky `All / Manga / Novels` chip at the top of each decides what that tab shows. Manga renders Mihon's untouched screens; Novels renders the light-novel equivalents; All shows both with a small content-type badge per row.

`BrowseTab` ([BrowseTab.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt)) swaps Mihon's `sourcesTab()` / `extensionsTab()` for `reikaiSourcesTab()` / `reikaiExtensionsTab()` inside a `// RK` island; Mihon's originals stay intact and reused. A shared `ReikaiBrowseScreenModel` ([ReikaiBrowseScreenModel.kt](../../app/src/main/java/reikai/presentation/browse/ReikaiBrowseScreenModel.kt)) holds the one sticky `browseContentType` preference (so both sub-tabs stay in sync) and the LN update count that feeds the combined Extensions badge (manga updates plus LN updates). It kicks the cache-gated `LnPluginUpdateChecker.runIfStale()` on Browse open so the badge is fresh without the user touching the Novels chip.

On the Sources tab, the Novels branch is `NovelSourcesScreenModel` ([NovelSourcesScreenModel.kt](../../app/src/main/java/reikai/presentation/browse/source/NovelSourcesScreenModel.kt)): it calls `ensureLoaded()`, follows `NovelSourceManager.sources`, and groups sources by language with a leading Pinned section, exactly like the manga sources list. Pinning toggles `pinnedNovelSources` ([ReikaiSourcePreferences.kt](../../app/src/main/java/reikai/domain/source/ReikaiSourcePreferences.kt)). Tapping a source opens its per-source browse and records it as last-used (skipped while Incognito).

### Global search fan-out

One query runs across every installed novel source at once, and each source's row fills in independently as it returns, so a slow source never blocks the rest.

`NovelGlobalSearchScreenModel` ([NovelGlobalSearchScreenModel.kt](../../app/src/main/java/reikai/presentation/novel/globalsearch/NovelGlobalSearchScreenModel.kt)) selects the source set (pinned-only or all), then `async`-fans `source.searchNovels(query, 1)` across them under a `Semaphore(5)` throttle (matching manga), updating each source's row to Loading then Success/Error as it completes. It mirrors the manga global search's chips: a Pinned / All source filter (defaults to PinnedOnly), a persisted "has results" toggle that hides empty/loading/error sources, and source headers tappable to open that source's full browse. Long-press add-to-library on a result works here too, delegating to the shared `NovelLibraryAdder` so it behaves identically to per-source browse.

### Per-source novel browse with filters and pinning

Opening one source shows its catalogue the way the manga catalogue looks: a grid or list, a Popular/Latest toggle, a filter sheet, paging as you scroll, an in-library marker on titles already saved, and long-press to add a title to the library with the category picker.

`NovelBrowseScreenModel` ([NovelBrowseScreenModel.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelBrowseScreenModel.kt)) is a `StateScreenModel` over a pre-picked source. Display mode (comfortable / compact / list) is a Compose-observable preference. The filter sheet ([NovelSourceFilterSheet.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelSourceFilterSheet.kt)) renders the plugin's raw filter definition, and `NovelSourceSettingsSheet` renders any per-source settings the plugin exposes. Paging uses the empty-page heuristic: LNReader plugins return a bare array with no `hasNextPage`, so a page that returns nothing marks the end (no contract change). In-library marking reads `NovelRepository.getFavoritedKeysAsFlow()` (read-only `(source, url)` keys) to dim and badge saved titles. Long-press add-to-library routes through the shared `NovelLibraryAdder` ([NovelLibraryAdder.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelLibraryAdder.kt)), which handles materialize-on-add, the category picker, the duplicate ("add anyway") dialog, and remove-with-confirm, so per-source browse and global search share one favorite/category code path. Tapping a result opens novel details (see [novel-details.md](novel-details.md)).

## Key files

Net-new Reikai code (under `reikai.*`):

- [reikai/novel/install/LnPluginInstaller.kt](../../app/src/main/java/reikai/novel/install/LnPluginInstaller.kt): install / fetch-repo / ensure-loaded / uninstall over the QuickJS host.
- [reikai/novel/source/NovelSourceManager.kt](../../app/src/main/java/reikai/novel/source/NovelSourceManager.kt): in-memory registry of installed novel sources.
- [reikai/presentation/browse/ReikaiBrowseScreenModel.kt](../../app/src/main/java/reikai/presentation/browse/ReikaiBrowseScreenModel.kt): shared content-type chip state + combined update badge.
- [reikai/presentation/browse/source/ReikaiSourcesTab.kt](../../app/src/main/java/reikai/presentation/browse/source/ReikaiSourcesTab.kt) + [NovelSourcesScreenModel.kt](../../app/src/main/java/reikai/presentation/browse/source/NovelSourcesScreenModel.kt): Sources tab wrap + the novel sources list (language groups, Pinned section, pin toggle).
- [reikai/presentation/browse/extension/ReikaiExtensionsTab.kt](../../app/src/main/java/reikai/presentation/browse/extension/ReikaiExtensionsTab.kt) + [LnPluginManagerScreenModel.kt](../../app/src/main/java/reikai/presentation/browse/extension/LnPluginManagerScreenModel.kt) + [LnPluginManager.kt](../../app/src/main/java/reikai/presentation/browse/extension/LnPluginManager.kt): Extensions tab wrap + Updates / Installed / Available manager.
- [reikai/presentation/browse/components/NovelSourceRow.kt](../../app/src/main/java/reikai/presentation/browse/components/NovelSourceRow.kt): source row.
- [reikai/presentation/browse/MangaLibraryAdder.kt](../../app/src/main/java/reikai/presentation/browse/MangaLibraryAdder.kt) + [reikai/presentation/novel/browse/NovelLibraryAdder.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelLibraryAdder.kt): shared favorite / category / duplicate orchestration reused by both Browse and global search.
- [reikai/presentation/novel/browse/NovelBrowseScreen.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelBrowseScreen.kt) + [NovelBrowseScreenModel.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelBrowseScreenModel.kt) + [NovelBrowseGridCell.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelBrowseGridCell.kt) + [NovelSourceFilterSheet.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelSourceFilterSheet.kt) + [NovelSourceSettingsSheet.kt](../../app/src/main/java/reikai/presentation/novel/browse/NovelSourceSettingsSheet.kt) + [DuplicateNovelDialog.kt](../../app/src/main/java/reikai/presentation/novel/browse/DuplicateNovelDialog.kt): per-source browse.
- [reikai/presentation/novel/globalsearch/NovelGlobalSearchScreen.kt](../../app/src/main/java/reikai/presentation/novel/globalsearch/NovelGlobalSearchScreen.kt) + [NovelGlobalSearchScreenModel.kt](../../app/src/main/java/reikai/presentation/novel/globalsearch/NovelGlobalSearchScreenModel.kt): cross-source search.
- [reikai/domain/source/ReikaiSourcePreferences.kt](../../app/src/main/java/reikai/domain/source/ReikaiSourcePreferences.kt): `browseContentType`, `pinnedNovelSources`, `novelBrowseDisplayMode`, the global-search "has results" key.
- [reikai/data/novel/update/LnPluginUpdateJob.kt](../../app/src/main/java/reikai/data/novel/update/LnPluginUpdateJob.kt): periodic plugin-update check + notification.

Mihon files patched (`// RK` islands):

- [eu/kanade/tachiyomi/ui/browse/BrowseTab.kt](../../app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt): swap in the Reikai Sources/Extensions tab wrappers + shared browse model.
- `ExtensionStoresScreen`: extended to manage manga + light-novel repos.
- `Notifications.kt`, `Migrations.kt`: LN update channel + the migration that schedules the update job.

## Status

Shipped and on-device verified. Part of the P5 light-novel vertical (round 1, see `ROADMAP.md`). Source pinning and the global-search Pinned / All / Has-results chips with tappable source headers landed in round 2, and long-press add-to-library in global search reached full Browse parity (favorite + category picker + duplicate dialog + remove).

## Decisions & tradeoffs

- **Install lives on Extensions, not Sources.** Preserves Mihon's separation: Sources browses what is installed, Extensions installs and updates. The update badge and repo management belong with Extensions.
- **A sticky content-type chip, not a separate novels tab.** One Browse, content type as metadata, reused on Library and other surfaces. The chip key is shared so Sources and Extensions stay in sync.
- **Mihon's repo screen is extended, not replaced.** Adding both repo buckets to `ExtensionStoresScreen` keeps the rich existing rows and dialogs; a net-new screen was the earlier idea but was unnecessary.
- **Empty-page paging, no contract change.** LNReader plugins return bare arrays without a `hasNextPage` flag, so an empty page marks the end. Matches how the plugins natively work.
- **A shared library-adder for both browse paths.** `NovelLibraryAdder` is reused by per-source browse and global search so add-to-library behaves identically in both, with no duplicated favorite/category/duplicate logic. The manga side mirrors this with `MangaLibraryAdder`.
- **Self-healing load over a hard install state.** `ensureLoaded()` retries failed loads on each novel-screen open, so a transient download failure recovers without a cold restart. Novel sources have no "installed but binary not loaded" state to model, unlike APK extensions.
