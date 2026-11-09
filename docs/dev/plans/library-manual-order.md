# Library manual order

## Goal

A **Manual** library sort whose order the user sets by long-pressing a cover and moving it, within a category, for manga and novels alike, in both library views and every display mode. Long-press and release still selects, as today. **Reset manual order** puts a category back to where it started, and each drop offers **Undo**. Requested in unseensnick/Reikai#86.

## Why

Users keep a few series in a deliberate order (reading next, favourites first) that no computed sort can express. Only Yokai had this (`LibrarySort.DragAndDrop`), on a View-based library; no Compose reference implements dragging library tiles, so this is built on the reorder library Reikai already ships.

## Approach

**The gesture.** Each tile is a reorderable item (`sh.calvin.reorderable`, already in the catalog) whose whole surface is a long-press drag handle. The tile's own long-press handler is switched off, since a handler there takes the long press and the drag never starts. A press that turns into a long press sets a flag, cleared at the start of every new press, and the tap its release also reports is dropped while the flag is set; otherwise a long press and release opens the series. A long press released within touch slop of where it started selects, by the same range-select call the long press makes today; a press that travelled further is a drag, even if no tile swapped places (releasing in the system gesture strip is the case that showed this).

**Where it is offered.** Only on a real category (`LibraryBucket.realCategory`) whose sort is Manual, and only while the whole category is on screen: the All content chip, no active filter, no search query. Elsewhere tiles behave as today. Dynamic groupings never offer Manual, since they have no fixed membership.

**Both views.** The paged view's three containers (`LibraryCompactGrid`, `LibraryComfortableGrid`, `LibraryList`) gain item keys, which the reorder state needs, and the same item wrapper. The one-list view (`ReikaiLibraryContent`) is one grid of every category's header and tiles; a move lands only on a tile of the same section, so a tile never crosses a header, and a series shown in two categories has a separate key per section. Edge auto-scroll comes with the library. The wrapper is written once and called from both views.

**While dragging** a local copy of the category's list moves under the finger, and the order is written once on release, as `rememberSettledReorder` does for lists (it takes only a list state today, so it gains a grid form or a sibling).

**Storage.** A Reikai-owned table, `library_manual_order(content_type, entry_id, category_id, position)`, keyed like `dedupe_merged_ids` (content type 0 manga, 1 novel). Per category, because a series can sit in several categories; category 0 for Default, which has no link row. A merged series writes the same position on every member, since the tile's lead row can change after a restitch. A drop renumbers the whole category in one transaction across both content types, since under All one category mixes them.

**The sort.** `Manual` is a new `LibrarySortMode` value in a free slot of the type mask, listed by `librarySortTypes`, ordering by position. A series with no position (added after the order was set) goes last, by the category's starting sort. Switching a category to Manual first writes positions from the order it shows at that moment, so nothing jumps, and records that starting sort for Reset.

**Reset and Undo.** Reset deletes the category's positions and rewrites them from the recorded starting sort. Undo restores the positions the drop replaced, held in memory until the snackbar goes.

**Backups.** A new per-entry field on `BackupManga` and `BackupNovel` in the same commit: a list of (category order, position) pairs, since a restore maps categories by order value (`MangaRestorer`). Default uses a sentinel order.

## Key files

- `domain/src/main/java/tachiyomi/domain/library/model/LibrarySortMode.kt`: the type mask and its free values.
- `domain/src/main/java/reikai/domain/library/LibrarySortComparator.kt`: `librarySortComparator`, also called by `StatsSeriesViewModel`.
- `domain/src/main/java/reikai/domain/library/CategorySortOverride.kt`: `canOverrideSort`, which refuses Default today.
- `app/src/main/java/reikai/presentation/library/LibrarySortOptions.kt` and `LibrarySettingsSheet.kt`: the sort list and its labels.
- `app/src/main/java/reikai/presentation/library/LibraryItemCell.kt` and `eu/kanade/presentation/library/components/CommonMangaItem.kt` (`GridItemSelectable`): where the tile's click and long press are wired.
- `app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt`, `LibraryComfortableGrid.kt`, `LibraryList.kt`: the paged view's containers.
- `app/src/main/java/reikai/presentation/library/ReikaiLibraryContent.kt`: the one-list grid.
- `app/src/main/java/reikai/presentation/library/LibraryEngine.kt` and `LibraryBucket.kt`: assembly, buckets and the per-category sort.
- `app/src/main/java/reikai/presentation/library/MangaMergeCollapse.kt`, `novels/NovelMergeCollapse.kt`: which member a merged tile carries.
- `app/src/main/java/reikai/presentation/components/SettledReorder.kt`: write-once-on-release.
- `data/src/main/sqldelight/tachiyomi/data/dedupe_merged_ids.sq`: the keyed-table pattern; the new table gets the next-numbered migration after `70.sqm`.
- `BackupManga.kt`, `BackupNovel.kt`, `MangaRestorer.kt`, `NovelRestorer.kt`: the backup field and its restore.

## Decisions & tradeoffs

- **Long-press and move first; a Reorder mode only if the gesture cannot be made reliable** (owner ruling). The spike on a Fold passed drag, select on release, category swipe, edge auto-scroll and the section boundary in both views.
- **A Reikai-owned table rather than a column on Mihon's link tables**: no Mihon patch to carry, one table for both content types, and Default needs a row the link tables do not have.
- **Yokai's id list on the category is not reused**: a bare id is ambiguous when a manga and a novel share one, and a list rewrites whole on every drop.
- **Drag only with the whole category visible**: a drop between two visible tiles under a filter could land anywhere among the hidden ones between them; category reorder makes the same call.
- **Default can be Manual on its own** (owner ruling): `canOverrideSort` lets Default take Manual as its per-category sort, and no other override; Default is where most libraries keep most series.
- **Statistics lists use the starting sort, not the positions** (owner ruling): a Statistics list mixes categories, so a per-category order has nothing to say there.
- **No drag while a selection is open** (owner ruling): in selection mode a long press extends the selection, as today.
