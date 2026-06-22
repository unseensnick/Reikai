# Library screen carry (P2)

P2 carries Reikai's signature library screen onto Mihon: the Compose foundation, the single-list (category-hopper) view alongside Mihon's tabbed pager, multi-select action mode with its library actions, and an opt-in update-errors screen.

## Goal

Give Reikai its J2K-flavoured library on the Mihon base while keeping Mihon's data pipeline. Concretely: a single collapsible list of all categories with a "hopper" for jumping between them, dynamic grouping (group by tag / author / language as well as the user's categories), per-category sort, a category sort order, hidden categories, the panorama display mode, multi-select actions, and a persistent record of which library entries failed their last update. The default Mihon tabbed library stays; the single-list view is a toggle on top of it, and every library feature works in both.

## Why

Mihon's library is a horizontally-swiped pager: one tab per category, one grid per tab. Reikai's identity (inherited from TachiyomiJ2K via Yōkai) is the opposite: one vertical list with all categories stacked and collapsible, plus a floating "hopper" puck to jump between categories or scroll to top. Users coming from the Yōkai-era app expect that view, the dynamic grouping, and the richer category controls.

The key decision that shaped the whole phase: Mihon's `LibraryScreenModel` already implements the entire filter / sort / group / search / badge pipeline. Building a separate ported ScreenModel would duplicate it and double the upstream-sync cost. So P2 reuses Mihon's data pipeline untouched and concentrates the net-new work in two places: the rendering layer (a parallel single-list renderer in `reikai.*`) and a handful of small, `// RK`-fenced data extensions inside `LibraryScreenModel`. This is the smallest patch surface that still delivers the J2K library feel.

## Approach

The data side is Mihon's, the rendering side is Reikai's, and the two are joined at one mount point.

**The Compose library foundation.** The library is a Voyager `Tab` (`LibraryTab`) backed by Mihon's `LibraryScreenModel`, following the screen conventions in [.claude/rules/compose-port.md](../../../.claude/rules/compose-port.md) (Voyager `Tab` + `ScreenModel`, state as `StateFlow`, Injekt in the model, coroutines on `screenModelScope`). Reikai does not re-implement the model. Its filter / sort / group / search / badge work all stays in Mihon's `LibraryScreenModel.getFavoritesFlow` / `applyFilters` / `applyGrouping` / `applySort`. Reikai's additions are `// RK`-fenced islands inside that model: the dynamic-grouping branch, the category-reorder step, the hidden-category drop, extra filter dimensions (lewd, category include/exclude), and a parallel state flow off a new `ReikaiLibraryPreferences` holder that carries the roughly twenty display-preference fields Mihon has no equivalent for (hopper visibility / gravity / long-press action, grouping mode, collapsed-category sets, category sort order, panorama and source-badge toggles, and so on). Those preference values are read in the model and exposed as state, never read inside a composable.

**The single-list (hopper) view alongside Mihon's tabbed pager.** Mihon's tabbed pager is left in place and stays the default. The single-list view is a separate branch in `LibraryTab.Content()` (`// RK`-fenced) that renders Reikai's `ReikaiLibraryContent` instead of Mihon's pager when the user enables "show all categories in one list". `ReikaiLibraryContent` is a net-new file: one `FastScrollLazyVerticalGrid` with category headers and manga cells interleaved as lazy items, one header plus one item per entry, so the fast-scroller index math stays valid for any layout. It respects Mihon's global display-mode setting (compact grid, comfortable grid, cover-only grid, list) by switching the cell composable per mode, reusing Mihon's `MangaCompactGridItem` / `MangaComfortableGridItem` / `MangaListItem` leaves and badges; list mode is a one-column grid so the hopper's single `LazyGridState` keeps working without a second scroll state. The hopper itself (`ReikaiCategoryHopper`) is a floating puck that jumps to the previous / next category and (long-press) opens a category picker or the group-by picker. Reikai also adds a richer category header (`ReikaiLibraryCategoryHeader`): per-category sort indicator, per-category refresh, and a select-all circle in selection mode. The two view modes share the same data, so this is the load-bearing constraint: every feature in this phase works in both the tabbed pager and the single-list view (the dual-view rule that governs all later library work).

