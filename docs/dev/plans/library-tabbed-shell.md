# Library tabbed shell (Manga + Novels)

How Reikai shows light novels alongside manga everywhere the manga workflow already lives (Library, Browse sources, Browse extensions, repos), using a shared content-type chip rather than a parallel set of novel-only screens.

## Goal

Let a user manage light novels in the same places they manage manga: one Library surface, one Browse, one set of repo controls. Switching between Manga and Novels is a chip tap, not a trip into a separate "light novel" corner of the app. Manga behavior stays byte-for-byte unchanged; novels reuse the same views and gestures.

## Why

The starting point (inherited from the Yōkai era and early rebase work) buried the whole light-novel workflow under a debug shelf: a separate novel library screen, a separate "browse a registry URL" screen, separate repo paste flows. That meant two mental models for one concept. Manga had a polished Library tab, a Browse tab with Sources and Extensions, and repo management under Browse. Novels had none of that in a discoverable place.

The Mihon base is Compose + Voyager throughout (see [.claude/rules/architecture.md](../../../.claude/rules/architecture.md)), and its library and browse views were already callback-driven and data-only at the leaf level. That made it cheaper to feed novel data through Mihon's existing views than to clone them. The design decision: do not add a second tab next to Mihon's Library/Browse tabs. Instead, add a content-type selector (a chip with Manga / Novels / All) inside the existing tabs, and route the active content type's data and callbacks through the same composables. The user gets parity without a duplicated UI tree, and upstream Mihon merges stay tractable because the manga path is untouched.

## Approach

### The dual-content Library shell

The Library tab is one screen. Under the toolbar sits a small chip strip: Manga | Novels. Tapping Novels swaps the whole grid to novels, keeping the same layout, badges, hopper, search, multi-select, and pull-to-refresh as manga. Tapping Manga swaps back. The choice persists.

There is no second Voyager `Tab`. Mihon's single `LibraryTab` ([app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt](../../../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt)) holds two screen models side by side: Mihon's `LibraryScreenModel` (manga) and Reikai's `NovelLibraryScreenModel` (novels), both resolved via `rememberScreenModel` so neither is destroyed on a chip flip. The novel model owns the content-type preference: `NovelLibraryScreenModel.contentType` is a `StateFlow<ContentType>` backed by `ReikaiLibraryPreferences.libraryContentType` ([app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt](../../../app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt)), so the active type survives restart.

The chip strip is a shared `ContentTypeFilterChips` composable stacked under the toolbar inside the Scaffold `topBar`, so the Scaffold sizes its content padding to include it and both library views render below it untouched. Everything downstream reads through `active*` locals (`activeCategories`, `activeSelection`, `activeSearchQuery`, etc.) selected by an `isNovels` flag: when the chip is on Manga, every local falls through to Mihon's manga `state`, so the manga path is provably unchanged; on Novels they read the novel model. Toolbar filter/selection actions, the bottom action menu (download / mark read / change category / delete; merge + unmerge for novels too), the hopper long-press menu, and the category picker sheet are all content-aware via the same `isNovels` branch.

One set of views serves both because novels are handed to Mihon's library composables disguised as `LibraryItem` with a negative manga id. The real novel id is recovered as `-item.manga.id` (see the continue-reading and click handlers in `LibraryTab`). So `ReikaiLibraryContent` (single-list + hopper) and Mihon's `LibraryContent` (tabbed pager) render novel covers, badges, and headers without knowing they are novels. The two view modes (tabbed pager vs single-list with hopper) are a separate Reikai feature; both host novels through this same disguise, so novel support works in both (see the library dual-view-mode note in the memories).

Novel categories, the novel filter/sort sheet, dynamic grouping, and merge collapse live in their own files under [app/src/main/java/reikai/presentation/library/novels/](../../../app/src/main/java/reikai/presentation/library/novels/) (`NovelLibraryScreenModel`, `NovelLibrarySettingsDialog`, `NovelMergeCollapse`, `NovelCategoriesFilter`). Category management routes to the shared `CategoryScreen(novels = true)`. (Novel categories and novel browse are covered in their own plan docs, [novel-categories.md](novel-categories.md) and [novel-browse.md](novel-browse.md); this doc does not duplicate them.)

