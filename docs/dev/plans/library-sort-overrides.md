# Library sort: global sort with per-category overrides

## Goal

When "Per-category setting for sort" (`categorizedDisplaySettings`) is on, the library uses one global sort that every category follows, except categories the user explicitly sorts (overrides). Changing the global re-sorts the non-overridden categories; a "Reset to global sort" action clears an override; turning the setting off makes everything follow the global again. Both manga and novel libraries.

## Why

The old behavior was broken and inconsistent. In the single-list (show-all) view the toolbar "global" sort wrote to a stale `activeCategory` (there are no tabs to update it), so it only ever re-sorted one category. Novels had a `CUSTOMIZED` override bit but their header label/arrow ignored it (labels disagreed with the actual ordering), and manga had no override concept at all (`Category.sort` is a raw flag decode). A 2026-07-14 deep code-research pass (five explorer agents plus inline verification) traced all of this; the user then specified the intended model (global + overrides + reset), so it was rebuilt properly rather than patched.

## Approach

A category follows the global sort unless a `CUSTOMIZED` sentinel bit (bit 0 of its flags) marks it as an override, in which case it uses its own decoded sort. Novels already had this bit; manga gained the equivalent. The read is override-aware; the write sets the bit on a per-category sort and sets only the global pref on a global sort (no more brute-force `updateAllFlags`); turning the setting off clears the bit on every category.

- **Manga override bit + read:** `reikai.domain.library.CategorySortOverride` (`CATEGORY_SORT_CUSTOMIZED = 0b1`, and `sortForCategory(flags, global)`), the manga twin of `NovelLibrarySort.CUSTOMIZED` / `forCategory`. Lives in the domain module (not app, like the hidden bit) because the write/reset interactors are Mihon domain interactors. Only three manga read sites use it (`LibraryScreenModel.applySort`, the shared header, the Sort dialog); `Category.sort` itself stays a raw decode (novels depend on it).
- **Write/reset:** `SetSortModeForCategory` OR-in `CUSTOMIZED` on a per-category write, sets only `sortingMode` on a global write. `ResetCategoryFlags` clears the bit on all categories (new `categories.sq` `clearSortOverrides` query) instead of flattening flags to the global. Both fired from the same settings-toggle handler as before, alongside the novel `ResetNovelCategoryFlags`.
- **Toolbar to global (Model A):** the toolbar sort scopes to the global (manga passes a null category, novel passes `UNCATEGORIZED_ID`), not the stale active category. Per-category overrides are set from each category's header sort in the single-list view. (Known limitation: the tabbed pager has no per-category sort control, overrides are set from show-all.)
- **Shared header decode:** `ReikaiLibraryCategoryHeader` renders a `sortLabel` + `sortAscending` (primitives), and the caller computes the effective sort (override or global) per content type, so labels and arrows always match the ordering and neither type's raw bits are touched by the shared header.
- **Reset UI:** a shared `ResetToGlobalSortItem` (a divider plus a restore icon, distinct from the sort modes) in both Sort dialogs, shown only when the category is overridden.
- **Re-sort trigger:** the manga sort pipeline now also observes `sortingMode.changes()` (it previously relied on the dropped `updateAllFlags` write to re-emit); novels already observed `novelLibraryDefaultSort`.
- **Migration:** existing per-category manga sorts are concrete flags with no bit, so a one-time migration (`SetupCategorySortOverrideMigration`, version 183f, versionCode bumped 182 -> 183) marks categories whose decoded sort differs from the global as overrides, only for categorized-display users (off users already follow the global). Backup round-trips free (`BackupCategory.flags` is a verbatim `Long`); novels need no migration.

## Key files

- Domain: `domain/.../reikai/domain/library/CategorySortOverride.kt`; `tachiyomi/domain/category/interactor/SetSortModeForCategory.kt`, `ResetCategoryFlags.kt`, `repository/CategoryRepository.kt`.
- Data: `data/.../categories.sq` (`clearSortOverrides`), `CategoryRepositoryImpl.kt`.
- Manga UI: `LibraryScreenModel.applySort` + the sort pipeline; `LibrarySettingsDialog` SortPage; `LibrarySettingsScreenModel.resetSort`.
- Shared UI: `reikai/presentation/library/ReikaiLibraryCategoryHeader.kt`, `ReikaiLibraryContent.kt`, `ResetToGlobalSortItem.kt`, `LibraryTab.kt` (toolbar routing + effective-sort header wiring).
- Novel UI: `NovelLibraryScreenModel.setSort`/`resetSort`, `NovelLibrarySettingsDialog` SortPage, `ResetNovelCategoryFlags`.
- Migration: `mihon/core/migration/migrations/SetupCategorySortOverrideMigration.kt`; `app/build.gradle.kts` versionCode 183.

## Status

Shipped (`b90344562`), Fold-verified (both types, the upgrade-migration path, the reset action, live global-sort updates). Grew out of round-2 Phase 7 (see [content-parity-drift-and-collapse.md](content-parity-drift-and-collapse.md)).

## Decisions & tradeoffs

- **Add a `sortForCategory` helper, don't change `Category.sort`:** it has only three manga callers and novels rely on its raw decode; changing its meaning would corrupt novels and leak prefs into a domain extension.
- **Model A (toolbar = global in both views):** simplest and matches the show-all workflow; the tabbed pager keeps no per-category sort entry (set overrides from show-all). Model B (view-dependent toolbar) was considered and declined.
- **Migration only for categorized-display users:** off users already correctly follow the global via the bit-less read, so migrating them would wrongly mark their categories as overrides.
- **`CUSTOMIZED` = bit 0 on both types:** below Mihon's sort/direction bits (2-6) and the Reikai hidden bit (7), no collision, one shared mental model.
