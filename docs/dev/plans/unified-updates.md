# Unified Updates tab

One Updates feed that interleaves manga and light-novel chapter updates behind an All / Manga / Novels chip, with shared filters, an include/exclude by-category filter, and an optional group-by-series collapse.

## Goal

Give Reikai a single Updates surface where new manga chapters and new novel chapters appear together in one time-ordered feed, and where every cross-cutting Updates feature (the filter sheet, a filter-by-category control, and a group-by-series collapse) works the same on all three chips: All, Manga, and Novels.

## Why

Reikai is a manga and light-novel reader, so "what's new in my library" has to cover both content types. The two libraries are separate (separate tables, separate sources, separate update jobs), so without consolidation the Updates tab would either show only manga (Mihon's stock behavior) or force the user to look in two places.

The harder constraint is maintenance. Reikai tracks Mihon upstream by hand-porting from a local `refs/mihon` clone, so the manga half of Updates must stay close to stock Mihon or every future upstream change becomes a painful manual reconciliation. The design therefore drives manga entirely through Mihon's own untouched screen model and reuses Mihon's row composables, while novels run on a parallel Reikai model, and a thin Reikai shell renders both. That way a new feature (filters, by-category, grouping) is built once in the shell and applies to both types, instead of being implemented twice or only on some chips.

## Approach

There is one Updates screen. A chip row at the top picks All, Manga, or Novels. The screen pulls manga update rows from Mihon's model and novel update rows from a Reikai model, merges them into a single list sorted newest-first, and renders them under shared date headers. The toolbar filter, the category filter, and the group-by-series toggle all live in one filter sheet and affect whichever rows are showing. Selecting rows (long-press) brings up one action bar that can mark read, bookmark, download, or delete across both types at once.

**The All / Manga / Novels chip.** The chip's selection is a sticky preference (`ReikaiSourcePreferences.updatesContentType`), exposed by `NovelUpdatesScreenModel.contentType` as a `StateFlow` and rendered by the shared `ContentTypeFilterChips` composable. The tab (`UpdatesTab.Content()`) reads it and passes the current `ContentType` into the shell. All three chips render through the same `ReikaiUpdatesScreen`; there is no separate manga screen anymore (`UpdatesTab.kt`).

**Two models behind one shell.** `UpdatesTab` builds both Mihon's stock `UpdatesScreenModel` (manga) and Reikai's `NovelUpdatesScreenModel` (novels) and hands both to `ReikaiUpdatesScreen`, which reads each one's `state` (`ReikaiUpdatesScreen.kt`). Manga rows are rendered with Mihon's own `UpdatesUiItem` composable and every manga action calls a public method on the untouched Mihon model (`updateLibrary`, `markUpdatesRead`, `bookmarkUpdates`, `downloadChapters`, `showConfirmDeleteChapters`, `toggleSelection`, `invertSelection`, `showFilterDialog`, and so on). Because the manga path reuses Mihon's model and row verbatim, the Manga chip behaves exactly like stock Mihon and stays easy to port.

**Interleave by time.** `buildUpdateRows` (`ReikaiUpdatesScreen.kt`) collects manga rows (skipped on the Novels chip) and novel rows (skipped on the Manga chip), sorts the combined set by `dateFetch` descending, then walks the list emitting a `ListGroupHeader` whenever the calendar day changes. All shows both types intermixed strictly by fetch time under one set of date headers; Manga and Novels show only their own type, same layout. The feed is bounded (novels: the last 3 months, up to 500 rows) so the in-memory merge and per-row work stay cheap.

**Shared filters.** The four filters (Unread, Downloaded, Started, Bookmarked) live in Mihon's `UpdatesPreferences`. The manga model already reads them; `NovelUpdatesScreenModel` injects the same `UpdatesPreferences` and applies the same four toggles in-memory to novel rows (`NovelUpdatesScreenModel.kt`, with "Started" defined as `lastTextProgress > 0 && !read`). The filter sheet is Mihon's own `UpdatesFilterDialog`, reused verbatim, so flipping one toggle filters both manga and novel rows at once. The manga-only excluded-scanlators switch stays in the sheet but is inert on novels (accepted for now). The toolbar calendar (Mihon's Upcoming releases) only appears where manga rows show, since novel sources rarely expose a release cadence (see novel-parity-backlog.md, "Parked").

**By-category filter.** A Reikai include/exclude category filter, mounted into the filter sheet through a `// RK` island in `UpdatesFilterDialog` (the `reikaiCategoryRow` slot, `UpdatesFilterDialog.kt`). Manga and novel categories are separate id spaces, so `ReikaiUpdatesCategoryFilter` shows a section per content type and persists per-type selections; on a single-type chip only that type's section appears (`ReikaiUpdatesCategoryFilter.kt`). The novel model resolves each row's categories on demand (cached for the screen's life, only paid when the filter is active; empty categories map to a synthetic Default id of 0) and applies the shared `matchesCategoryFilter` logic (`NovelUpdatesScreenModel.kt`). This reuses the same `CategoryFilterRow` and `matchesCategoryFilter` helpers the library filter uses, and the same shared `CategoryFilterRow` that Active item 2 in the roadmap introduced.

**Group by series.** A per-tab preference (`ReikaiSourcePreferences.updatesGroupBySeries`, default off), toggled via a second `// RK` island in the filter sheet (the `reikaiAfterFilters` slot, `UpdatesFilterDialog.kt`; the `ReikaiUpdatesGroupToggle` switch). When on, `buildUpdateRows` collapses a series' two-or-more same-day chapters into one `UpdateRow.Group` row ("N new chapters" with a chevron); tapping expands it inline into indented child rows, with expansion state held ephemerally in the composable. A single new chapter stays a normal row. Grouping is merge-aware: each favorite's merge-group key is resolved (`mangaSeriesKeys` / `novelSeriesKeys`, `NovelUpdatesScreenModel.kt`) so a series merged across sources collapses into one group, not one per source; the group key is series-plus-date so groups nest under the date headers. Selecting a collapsed group selects all its member chapters through each member's own model, so the bottom action bar gives per-series mark-read / download for free. This works identically for manga and novel rows, so All shows grouped entries of both types interleaved by date.

**Unified selection.** Manga selection lives in Mihon's model, novel selection in the Reikai model. The shell shows one `MangaBottomActionMenu` whenever either has a selection, and each action dispatches to whichever model owns the selected rows (`ReikaiUpdatesScreen.kt`). Select-all and invert call both models (scoped to the visible types). The bottom nav hides during selection on either side (`UpdatesTab.kt`).

Manga rendering is stock-Mihon driven; only the chip, the novel side, the interleave/group builder, and the two filter-sheet `// RK` islands are Reikai additions.

## Key files

Confirmed in `app/src/main/java/`:

- `eu/kanade/tachiyomi/ui/updates/UpdatesTab.kt`: the Voyager `Tab`. Builds both models, renders the chip, hosts the shared filter/delete dialogs, and routes row taps (manga chapter to `ReaderActivity`, novel chapter to `NovelReaderScreen`, and each cover to its details screen: `MangaScreen` / `NovelScreen`). All Reikai additions fenced `// RK`.
- `reikai/presentation/updates/ReikaiUpdatesScreen.kt`: the consolidated screen: toolbar, combined selection action bar, pull-to-refresh, the `buildUpdateRows` interleave/group builder, and the collapsed-group / child row composables.
- `reikai/presentation/updates/NovelUpdatesScreenModel.kt`: the novel side: recent-updates feed, shared filters, by-category filter, merge-aware series keys, selection, and chapter actions. Defines `NovelUpdatesItem`.
- `reikai/presentation/updates/EntryUpdatesRow.kt`: the shared flat update row for both content types (replaced the separate manga `UpdatesUiItem` / novel `NovelUpdatesUiItem`; grouped children already share `UpdatesGroupChildRow`).
- `reikai/presentation/updates/ReikaiUpdatesCategoryFilter.kt`: the include/exclude category control and the group-by-series toggle mounted into the filter sheet.
- `eu/kanade/tachiyomi/ui/updates/UpdatesScreenModel.kt`: Mihon's stock manga model, left untouched so it ports verbatim; the shell reads its state and calls its public actions.
- `eu/kanade/tachiyomi/ui/updates/UpdatesSettingsScreenModel.kt`: the filter-sheet model; carries the Reikai category-preference accessors and category flows.
- `eu/kanade/presentation/updates/UpdatesFilterDialog.kt`: Mihon's filter sheet, with two `// RK` slots (`reikaiCategoryRow`, `reikaiAfterFilters`) for the category row and group toggle.

The novel feed is produced by the background novel update job documented in novel-update-job.md.

## Home-screen widget

The in-app feed has a home-screen companion: a resizable widget that shows recently updated manga and novels together, split into a labeled "Novels" strip and a "Manga" strip. Mihon's own widget is a bare cover grid with no labels, so mixing the two types would make covers indistinguishable; the section labels solve that without a per-cover badge. The strict-by-time interleave the in-app feed uses is dropped here on purpose: for an at-a-glance "what's new" surface, grouping by type reads better than a time order you cannot visually parse in a wall of covers.

Mihon's `UpdatesGridGlanceWidget` stays the manga-only widget, untouched (it ports verbatim on upstream sync); the unified one is added alongside it, so the launcher's widget picker lists both. Tapping a cover opens that title's details via the existing `SHORTCUT_MANGA` / `SHORTCUT_NOVEL` deep links into `MainActivity`.

**Why it lives in the app module, not `presentation-widget`.** A unified widget has to query novels and build novel covers, and both the novel query (`NovelRepository.getRecentNovelUpdatesAsFlow`) and the novel coil model (`NovelCover`) live in the app module's `reikai.*` packages. `presentation-widget` is a library module that cannot depend on `app`, so it cannot see those types. The app module can: it already depends on `presentation-widget`, so the new widget reuses that module's public pieces (`UpdatesMangaCover`, `CoverWidth`/`CoverHeight`, `calculateRowAndColumnCount`, `BaseUpdatesGridGlanceWidget.DateLimit`, `LockedWidget`) while keeping the novel-specific code local. The cost is one new dependency: the app module now pulls in Glance directly (`// RK` in `app/build.gradle.kts`).

**Refresh.** Mihon's `WidgetManager` only watches manga updates and lives in `presentation-widget`, so it cannot drive the unified widget. A parallel `UnifiedUpdatesWidgetManager` in the app module combines the manga and novel update flows plus the app-lock toggle and re-renders on change; it is started from `App` next to the stock `WidgetManager`.

**The novel page url.** Opening a novel from a cover needs the `SHORTCUT_NOVEL` deep link's (source, url) pair, but `NovelUpdateWithRelations` only carried the chapter url. The novel's own url was added to `novelUpdatesView` (the view already joins the novels table for title and cover), with migration `22.sqm` recreating the view for installed databases.

Key files (app module): `reikai/presentation/widget/UnifiedUpdatesGlanceWidget.kt` (the widget + cover/section building), `UnifiedUpdatesGlanceReceiver.kt`, `UnifiedUpdatesWidgetManager.kt`, `res/xml/unified_updates_widget_info.xml`, and the `// RK` receiver block in `app/src/main/AndroidManifest.xml`.

## Status

Shipped and on-device verified (Z Fold / Fold6): all four pieces of the in-app feed (consolidation, novel filters, by-category, group-by-series) are live on `main`, plus the home-screen widget (`b2e4d1cb8`). The novel History tab is the History-side twin of this same consolidation pattern; see novel-parity-backlog.md.

## Decisions & tradeoffs

- **Manga drives off Mihon's untouched model, not a Reikai copy.** The hard requirement is hand-portability from `refs/mihon`. Keeping Mihon's model, queries, prefs, and row composable verbatim means a future upstream change to the manga Updates path ports cleanly; the cost is that the Reikai shell has to mirror what Mihon's own screen did (pull-to-refresh, the last-updated line, the calendar action), which it does.
- **Two `// RK` islands in Mihon's filter sheet, not a parallel Reikai sheet.** The category row and the group toggle are added through two slot parameters in `UpdatesFilterDialog`. A separate Reikai sheet would orphan Mihon's dialog and duplicate its four filters; two greppable islands are the smaller divergence and match the project's marker convention.
- **One filter sheet drives both types.** Reusing Mihon's `UpdatesPreferences` for the four core filters means a single toggle filters manga and novels together. Tradeoff: the excluded-scanlators switch is manga-only and sits inert on the Novels chip (acceptable for v1; can be hidden later behind a small `// RK` if it proves confusing).
- **Group-by-series is merge-aware and defaults off.** Resolving merge-group keys lets a cross-source merged series collapse into one group instead of one per source. The keys are resolved only while grouping is on and re-resolve when the merge prefs change; a favorite added mid-session is not reflected until reopen (same staleness window as the category cache, accepted). Default off keeps the familiar flat feed unless the user opts in.
- **The calendar (Upcoming) stays manga-only.** It only shows where manga rows appear. Light-novel sources rarely expose a reliable release cadence, so a novel upcoming feed would be mostly empty (see novel-parity-backlog.md).
- **In-memory merge, bounded feed.** Interleaving and per-row category lookups happen in memory rather than via a combined SQL view, kept cheap by the 3-month / 500-row bound. This avoids a new cross-table SQLDelight view spanning two independent schemas, at the cost of doing the merge in Kotlin on each emission.