### Browse + repo + install unification (follow-up)

The same Manga / Novels / All chip appears in Browse. Under Browse, sources and installed extensions for both content types live in one place; the All view interleaves them under "Manga" and "Novels" headers. Adding a light-novel plugin repo happens in the same spot as a manga extension repo, behind the same "Repos" action. The old debug-shelf novel screens are gone.

Mihon's two Browse tabs are swapped at the `BrowseTab` call site behind a `// RK` island for Reikai wrappers ([app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt](../../../app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt)):

- Sources: `reikaiSourcesTab` ([app/src/main/java/reikai/presentation/browse/source/ReikaiSourcesTab.kt](../../../app/src/main/java/reikai/presentation/browse/source/ReikaiSourcesTab.kt)) wraps Mihon's `SourcesScreen` verbatim for the Manga chip, shows an installed-novel-sources list for Novels, and interleaves both (plus the local "Other" group sunk to the bottom) for All. The global-search and filter toolbar actions are content-aware: Novels searches LN sources via `NovelGlobalSearchScreen`.
- Extensions: `reikaiExtensionsTab` ([app/src/main/java/reikai/presentation/browse/extension/ReikaiExtensionsTab.kt](../../../app/src/main/java/reikai/presentation/browse/extension/ReikaiExtensionsTab.kt)) reuses Mihon's extensions tab content for Manga, shows the `LnPluginManager` (install / update / uninstall / update-all) for Novels, and combines both under type headers for All. The tab badge sums Mihon's manga update count and the LN plugin update count. The Browse search query is applied to the novel plugin list too, so searching filters both sides.

Repos are unified rather than tabbed: both tabs' overflow "Repos" action opens Mihon's `ExtensionStoresScreen`, extended with `// RK` islands ([app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/ExtensionStoresScreen.kt](../../../app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/ExtensionStoresScreen.kt)) to add create/delete dialogs for light-novel registry URLs (`createLnRepo` / `deleteLnRepo`) next to the manga extension repos. The actual plugin install runtime is `LnPluginInstaller` ([app/src/main/java/reikai/novel/install/LnPluginInstaller.kt](../../../app/src/main/java/reikai/novel/install/LnPluginInstaller.kt)): `installFromUrl`, `fetchRepo`, `loadInstalled`, `uninstall`, driving the QuickJS plugin host.

`ContentType` ([app/src/main/java/reikai/domain/library/ContentType.kt](../../../app/src/main/java/reikai/domain/library/ContentType.kt)) is the shared `ALL / MANGA / NOVELS` enum; `ReikaiBrowseScreenModel` ([app/src/main/java/reikai/presentation/browse/ReikaiBrowseScreenModel.kt](../../../app/src/main/java/reikai/presentation/browse/ReikaiBrowseScreenModel.kt)) owns the Browse-side content-type state and the LN update count, mirroring how the library model owns the library-side chip.

## Key files

Library shell:
- [app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt](../../../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt): Mihon's `LibraryTab`, `// RK`-patched to hold both screen models, the content-type chip, and the `active*` branch.
- [app/src/main/java/reikai/presentation/library/novels/NovelLibraryScreenModel.kt](../../../app/src/main/java/reikai/presentation/library/novels/NovelLibraryScreenModel.kt): novel library state, owns `contentType` / `setContentType`.
- [app/src/main/java/reikai/presentation/library/novels/](../../../app/src/main/java/reikai/presentation/library/novels/): novel settings dialog, merge collapse, category filter, novel `LibraryItem`.
- [app/src/main/java/reikai/presentation/library/ReikaiLibraryContent.kt](../../../app/src/main/java/reikai/presentation/library/ReikaiLibraryContent.kt): single-list + hopper view, content-agnostic, hosts both types.
- [app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt](../../../app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt): `libraryContentType` preference.
- [app/src/main/java/reikai/domain/library/ContentType.kt](../../../app/src/main/java/reikai/domain/library/ContentType.kt): the `ALL / MANGA / NOVELS` enum.
- [app/src/main/java/reikai/presentation/components/ContentTypeFilterChips.kt](../../../app/src/main/java/reikai/presentation/components/): the shared chip strip.

