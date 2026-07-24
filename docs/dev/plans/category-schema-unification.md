# Category schema unification (content_type discriminator)

## Goal

Retire the forked `novel_categories` table into the shared `categories` table with a `content_type`
column (manga, novel, or universal), so one category axis serves both content types. This is the data
half of the "All" library chip: the content-layer library phase gives All its merged list and dispatch,
this gives it a category axis that can answer what a mixed bucket means.

## Why

With two disjoint category id spaces there is no answer to whether a mixed "Reading" bucket is one
thing or two. `categories._id` and `novel_categories._id` are independent rowid primary keys, so the id
3 exists in both tables meaning different things, and a bare `Long` cannot say which one it names. That
is what makes an All view unrepresentable today, and it is a data-model gap rather than plumbing.

A `content_type` discriminator answers it directly: a category declares itself manga-only, novel-only,
or universal, and a universal "Reading" holds both content types in one bucket. Adapted from tsundoku's
model, which carries the same column on its own categories table, but fitted to Reikai's separate entry
tables (the two join tables stay, one per content type).

Secondary benefit: it drops a parallel repository plus domain-model stack (`NovelCategoryRepository`,
`NovelCategory`, the novel category interactors and screen model), which are near-duplicates of their
manga counterparts.

## Approach

One shared `categories` table gains a `content_type` column (0 = universal, 1 = manga, 2 = novel). The
two junction tables (`mangas_categories`, `novels_categories`) stay as they are, each pointing at the one
category table, so entry storage is untouched and no entry ids move.

A migration moves every `novel_categories` row into `categories`, mints a fresh id for each (the two id
sequences overlap by construction, so novel ids are the ones that move), rewrites every
`novels_categories.category_id` to match, and translates each moved row's sort flags from the novel bit
layout to the manga one (see the flags decision below). The novel-only `novel_order` column is dropped,
not carried: the per-entry manual sort it stored is being retired.

The uncategorized sentinel becomes a single universal row 0. Manga already seeds a real row 0 with a
delete-guard trigger; that row is retyped to `content_type = 0` and novels stop synthesizing their own
Default at render time, reading the shared row 0 instead. `isSystemCategory` stays an `id == 0` check on
both sides, unchanged.

## Decisions (locked)

- **Flags standardize on Mihon's manga layout (Option B), not per-content-type interpretation.** The two
  sides differ in exactly one place: the novel layout stored Downloaded and TrackerMean on swapped type
  values (novel Downloaded `0b100000`, TrackerMean `0b100100`; manga is the reverse). Every other bit is
  identical: the `CATEGORY_SORT_CUSTOMIZED` override bit 0, the direction bit, and the hidden bit
  (`CATEGORY_HIDDEN_MASK`, already a shared constant used by both). The migration translates the two
  swapped type values per moved row; everything else passes through. Going forward the novel library
  reads its flags through the shared `LibrarySort` / `CategorySortOverride` helpers, and
  `NovelLibrarySort`'s reader dissolves. Chosen over keeping two layouts because the whole initiative is
  anti-divergence and a universal category cannot carry two conflicting bit meanings for one sort.
  Guarded by a pinning test (`novelCategoryFlagsToMangaLayout`) written before any row moves.
- **The flag translation is the single silent-failure risk.** Move a novel row without translating and a
  Downloaded-sorted category flips to TrackerMean (or the reverse) with no error and nothing to notice
  until a user sees a wrong order. This is why the pinning test comes first.
- **Single universal row 0, not per-type sentinels.** Row 0 becomes the one universal uncategorized
  bucket; novels drop their two render-time syntheses (which today disagree on flags). Matches tsundoku
  and removes the synthesis drift. Per-type sentinel rows were rejected: they need id 0 shared across two
  junctions or a second reserved id, more surface for no benefit.
- **`novel_order` is retired, not carried.** It stored the per-category manual drag order of library
  entries, a Yokai-era feature the owner never used on either build. The column is dropped with the
  table and its read path (`NovelCategory.novelOrder`, the mapper and repo references) goes with the
  novel-stack retirement. Backups never carried it, so nothing round-trips through it.
- **Category default is `content_type = 1` (manga), not tsundoku's 0.** Existing manga categories are
  manga-typed, not universal; tsundoku defaults to universal only because it has one entries table. New
  categories created through the unchanged `insert` inherit the manga default until the novel path gets
  its own typed insert.
- **Backups do not move.** Neither restorer trusts a raw backup category id as a key: both resolve
  categories by name, mint fresh local ids, and restore membership by matching order then name.
  `BackupNovelCategory` sits at a defaulted proto number, so an old backup keeps decoding. Only what the
  restorer writes changes; the wire format is untouched.
- **Raw category ids that cross a process boundary must be remapped.** Category ids live in preferences
  (default category, and the update/download include and exclude sets, on both content types) and are
  passed into WorkManager data by both update jobs. The data migration rewrites the six novel prefs from
  the in-memory old-to-new id map it builds while moving rows, mirroring the existing
  `CategoryPreferencesCleanupMigration`. The manga prefs do not move (their ids are stable).
