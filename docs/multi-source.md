---
title: Multi-source grouping
titleTemplate: Guides
description: Fold the same series from several sources into one library entry that reads as one.
---

# Multi-source grouping

_Dev records: [merge-system-rebuild.md](dev/plans/merge-system-rebuild.md), [merge-aware-manga-reader.md](dev/plans/merge-aware-manga-reader.md), [merge-component-consolidation.md](dev/plans/merge-component-consolidation.md), [merged-read-state.md](dev/plans/merged-read-state.md). Doc map: [README.md](README.md)._

The same series is often available from several sources.
**Reikai** can fold those into a single library entry that reads as one series, so your library shows what you read rather than how many copies of it you have.

Grouping works the same way for manga and for light novels.

::: info Grouping only happens when you ask for it
Nothing is grouped behind your back.
An entry joins a group when you accept the prompt shown as you add it, or when you merge entries yourself.
:::

## Grouping series

Turn grouping on with **Group series across sources**, in <nav to="library"> under **Sources**, or in the library display sheet.

With it on, every group renders as one card.
Turning it off expands each group back into its per-source entries and keeps the groups, so turning it on again collapses them exactly as they were.

Grouping is per-category, so a series filed in two categories still shows once in each.

### Joining a group as you add a series

When you add a series that matches one already in your library, the duplicate dialog offers **Add to existing group**.
Pick the entry it belongs with, and the new copy is added to your library and joined to that group.

::: tip Where the prompt appears
Adding from Browse, global search, History, or MangaDex Follows.
:::

That prompt is controlled by **Suggest grouping same-titled series**, in <nav to="library"> under **Sources**, once for **Manga** and once for **Novels**.
With it off, adding a matching series never offers to group it.

### Reading a merged card

A merged card carries the icons of its grouped sources in the corner, up to three, then a `+N` for the rest.
Turn those off with **Show source icons on merged covers** in the library display sheet, and the card falls back to a plain count.

## Switching source

Open a grouped series and a row of source chips sits in the header, under the cover, with the one you are reading highlighted.

Tap another chip to read that source's version.
Chapters, progress and library state stay with the group, so switching source does not restart anything.

The row refreshes on its own whenever you come back to the details screen, so a source you just added through global search appears without backing out to the library first.

## Reading a group

A merged series reads as one.
The chapter list in the reader holds every source's chapters together, each labelled with where it came from, and the previous and next controls run across the whole group: the end of one source's chapters flows into the next without leaving the reader.

Underneath, each chapter still downloads, marks read and updates trackers through its own source, so the group reads as one series while staying correct per source.

## Merging entries yourself

The add-time prompt matches on title, so two romanizations of one series ("Kaijuu 8-gou" and "Kaiju No. 8") never meet.
Merge those by hand.

::: tip How to merge
1. Long-press an entry in <nav to="main_library"> to start selecting.
1. Tap the other entries you want with it.
1. Tap **Merge** in the bottom bar.
:::

The selected entries become one card and share a chapter list, progress and library state from then on.

## Splitting a group up

Splitting a source out returns it to a standalone library entry and leaves the rest of the group merged.
Every route shows an undo snackbar, so a mistaken tap is recoverable.

::::tabs
== From the chip row
Long-press the source's chip on the details screen and confirm **Split**.

Quickest when you are already looking at the chip row.
== From Manage sources
On the details screen, open <nav to="overflow"> and tap **Manage sources**, then pick the source and tap **Split**.

Easier than a long-press on a small screen.
== Every group at once
**Clear all merges** in <nav to="advanced">, once for manga and once for novels, splits every group you have back into separate entries.
::::

Groups are saved in your backups, so they survive a backup and restore.

## Manage sources

On the details screen, open <nav to="overflow"> and tap **Manage sources** to see every source grouped with the entry you have open.

- **Drag to reorder.** The top row leads the group's combined chapter list and carries a **Primary** badge. Reordering applies immediately, and overrides the global **Preferred sources** ranking for this group only.
- **Reset order** drops that override, so the group falls back to the global ranking again.
- **Split** detaches a source, the same as long-pressing its chip.
- **Remove from library** unfavorites a source outright, which deletes its downloaded chapters and covers.
- **Remove all from library** unfavorites every source in the group, which is the only way to remove a whole group from the details screen.

Long-press a row to select several sources and split or remove them together.

The global ranking those first two items refer to is **Preferred sources**, in <nav to="library"> under **Sources**.
It decides which source leads a merged chapter list when a group has no order of its own.

## Removing a grouped series

The heart on the details screen only ever adds or removes the one source you are viewing.

To remove more, select the entry in your library and delete it.
When the selection includes a merged card, the Remove dialog gains an **All grouped sources (N)** checkbox.

::: warning That checkbox starts ticked
Removing a merged card removes every source behind it unless you untick it first.
The alternative was worse: removing only the leading source leaves the others in your library but collapsed out of sight, so the entry looks half-deleted.
:::
