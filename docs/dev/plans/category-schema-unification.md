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
  view today). `LibraryCategoryRef` was meant to pair a category with its content type; the shared table made
  it unnecessary and it was deleted unwired.

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
6. **Lift `behaviorFor(ALL)`'s refusal**, unblocking the All chip. (`LibraryCategoryRef` was dropped instead of
   wired: one shared table means one id space, so there is nothing to disambiguate.)

## Follow-on (separate, after this)

- **Category-preference cleanup. SHIPPED (`f5aa12fe1`..`28d18f1d0`), device-verified on the A57.** Category-id
  preferences that pointed at a category of the wrong content type (or a deleted one) are now cleaned in three
  places, all reading one shared `reikai.domain.category.CategoryIdPreferences` registry (every category-id pref
  per content type, in one list, so the three paths can't drift):
  1. **Cleanup migration** (`CategoryPreferencesContentTypeCleanupMigration`, `version = 188f`, `versionCode`
     bumped 187 -> 188): scrubs each pref against `CategoryRepository.getAll(MANGA/NOVEL)` (novel keeps
     content_type-2/0 ids, manga content_type-1/0), resets an invalid default to its -1 sentinel, and deletes the
     dead `last_used_novel_category` key.
  2. **Delete-scrub** extended to the library and Updates-tab filter prefs on both types. The novel delete passes
     the registry's `novelSets`; the manga delete scrubs the registry's `mangaSets` after Mihon's `DeleteCategory`
     (domain can't see the app-module `ReikaiLibraryPreferences`/`ReikaiSourcePreferences`).
  3. **Restore remap**: manga stays inline in `PreferenceRestorer` (its remapped key list now comes from the
     registry, so it covers the filter/Updates prefs too); novel is remapped in `NovelRestorer.remapCategoryPreferences`
     after the restore's `coroutineScope` settles, since novel categories aren't restored yet when app prefs are.
     Both run the shared `translateCategoryIds` (old-id -> name -> new-id).

  Three corrections to the original plan surfaced during the work: Mihon's `PreferenceRestorer` already remapped
  the manga default/update/download prefs by name (the real gaps were the filter prefs and all novel prefs, not
  "membership only"); `last_used_category` is a library tab index (app-state, never backed up), so it is excluded
  from the scrub; and an old backup could resurrect the dead `last_used_novel_category` key after the migration removed
  it, so `PreferenceRestorer` now also skips that key on restore. The one deliberate manga/novel difference:
  restoring over an existing library, manga unions the backup filter into the current one while novel replaces it
  (novel prefs pass through the raw restore first); on a fresh-install restore they are identical.
- **User-creatable universal categories. SHIPPED except backup** (`f54320f49`..`2caa678b2`), device-verified on
  the A57. A category can now be created
  as universal (`content_type = 0`), manga-only or novel-only, picked from a radio group in the Add-category
  dialog and fixed afterwards. The edit-categories screen became **one list** rather than staying tabbed, and
  each row names its type on a secondary line, following tsundoku. The single list is what makes ordering
  correct: `sort` is one column, and the per-library reads overlap on universal rows, so create/reorder/delete
  had to move onto one authority that renumbers the whole table. `Category` carries `contentType`, the five
  category reads project the column, and the two insert statements collapsed into one that takes it as a
  parameter (the universal value used to fall through to the manga branch and be written as manga-only).
  Mihon's `CreateCategoryWithName`, `ReorderCategory` and `DeleteCategory` each scoped themselves to the
  manga-visible rows and went off-path (see [off-path-manifest.md](../off-path-manifest.md)); the type-agnostic
  `RenameCategory` and `UpdateCategory` stay live.

  **Backup carries the content type. SHIPPED (`de40d9f20`), device-verified.** `BackupCategory` gained it at a fork-reserved `@ProtoNumber(8001)`
  (tsundoku's convention) with a Kotlin default of MANGA, matching both the column default and how a backup
  written before the field has to read. `CategoriesRestorer` now inserts through `CategoryRepository` rather
  than the raw query, which writes that content type instead of forcing manga-only, and returns the new row id
  (the raw query returns rows affected, which every restored `Category` was using as its id).

  **A universal category stays in both backup lists, and that is deliberate.** The obvious-looking fix, emitting
  it once in the manga list, would silently drop novel memberships: `BackupNovel.categories` holds each
  category's `order`, not its id, and `NovelRestorer` resolves that `order` inside `backupNovelCategories`
  alone. Drop the row from that list and the lookup finds nothing. Emitting stays untouched; only restore
  changed. The duplicate row stops being created because `NovelRestorer`'s existing name check reads
  `content_type IN (0, 2)`, so it already sees a universal row once one is actually written as universal, which
  is also why `BackupNovelCategory` needs no content type of its own. Restore order guarantees the manga list
  wins the race: `BackupRestorer` joins the manga category job before anything else launches, and the manga
  list is emitted whenever categories are backed up, independent of whether manga are.

  Name matching moved from `associateBy` to a grouped lookup preferring the same content type and falling back
  to any row with that name. The fallback is what keeps a pre-field backup (every entry reads as manga)
  matching a category the user has since made universal, rather than inserting a duplicate beside it.

  One consequence accepted: the category inserts are no longer wrapped in a single transaction, since
  `CategoryRepositoryImpl.insert` opens its own and nesting them is not worth it. A failure mid-restore can
  leave some categories created. The novel side already behaved this way and restore is not atomic overall.

  Four decisions settled with the owner while building it:
  1. **One list, not tabs.** Ordering decided it: `sort` is a single column, so two per-library renumberings
     fight over a row both libraries can see. One list gives one ordering authority and the collision cannot
     occur. The cost accepted is that dragging a category crosses rows the other library does not show.
  2. **The content type is fixed at creation.** Rename stays name-only, which sidesteps having to migrate
     memberships out of a junction table that no longer matches, for an operation a new category covers.
  3. **Hidden and per-category sort stay shared.** The override is a property of the category and applies
     wherever it is displayed, so a category spanning both libraries has one hidden state and one sort. This
     needed no code: it falls out of the single `flags` column, and both libraries already decode it through
     the same `sortForCategory`.
  4. **Empty categories should be hidden**, levelling manga up to the novel behaviour. Queued in ROADMAP.

  Verified on device across the whole surface: creation of each type, per-type visibility in both libraries and
  both default-category pickers, one drag order (only the moved rows' `sort` changed, the system row kept -1),
  hide and per-category sort applying to both libraries from one row, the both-sides preference scrub on delete
  (no reference to the deleted id survived anywhere) against a one-sided delete leaving the other side intact,
  a mixed-type multi-select with undo, and rename preserving the type.

  One pre-existing behaviour surfaced and is **awaiting a ruling**: the Updates filter renders one section per
  content type, and both sections read a list that includes universal rows, so a category spanning both
  libraries is listed twice there with independent toggles (`Default` doubles the same way). Either it stays two
  independent filters, or a spanning category collapses to one toggle writing both sides.
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
novel categories and memberships survive. The category-preference cleanup under Follow-on above then shipped
(`f5aa12fe1`..`28d18f1d0`, `versionCode` 188), device-verified on the A57 across all three paths (upgrade scrub,
delete-scrub, restore remap + dead-key skip). The user-creatable universal categories and their backup support
then shipped too, both recorded under Follow-on above, so this initiative is complete and the "All" chip it
existed to unblock is no longer waiting on the category axis. Researched 2026-07-23, shipped 2026-07-24.

**Three sort-flag residues the translation does not reach** (audited 2026-07-30, tracked as a roadmap item). The
187f migration translates novel *category rows*, so three carriers of the old layout survive: the global novel
sort preference `novel_library_default_sort`, which was written with the old novel sort flag and is read today as
a manga-layout `LibrarySort`; `NovelRestorer.restoreCategories`, which inserts a backup's category flags verbatim;
and the generic preference restore, which skips only the merge keys. Each can re-present a pre-unification
Downloaded or TrackerMean sort as the other one.

None is safely fixable by a later migration, which is why nothing was added. The translation is a pure swap of
those two values, so it only ever touches flags it cannot disambiguate: a flag carrying either value is equally
consistent with an untranslated old value and a correct current one, `setSort` has written the manga layout since
the unification, and `Backup` carries no format version to discriminate on (only per-entry `version` fields on the
manga and novel rows). 187f was correct precisely because it fired at a version gate where every row was known to
predate the change. A migration firing later would repair one small cohort by corrupting another of similar size,
so the prerequisite is a backup format version, and the user-facing cost meanwhile is re-picking one sort.

Fixes that landed during the work, worth keeping in mind: during the cutover, novelLibraryView is dropped and
recreated around the junction table-recreate (else the RENAME reparses it mid-migration and crashes, invisible
to verifyDebugDatabaseMigration's JDBC driver); during Slice B, the shared edit-categories screen's event
collector had to move to a `SharedFlow` keyed on the model, since a `LaunchedEffect(Unit)` over a
`receiveAsFlow` channel bound to the first Manga/Novels tab only and dropped the other tab's undo snackbar (so
its delete never committed).