- **Entry tables are not touched.** No manga or novel row moves, no entry id changes, and the two
  junction tables stay separate.

## Key files

- Schema: `data/src/main/sqldelight/tachiyomi/data/categories.sq`, `novel_categories.sq` (retired),
  `mangas_categories.sq`, `novels_categories.sq`; new migration under `.../migrations/`.
- Flags: `tachiyomi.domain.library.model.LibrarySort` (manga layout), `reikai.domain.novel.model.NovelLibrarySort`
  (novel layout, its reader dissolves), `reikai.domain.library.CategorySortOverride` (the shared
  override read), `reikai.domain.category.CATEGORY_HIDDEN_MASK` (shared hidden bit), and the new
  `reikai.domain.library.novelCategoryFlagsToMangaLayout` translation helper.
- Manga side: `tachiyomi.domain.category` (repository interface, `Category`, interactors),
  `CategoryRepositoryImpl`, `CategoryScreenModel`.
- Novel side (collapses): `reikai.domain.novel.NovelCategoryRepository`, `NovelCategoryRepositoryImpl`,
  `reikai.domain.novel.model.NovelCategory` (and its `toCategory()`), the novel category interactors,
  and `NovelCategoryScreenModel`.
- Boundary crossings: `LibraryPreferences`, `DownloadPreferences`, `NovelPreferences` (the six novel
  category-id prefs), `LibraryUpdateJob`, `NovelUpdateJob`, and `CategoryPreferencesCleanupMigration`
  (the remap template).
- Backup: `BackupCategory`, `BackupNovelCategory`, `CategoriesBackupCreator`, `CategoriesRestorer`,
  `NovelRestorer`.
- The consumer that unblocks: `reikai.presentation.library.LibraryEngine` (`behaviorFor` refuses a mixed
  view today) and `LibraryCategoryRef` (pairs a category with its content type, defined but unwired).

## Plan

1. **Pin the flag translation.** `novelCategoryFlagsToMangaLayout` plus a test: Downloaded and
   TrackerMean swap, every other type is unchanged, and the customized, direction and hidden bits pass
   through. Guards the migration before any row moves.
2. **Schema.** Add `content_type INTEGER NOT NULL DEFAULT 1` to `categories` (create table, seed row 0 at
   `content_type = 0`, a migration for upgraders that also retypes the existing row 0 to 0). Make
   `getCategory` select explicit columns so the shared mapper is unaffected.
3. **Data migration** (Kotlin, gated on `versionCode` 187 with a mid-cycle bump from 186): move each
   `novel_categories` row into `categories` at `content_type = 2`, swapping the two flag bits, capturing
   old-to-new id; rewrite `novels_categories.category_id`; remap the six novel prefs from that map.
4. **Sentinel.** Drop the novel render-time Default syntheses; read the shared universal row 0.
5. **Retire the novel category stack** (repository, model with `novelOrder`, interactors, screen model,
   DI), routing the novel sort-read path onto the shared `LibrarySort` / `CategorySortOverride` helpers
   and the novel category UI through the shared `Category` stack, `content_type`-filtered.
6. **Wire `LibraryCategoryRef` and lift `behaviorFor(ALL)`'s refusal**, unblocking the All chip.

## Follow-on (separate, after this)

- **Category-preference cleanup (next task).** Some category-id preferences hold ids that are no longer a
  valid category of the right content type: the fold-in migration remapped the six novel update/download
  prefs but not the novel library-filter prefs or `last_used_novel_category`, and a wipe-and-restore mints
  fresh category ids without remapping any category-id pref (Mihon's backup remaps membership by name/order,
  never the filter/default prefs). Measured on the A57 after a restore: `novel_library_filter_categories_exclude
  = [1]` and `last_used_novel_category = 5`, both pointing at ids that are now manga categories. Harmless
  (they filter/preselect nothing) but stale. Fix it in one change so it needs a single migration:
  1. **Cleanup migration**, new, gated `version = 188f` (bump `versionCode` 187 -> 188 so it fires in dev):
     scrub every category-id preference of ids that are not a real category of the right content type. Novel
     prefs keep only content_type-2 ids, manga prefs only content_type-1/0 ids; single-value prefs
     (`default*Category`, `last_used*`) reset to their sentinel when invalid. Lives in the app module, so it
     reaches every pref (`LibraryPreferences`/`DownloadPreferences` in domain, `NovelPreferences`/
     `ReikaiLibraryPreferences` in app). Valid ids come from `CategoryRepository.getAll(MANGA/NOVEL)`. Note
     Mihon's `CategoryPreferencesCleanupMigration` (`10f`) already scrubs the manga *set*-prefs but is
     one-time-passed and does not cover the Reikai filter prefs.
  2. **Extend the delete-scrub to the library-filter prefs** so a delete stops leaving dangling refs. Novel:
     add `novelLibraryFilterCategoriesInclude`/`Exclude` to `NovelCategoryActions.delete`'s pref list. Manga:
     add the scrub in `MangaCategoryActions.delete`, since domain `DeleteCategory` cannot see the app-module
     `ReikaiLibraryPreferences`.
  3. **Restore-side remap** so future restores stop re-introducing stale refs. After restoring categories,
     build the old-id -> new-id map by name and remap the category-id filter/default prefs through it (both
     types). Touches `CategoriesRestorer` (Mihon, `// RK`) and `NovelRestorer` (Reikai); a shared app-module
     remap helper both call. Sequence after both the prefs and the categories are restored.

  Device-verify on the A57: the two stale prefs scrubbed, a filtered-category delete cleans the filter, and a
  backup -> restore keeps filter/default selections valid instead of stale.
