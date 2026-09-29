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
