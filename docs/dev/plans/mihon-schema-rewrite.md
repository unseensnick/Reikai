# Mihon schema rewrite and the data-module move

## Goal

Take upstream's database rework (mihon `c67a33f3d` through `2d1d2e4ca`) without losing anything Reikai
keeps beside Mihon's tables: merged series, custom info, the adult-source metadata, novel categories and
the page counts. Duplicate copies of one series from one source are merged into one, for manga and novels.

## Why

Upstream renames its tables and views, folds the three favourite fields into `favorite_at`, removes the
sync scaffold, dedupes entries and chapters, and moves every raw database read into `:data`
(mihon `306befb21`). Nine Reikai tables hold a foreign key into the tables upstream rebuilds, and the
driver checks every foreign key after a migration and refuses to open a database with a violation, so
each of those rows has to be rebuilt by hand in the same `.sqm`. Reikai's own repositories and domain
types also live in `:app`, where upstream no longer lets a database type reach.

## Approach

The work lands as one port in upstream order, with Reikai's preparation first, because upstream
interleaves its database commits with the fixes around them: three restore fixes edit a file the module
move creates, and the rename rewrites the `.sq` files four earlier fixes edit.

1. **Preparation, no behaviour change.** The Reikai domain types and repository interfaces move to
   `:domain` (the novel cover key leaves `reikai.data.coil` for a domain package); the novel chapter
   sync moves behind `NovelChapterRepository`; the Reikai repository implementations and their
   database-only tests move to `:data`; the remaining app-side database reads go behind repositories.
   The restore merge kernel moves to `:domain` so the restore repository upstream puts in `:data` can
   call it.
2. **Upstream order.** `532575e29`, then the scaffold drop, the split update queries, the module move,
   the partial-update lambda, `favorite_at`, the category order commits and the one-transaction chapter
   sync, each with its novel half. The fixes that follow (stub sources, removed stores, the history
   double count, the excluded-scanlator open, track upsert and restore, the ORDER BY queries, the
   signing-key store rules, the store pill, the dependency bumps and the Apollo trackers) sit in their
   upstream positions.
3. **The rename and dedupe** (`93cc07511` with `2d1d2e4ca`) is one Reikai migration. Before the old
   duplicate table goes, every Reikai child row is re-pointed at the surviving entry: the survivor's own
   row wins, otherwise the first copy's by upstream's precedence; one copy supplies the adult-source
   metadata, tags and titles together; merge groups left with fewer than two members are deleted with
   all their child rows, since foreign keys are off inside a migration and nothing cascades; the merged
   chapter cache keeps every row whose chapter and group survive, so only a group the dedupe touched
   restitches. The novel dedupe follows in its own migration, `51.sqm`, which also gives `novels`,
   `novel_chapters` and `novels_categories` their unique indexes.

Restore rules that must hold for both content types live in `reikai.domain.backup` kernels, which the
manga restore repository and `NovelRestorer` both call rather than restating.

## Key files

- `data/src/main/sqldelight/tachiyomi/migrations/`: the Reikai-numbered migrations (never Mihon's
  numbers).
- `domain/src/main/java/reikai/domain/backup/RestoreMergeRules.kt`: the restore kernels both restorers
  call.
- `data/src/main/java/reikai/data/`: the Reikai repositories.
- `data/src/main/java/tachiyomi/data/DatabaseBindings.kt`: the driver and database providers, which
  tests call too rather than restating the adapters.
- `data/src/test/java/reikai/data/migration/SchemaChainMigrationTest.kt`: the migrations run over rows
  seeded into `43.db`, every dedupe case over both content types.

## Status

Complete on `feat/0.4.0`, and the synced base is `2d1d2e4ca`. The preparation (`81e4d65d4`, `cf8245ca9`,
`80dd087cf`, `cb089a37d`, `800c694ea`) and the fixes ported ahead of the chain (`02cb0ff90` to `9c8ef9a09`)
came first; the chain is `71f4cb8dc` to `97690516f`, the novel dedupe `25710ad7f`. Simulated over three
databases (the emulator copy, the seeded snapshot, and a crafted one with duplicates of every kind): no
foreign-key violation, every row delta a merged duplicate, 36 precedence checks and 24 migration mutants
red. On the emulator copy no merge group changes, so none restitches after the upgrade. Not yet run on a
device: an upgrade from a 196 or 197 build with real data is the owner's check.

## Decisions & tradeoffs

- **One port, not two blocks.** Landing the fixes first and the schema later would port three restore
  fixes into a file that does not exist yet and then move them again.
- **Reikai-owned columns keep their names** (`page_count`, `content_type`, the `_id` columns of Reikai
  tables) rather than taking upstream's prefixes.
- **The novel dedupe ships with the manga one**, under the write-once rule.
- **`novels_categories` gets a unique index** on its pair, matching the manga category link table.
- **The app keeps a test-only SQLDelight dependency** for the tests that need a real database and the
  app's backup models; tests that only touch `:data` move with their classes.
- **Migration tests may start from the committed `43.db` snapshot** and run the real migrations, which
  is the only way to seed rows a later migration has to carry (now in database.md).
- **The stitch cache is carried, not wiped.** Wiping it restitched every group on first open after the
  upgrade; carrying the surviving rows leaves the stale checks to find the groups the dedupe changed,
  which the ranking stamp (member ids) and the row count already detect.