**Action-mode multi-select and library actions.** Multi-select reuses Mihon's native selection rather than a ported parallel one: `LibraryScreenModel` already carries the selection set, the selection toolbar, and the bottom action menu (move-to-category, mark read / unread, download, delete, migrate, share). Reikai's contribution here is the merge and unmerge actions (Reikai's preference-based multi-source grouping, landed in P4's merge engine) plus the single-list category header's select-all-in-category, which calls Mihon's `selectAllInCategory`. The action menu and selection visuals are Mihon's; the only `// RK` additions are the merge / unmerge entries and wiring them through the single-list renderer so selection looks and behaves identically in both views.

**The update-errors screen.** Mihon only surfaces a failed library update as a transient notification plus a temp cache file; once dismissed there is no in-app record. Reikai adds a persistent, database-backed record, opt-in via a switch in Settings → Advanced (default off) and reached from the library overflow menu only when the toggle is on. A new `library_update_errors` table plus a favorites-only view plus migration `12.sqm` (the rebase's first schema change) store one row per failed manga with its error message; the table's foreign key points into `mangas` with `ON DELETE CASCADE`, so nothing in the manga path references it and the migration is purely additive. The repository, interactors, and Voyager screen all live in `reikai.*`. `LibraryUpdateJob` gets a `// RK` hook that, when the toggle is on, records on failure and clears on success; Mihon's existing notification path is untouched. The screen groups rows by error message, each row a manga (cover / title / source), with multi-select dismiss, clear-all, and retry.

This is current behavior. A few items in the original P2 scope were dropped or absorbed during the phase: the staggered grid (Y5) was dropped (the user does not use it, and it would require a whole cover-ratio metadata subsystem just to differ from a uniform grid); the bespoke five-tab settings sheet was retired in favour of extending Mihon's `LibrarySettingsDialog` with `// RK` islands; the search-syntax item was a no-op because Mihon's matcher already covers it; and a series-type filter was dropped for lack of a clean source on Mihon's model. The panorama comfortable-grid mode, hidden categories (a flag bit, no migration), and a source-icon badge were added during the phase.

## Key files

Net-new Reikai code (`reikai.*`, own files, no fence needed):

- `app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt`: the display-preference holder (hopper, grouping, collapse sets, category sort order, panorama / source-badge toggles, `trackUpdateErrors`).
- `app/src/main/java/reikai/presentation/library/ReikaiLibraryContent.kt`: the single-list renderer (display-mode switch + list one-column path).
- `app/src/main/java/reikai/presentation/library/ReikaiCategoryHopper.kt`: the floating category-jump puck.
- `app/src/main/java/reikai/presentation/library/ReikaiLibraryCategoryHeader.kt`: the single-list category header (sort indicator, refresh, select-all circle).
- `app/src/main/java/reikai/presentation/library/ReikaiFastScrollGrid.kt`, `ReikaiCategoryPickerSheet.kt`, `ReikaiLibraryBadges.kt`, `ReikaiLibrarySettings.kt`, `ReikaiLibraryState.kt`: supporting renderer pieces, the picker sheet, badges, settings glue, and the single-list state.
- `app/src/main/java/reikai/presentation/library/LibraryDynamicGrouping.kt`, `LibraryGroup.kt`, `ReikaiDynamicCategory.kt`, `ReikaiCategorySort.kt`: dynamic grouping (synthetic categories with negative ids) and category sort order.
- `app/src/main/java/reikai/presentation/library/ReikaiComfortableGridPanoramaItem.kt`, `ReikaiLibraryComfortableGridPanorama.kt`: the panorama display mode.
- `app/src/main/java/reikai/domain/category/ReikaiCategoryHidden.kt`, `CategoryFilter.kt`: the hidden-category flag bit and the category include/exclude filter.
- `app/src/main/java/reikai/domain/library/updateerror/`: `LibraryUpdateError.kt` (model), `LibraryUpdateErrorRepository.kt`, `LibraryUpdateErrorInteractors.kt`.
- `app/src/main/java/reikai/data/library/updateerror/LibraryUpdateErrorRepositoryImpl.kt`: the repository impl over the injected `Database`.
- `app/src/main/java/reikai/presentation/library/updateerror/UpdateErrorsScreen.kt`, `UpdateErrorsScreenModel.kt`: the Voyager update-errors screen.

Update-errors database (the only data-module code in P2):

- `data/src/main/sqldelight/tachiyomi/data/library_update_errors.sq`: table + queries.
- `data/src/main/sqldelight/tachiyomi/view/library_update_error_view.sq`: the favorites-only view.
- `data/src/main/sqldelight/tachiyomi/migrations/12.sqm`: the additive migration.

Mihon files patched with `// RK` islands:

- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`: the mount point (single-list branch, `displayMode` read, update-errors overflow entry).
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt`: the data islands (dynamic grouping, category reorder, hidden-category drop, extra filters, the `ReikaiLibraryPreferences` state flow, `selectAllInCategory`).
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibrarySettingsScreenModel.kt`: the extended settings dialog's data.
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt`: the gated update-error record / clear hook.

Mihon leaves reused as-is (read-only reference): `eu/kanade/presentation/library/components/CommonMangaItem.kt` (the grid / list cells) and `LibraryBadges.kt`, `eu/kanade/presentation/library/LibrarySettingsDialog.kt` (one level up, not under `components/`), and `eu/kanade/presentation/manga/components/MangaCover.kt`.

## Status

Shipped and on-device verified (ROADMAP marks P2 done). The single-list view, hopper, dynamic grouping, filter / sort, category sort order, hidden categories, panorama mode, source badge, richer category header, multi-select actions (including merge / unmerge), and the opt-in update-errors screen all work in both the tabbed pager and the single-list view. The update-errors multi-select with more than one error was flagged for daily-use confirmation. See [ROADMAP.md](../../../ROADMAP.md) for the live status.

## Decisions & tradeoffs

- **Extend Mihon's `LibraryScreenModel`, do not port a parallel model.** Mihon's model already does the full filter / sort / group / search / badge pipeline. A separate ported model would duplicate it and double the per-sync re-application cost. The tradeoff: Reikai's data additions are `// RK` islands inside an upstream file (re-applied on each Mihon sync) rather than isolated in `reikai.*`. Worth it, because the rendering layer (the bulk of the net-new code) does stay isolated.
- **A separate single-list renderer, not a rewrite of Mihon's pager.** The single-list view is a new branch that renders `ReikaiLibraryContent`; Mihon's pager, grids, and settings dialog stay in-tree and merge-able. Every feature must work in both views, which is the standing constraint on all later library work (the dual-view rule).
- **Reuse Mihon's native multi-select, add only merge / unmerge.** Selection state, the selection toolbar, and the standard actions are Mihon's. Reikai only adds the merge / unmerge entries and threads selection through the single-list renderer so it matches in both views. Smaller patch, less to re-sync.
- **Hidden categories via a flag bit, no migration.** "Hidden" is a high bit in `Category.flags` (outside Mihon's sort mask), written through Mihon's existing `UpdateCategory` path, with an optional `// RK` map to a `BackupCategory` proto field for cross-app backup portability. No `categories.sq` edit and no migration slot, so it does not collide with the update-errors `12.sqm`.
- **Synthetic categories for dynamic grouping.** A dynamic group (by tag / author / language) is a `Category` with a negative id and its sort carried in `flags`, so it slots into Mihon's id-agnostic `applySort` / `applyGrouping` unchanged. Collapse state and header keys are computed in the render layer from the name, not stored on the immutable `Category`.
- **Update errors are opt-in and additive.** Default off, hidden from the overflow until enabled, recorded only when the toggle is on, and stored in a table whose foreign key points into `mangas` (nothing in the manga path references it). So the feature is invisible and zero-cost unless a user wants it, and the migration upgrades existing installs cleanly.
- **Dropped / absorbed scope.** Staggered grid (Y5) dropped (needs a cover-ratio metadata subsystem for little gain); the bespoke five-tab settings sheet retired for `// RK` islands in Mihon's own `LibrarySettingsDialog` (which re-types far more cleanly, mirroring how Komikku patches it); search-syntax was a no-op (Mihon's matcher already covers title / author / source / genre / negation / tokens); series-type filter dropped (no clean derivation on Mihon's model). Panorama mode, hidden categories, and the source-icon badge were added in their place. The adult-source / EXH subsystem (the full inverted-lewd source branches) was deferred to a later sprint; only the basic genre-plus-source-name lewd heuristic shipped here.