Browse + repo + install:
- [app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt](../../../app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt): `// RK` swap to the Reikai tab wrappers.
- [app/src/main/java/reikai/presentation/browse/source/ReikaiSourcesTab.kt](../../../app/src/main/java/reikai/presentation/browse/source/ReikaiSourcesTab.kt): chip-switched sources tab.
- [app/src/main/java/reikai/presentation/browse/extension/ReikaiExtensionsTab.kt](../../../app/src/main/java/reikai/presentation/browse/extension/ReikaiExtensionsTab.kt): chip-switched extensions tab.
- [app/src/main/java/reikai/presentation/browse/extension/LnPluginManager.kt](../../../app/src/main/java/reikai/presentation/browse/extension/LnPluginManager.kt) and `LnPluginManagerScreenModel.kt`: LN plugin install/update UI + state.
- [app/src/main/java/reikai/presentation/browse/source/NovelSourcesScreenModel.kt](../../../app/src/main/java/reikai/presentation/browse/source/NovelSourcesScreenModel.kt): installed novel sources list.
- [app/src/main/java/reikai/presentation/browse/ReikaiBrowseScreenModel.kt](../../../app/src/main/java/reikai/presentation/browse/ReikaiBrowseScreenModel.kt): Browse content-type state + LN update count.
- [app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/ExtensionStoresScreen.kt](../../../app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/ExtensionStoresScreen.kt): Mihon repo screen, `// RK`-extended with LN repo create/delete.
- [app/src/main/java/reikai/novel/install/LnPluginInstaller.kt](../../../app/src/main/java/reikai/novel/install/LnPluginInstaller.kt): plugin install/fetch/uninstall runtime.

## Status

Shipped and on-device verified. Novels render in the Library behind the Manga/Novels chip in both view modes (tabbed pager and single-list + hopper). Browse Sources and Extensions both carry the chip with Manga / Novels / All, repos are managed in the shared `ExtensionStoresScreen`, and the old debug-shelf novel library/browse/repo entries are removed. Part of the P5 light-novel vertical (round 1), which the [ROADMAP](../../../ROADMAP.md) marks complete.

## Decisions & tradeoffs

- **A content-type chip, not a second tab.** A `PrimaryTabRow` with a dedicated Novels tab (the original Yōkai-era plan) would have meant a second navigation surface and, in Browse, nested tabs. Feeding the active content type through Mihon's existing views keeps one UI tree, one search box, one toolbar, and leaves the manga path literally unchanged. The cost is the `isNovels` branching threaded through `LibraryTab` and the Browse wrappers, which is verbose but mechanical and `// RK`-fenced.

- **Novels disguised as `LibraryItem` with a negative id.** Reusing Mihon's library composables for novels requires handing them a `LibraryItem`. Encoding the novel id as `-manga.id` lets the same grid, badges, and headers render both types with zero changes to Mihon's leaf composables. The tradeoff is an encoding convention that every novel-side handler must decode (`-item.manga.id`); it is consistent and localized to `LibraryTab` and the novel model.

- **Both screen models held alive at once.** Keeping `LibraryScreenModel` and `NovelLibraryScreenModel` resolved together means an instant chip flip (no reload, per-type selection and scroll preserved) at the cost of two live models. Acceptable: the library is a single long-lived tab and the novel model is cheap when the user has no novels.

- **Repos unified, not tabbed.** Manga extension repos and LN registry URLs share one `ExtensionStoresScreen` with parallel create/delete dialogs, rather than a tabbed repo screen. Fewer surfaces, and the two repo kinds are visually adjacent. The screen is a Mihon file, so the additions are `// RK` islands that survive upstream merges.

- **Manga reused verbatim; novels mirror it.** Every Manga chip path delegates to the untouched Mihon composable (`SourcesScreen`, the extensions tab content, `LibraryContent`). Novel paths are net-new Reikai files that mirror the manga shape. This keeps the upstream-merge surface to the small `// RK` swaps at the call sites and the chip branching, never the manga rendering itself.
