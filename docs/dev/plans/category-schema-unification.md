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

- **Category reorder mode**, a Yokai-era Reikai feature to restore for both types on the now-unified
  category screen: a reorder mode toggled from the edit-categories screen that reveals a drag handle plus
  move-to-top and move-to-bottom controls on each category card, confirm or cancel to finish. Built once
  on the shared screen model, so it serves manga and novels without divergence. Uses the existing `sort`
  column; no schema work. Queued in ROADMAP.

## Status

In progress. Steps 1-2 landing first (they are independent of everything downstream). Researched
2026-07-23, decisions locked 2026-07-24. Roadmap entry: "Unify the category schema (content_type
discriminator)".