- **User-creatable universal categories.** The schema already supports a universal category (`content_type = 0`;
  today only the hidden uncategorized row 0 uses it) and the shared `insert(Category, contentType)` already
  takes a content type, so this is the user-facing half: a content-type selector in the Add/Edit-category dialog
  (All / Manga only / Novels only, tsundoku-style) and a create path that writes `content_type = 0` for All, so a
  universal "Reading" holds both manga and novels. The edit-categories screen shows each category's type. This is
  the category-level counterpart of the "All" library chip (a universal bucket is what a mixed "Reading" means),
  and a co-requisite of it. The manga library reads `content_type IN (0, 1)` and the novel library `IN (0, 2)`,
  so a universal category already surfaces in both once one can be created. Decide whether the edit screen stays
  tabbed (add the selector per tab) or becomes one list with a type column like tsundoku.
- **Category reorder mode**, a Yokai-era Reikai feature to restore for both types on the now-unified
  category screen: a reorder mode toggled from the edit-categories screen that reveals a drag handle plus
  move-to-top and move-to-bottom controls on each category card, confirm or cancel to finish. Built once
  on the shared screen model, so it serves manga and novels without divergence. Uses the existing `sort`
  column; no schema work. Queued in ROADMAP.

## Remaining work (resequenced into two slices)

The plan steps 4-6 above were resequenced once the sentinel turned out to be entangled with the read-caller
retirement (reading row 0 means every `getNovelCategories` caller sees it, including the pickers that must
not). Two slices, each its own device-verified commit:

- **Slice A (management dedup): SHIPPED (`e68e1c572`).** One `CategoryScreenModel` drives both edit-category
  tabs via a Reikai-owned `CategoryActions` seam (`reikai.presentation.category`): a manga adapter over
  Mihon's interactors (unchanged) and a novel adapter over `NovelCategoryRepository`. Retired
  `NovelCategoryScreenModel` and the Insert/Delete/Reorder novel interactors + DI. Verified on device: both
  tabs load, tab-switch works, hiding a novel category persists. Create/rename need a hardware keyboard, not
  yet device-checked (they use the same proven adapter methods).
- **Slice B (sentinel + read-caller retirement): SHIPPED (`876baa087`..`43c287bd0`), device-verified on the
  A57.** `CategoryRepository` is content-type-aware (a defaulted `contentType` param on `getAll`/`getAllAsFlow`/
  `insert`, plus `getCategoriesByNovelId`; `insert` returns the rowid; manga callers unchanged). `GetNovelCategories`
  re-homed to `reikai.domain.category` over the shared repo, returning `Category`; the novel library reads the
  real row 0 and the two Default syntheses are gone; pickers filter row 0 by id as before. Backup create/restore
  and the novel per-category sort read/write moved onto the shared repo, and `NovelLibrarySort` dissolved onto the
  shared `LibrarySort` layout (the write side that the `187f` migration had assumed). `NovelCategory`,
  `NovelCategoryUpdate`, `NovelCategoryRepository` (+impl), `NovelLibrarySort` and `mapNovelCategory` are deleted.
  The novel-delete parity gap closed via a shared `deleteCategoryAndCleanup` helper both types call (unit-tested).
  One bug surfaced and fixed here: the shared edit-categories screen swaps its model per Manga/Novels tab but
  collected events in a `LaunchedEffect(Unit)` that never re-subscribed, so the Novels-tab delete showed no undo
  snackbar and never committed; events is a `SharedFlow` now, keyed on the model (`e47c7898e`).

## Status

Complete, shipped and device-verified on the A57 across `87ccbfe50`..`43c287bd0`: the schema column and
flag-translation test, the cutover (33.sqm moves the rows, repoints the junction, drops the old table; the
Kotlin migration fixes flags and remaps prefs), the category-manager dedup (Slice A), and the sentinel +
read-caller retirement + sort collapse + stack deletion (Slice B). A full wipe-and-restore round-trip verified
novel categories and memberships survive. What remains is the category-preference cleanup under Follow-on above
(its own task), then the "All" chip that this whole initiative unblocks. Researched 2026-07-23, shipped
2026-07-24.

Fixes that landed during the work, worth keeping in mind: during the cutover, novelLibraryView is dropped and
recreated around the junction table-recreate (else the RENAME reparses it mid-migration and crashes, invisible
to verifyDebugDatabaseMigration's JDBC driver); during Slice B, the shared edit-categories screen's event
collector had to move to a `SharedFlow` keyed on the model, since a `LaunchedEffect(Unit)` over a
`receiveAsFlow` channel bound to the first Manga/Novels tab only and dropped the other tab's undo snackbar (so
its delete never committed).
