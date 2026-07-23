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

One shared `categories` table gains a `content_type` column. The two junction tables
(`mangas_categories`, `novels_categories`) stay as they are, each pointing at the one category table, so
entry storage is untouched and no entry ids move.

A migration moves every `novel_categories` row into `categories`, mints a fresh id for each (the two id
sequences overlap by construction, so novel ids are the ones that move), and rewrites every
`novels_categories.category_id` to match.

The uncategorized sentinel needs a ruling: only one row can own id 0, and today manga seeds a real row 0
with a delete-guard trigger while novels synthesize their Default at render time with no row at all.
Either novels keep synthesizing (and `isSystemCategory` stays an id check per content type), or the
sentinel becomes an explicit flag rather than an id check at every site that tests it.

## Key files

- Schema: `data/src/main/sqldelight/tachiyomi/data/categories.sq`, `novel_categories.sq`,
  `mangas_categories.sq`, `novels_categories.sq`.
- Manga side: `tachiyomi.domain.category` (the repository interface, `Category`, and the interactors),
  `CategoryRepositoryImpl`, `CategoryScreenModel`.
- Novel side (the stack that collapses): `reikai.domain.novel.NovelCategoryRepository`,
  `NovelCategoryRepositoryImpl`, `reikai.domain.novel.model.NovelCategory` (note its `toCategory()`,
  which is exactly where a novel id becomes an untagged `Category`), the novel category interactors,
  and `NovelCategoryScreenModel`.
- Backup: `BackupCategory`, `BackupNovelCategory`, `CategoriesBackupCreator`, `CategoriesRestorer`,
  `NovelRestorer`.
- The consumer that unblocks: `reikai.presentation.library.LibraryEngine`, whose `behaviorFor` refuses a
  mixed view today for exactly this reason, and `LibraryCategoryRef`, which pairs a category with its
  content type and is currently defined but unwired.

## Status

Designed, not started. Researched 2026-07-23 (schema census, category call-site census, backup
mechanics, and a tsundoku comparison). Roadmap entry: "Unify the category schema (content_type
discriminator)".

## Decisions & tradeoffs

- **Discriminator on a shared table, not two namespaced tables.** Keeping two tables and making the id
  spaces globally disjoint (offsetting novel ids, or negative ids, or a composite key everywhere) was
  weighed and rejected as the primary approach. Offsetting still leaves the flags and sentinel problems
  unsolved and needs a translation shim at every persistence boundary. Negative ids collide with the
  synthetic dynamic-grouping categories, which already claim the entire negative space
  (`ReikaiDynamicCategory.isDynamic` is an `id < 0` test). A composite key is correct and is what
  `LibraryCategoryRef` encodes, but on its own it only makes a mixed list expressible, it does not let a
  universal category exist, which is the thing that makes All feel like one library rather than two
  lists stapled together.
- **The sort-flags bit layout must be translated during the migration, and failure here is silent.**
  The two sides store the Downloaded and TrackerMean sort bits with swapped values in their respective
  flags columns. Moving rows without remapping those bits silently flips a category between sorting by
  download count and sorting by tracker score, with no error and nothing to notice until a user sees a
  wrong order. This is the single highest-risk detail in the migration and deserves a pinning test
  before any rows move.
- **The novel-order encoding must be preserved.** `novel_categories.novel_order` carries either the
  per-category drag position or a sort-mode char, parsed at read time. It has no manga counterpart on
  the categories table, so it either rides along as a column or its data moves wherever the manga side
  keeps the equivalent.
- **Backups are far less exposed than first assumed.** The earlier concern that this would break old
  backups does not survive reading the restore path: both restorers resolve entries by `(url, source)`
  and mint fresh local ids rather than trusting the ids in the backup, and category membership is
  restored by matching order and name, not by id. `BackupNovelCategory` sits at its own proto number
  with a default, so an old backup keeps decoding unchanged. Only what the restorer writes has to
  change. Old backups keep working, and the backup wire format does not need to move at all.
- **Raw category ids do cross process boundaries, and every one of those has to be remapped.** Category
  ids are persisted in preferences (default category, the update and download category include and
  exclude sets, on both content types) and passed into WorkManager data by both update jobs. A migration
  that only rewrites the database leaves those pointing at the wrong category, which silently changes
  which entries get auto-updated or auto-downloaded.
- **Entry tables are not touched.** No manga or novel row moves, no entry id changes, and the two
  junction tables stay separate. This keeps the migration scoped to categories, and keeps it independent
  of the rejected novels-as-manga design.