- **The `novels_categories` pair index lands in `51.sqm`**, after the novel merge that can bring a pair
  together, rather than in `50.sqm`, which only rebuilds the table against the renamed `category`.
- **State outside the database follows the merge through a record.** `50.sqm` and `51.sqm` write each
  merged-away id, its survivor and its title to `dedupe_merged_ids`, and each merged-away chapter row
  and the kept row with the same url to `dedupe_merged_chapter_ids`, before deleting them, since a
  migration cannot touch files or preferences. Two readers use it, both before it is emptied:
  - `MergedDuplicateCarryMigration` (198) moves a copy's custom cover to the survivor when the survivor
    has none; when both have one the survivor's stays. The copy's file is deleted either way, because
    neither entry table uses AUTOINCREMENT and a new entry could be given the freed id and inherit it. A
    novel's cover may still sit under its pre-186 name, since the 186 re-key sees only surviving rows.
    It then carries the downloads (below) and empties both records, so a second run does nothing, but
    only once every download folder is merged. A folder carry left unfinished (no room, a failed copy
    or rename) keeps both records, and `App` retries it through `retryUnfinishedFolders` on every later launch,
    after `Migrator`, emptying the records once none is left. The retry redoes only the folders, which
    are keyed by title: a cover and a queued download are keyed by the freed id, which a new entry may
    hold by then. A crash during 198 itself needs no retry path, since the version is stamped only after
    the chain completes and the whole carry is safe to run again. Upstream loses these covers.
  - `MigrateMergePrefsToGroupsMigration` (189) maps merged-away ids in the old merge and unmerge prefs
    to their survivors. Every 0.3.2 install runs it after the dedupe, and without the map it drops a
    manual merge naming a copy and lets a same-title group form against an unmerge naming one.
- **Downloads follow the merge by one rule for both types** (`MergedDuplicateDownloads`, called by the
  198 carry). A download folder is named by source and title, so a copy whose title differs from the
  survivor's has its folder renamed in place to the survivor's title when the survivor has no folder of
  its own, through a temporary name for a change of letter case only, as both engines' title renames
  do; both download indexes are then rebuilt. Titles are compared as folder names before the manga
  source is looked up, since that lookup waits for extensions to load. Where several copies merged into
  one survivor, the lowest id goes first and takes the name. The chapter files inside keep their names,
  which come from each chapter's name and url, so they match the survivor's rows wherever the merged
  rows agree on the name.
- **When both copies have a folder, the two are merged by copy and delete** (owner ruling, 2026-09-29;
  `DownloadFolderMerge`, one kernel for both types). Storage (`UniFile`) can rename in place but cannot
  move a file between folders. Each chapter entry of the copy's folder (a page folder or `.cbz`, a
  novel's `.html`) that the survivor's folder has no entry of that name for, compared ignoring case, is
  copied into the survivor's folder under a `_merge_tmp` name, checked, renamed into place, and only
  then deleted from the copy's folder. The check is the same names at every level and every file the
  same length; a content hash was left out because it would read every chapter a second time. A chapter
  the survivor has already stays in both folders: nothing is overwritten, and a chapter not copied is
  never deleted. A half-written download (`_tmp`) is left alone. The copy's folder is deleted only when
  this run moved every entry it held out of it and it then lists empty, because a failed listing also
  reads as empty; a folder with anything left stays, unlisted, and the merge is still counted finished.
  A copy that fails or comes out short is deleted from the survivor's folder and its source kept, and
  counts as unfinished. A `_merge_tmp` left by a crash is deleted at the start of the next run, before
  the survivor's listing is read; it ends in the downloaders' `_tmp`, so neither index lists it, and no
  download name can end in it. Free space is checked first when the volume reports it: the pair is
  skipped, as unfinished, unless the whole of what it would copy leaves the volume above the download
  floor (`hasRoomToCopy`). The survivor's name is looked up again just before the rename, and a rename
  that lands on another name (the document provider picks a free `name (1)` rather than replace) drops
  the copy. The one gap is plain-file storage on a later-launch retry: if the survivor's downloader
  writes the same chapter in the instant between that lookup and the rename, the rename replaces it with
  the checked copy of the same chapter (same name and url).
- **Queued downloads follow the merge by one rule for both types.** Both saved queues are rows of
  entry id, chapter id and order in their own preferences file (`active_downloads`,
  `active_novel_downloads`). The carry re-points a row of a merged-away entry to the survivor and a row
  of a merged-away chapter to the kept row with the same url, keeps the row further up the queue where
  two land on one chapter, and drops a row whose chapter no longer exists, so no row names a deleted
  id. It edits only the rows it read. Both engines restore their queue as they are built, which can be
  before the migrations finish (`App` warms the manga one right after start), so both stores' restore
  waits for `Migrator` first; before this the manga queue dropped such a row and the novel queue kept
  it under the deleted entry id, which then failed.
- **Other state keyed by an entry, checked for the same loss.** Not affected: hidden chapters
  (keyed by source and chapter url), page-list and page-preview caches (rebuilt on the next read), the
  cover colour cache (recomputed from the cover), per-entry reader settings (columns the SQL merges).
  Downloads and queued downloads are handled above.
