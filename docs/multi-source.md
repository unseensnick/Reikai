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

::: info Same titles group on their own
Series you add from different sources under the same title are grouped automatically. You can also merge entries yourself.
:::

## Grouping series

**Merge same-title series across sources** <Badge type="info" text="On" />, on the **Display** tab of the library display sheet, groups series whose titles match, ignoring case. For light novels, **Also require matching author** <Badge type="info" text="On" /> only groups two novels when their author matches too.

Every group renders as one card.

### Reading a merged card

A merged card carries the icons of its grouped sources in the corner, up to three, then a `+N` for the rest.
Turn those off with **Show source icons on merged covers** in the library display sheet, and the card falls back to a plain count.

## Switching source

Open a grouped series and a row of chips sits below its details: **All** for the combined list, selected when you open it, then one chip per source.

Tap another chip to read that source's version.
Chapters, progress and library state stay with the group, so switching source does not restart anything.

## Reading a group

A merged series reads as one.
The chapter list in the reader holds every source's chapters together, each labelled with where it came from, and the previous and next controls run across the whole group: the end of one source's chapters flows into the next without leaving the reader.

Underneath, each chapter still downloads, marks read and updates trackers through its own source, so the group reads as one series while staying correct per source.

## Merging entries yourself

Automatic grouping matches on title, so two romanizations of one series ("Kaijuu 8-gou" and "Kaiju No. 8") never meet.
Merge those by hand.

::: tip How to merge
1. Long-press an entry in <nav to="main_library"> to start selecting.
1. Tap the other entries you want with it.
1. Tap <icon name="merge"> in the bar along the bottom.
:::

The selection bar draws its actions as icons with no text beside them, so look for the shape rather than the word.
The merge icon only appears once you have selected two or more entries **of the same type**, since a group is either manga or novels, never a mix.

The selected entries become one card and share a chapter list, progress and library state from then on.

## Splitting a group up

Splitting a source out returns it to a standalone library entry and leaves the rest of the group merged.

::::tabs
== From the chip row
Long-press the source's chip on the details screen and confirm **Split**.

Quickest when you are already looking at the chip row. Shows an undo snackbar.
== From Manage sources
On the details screen, open <nav to="overflow"> and tap **Manage sources**, tick the sources, then tap **Split**.

Easier than a long-press on a small screen. Shows an undo snackbar.
== From the library
Long-press a merged entry in <nav to="main_library"> and tap <icon name="unmerge">, which dissolves that whole group rather than splitting one source out of it.

The icon appears whenever your selection includes a merged entry. No undo.
== Every group at once
In <nav to="advanced">, **Clear manual merges** undoes every group you made by hand and leaves same-title grouping alone. **Separate all merged series** splits every group, same-title ones included. Novels have their own pair: **Clear manual novel merges** and **Separate all merged novels**.
::::

::: danger Two of these cannot be undone
Splitting from the chip row or from Manage sources offers an undo snackbar. Unmerging from the library does not, and neither do **Clear manual merges** or **Separate all merged series**, which act on every group of that content type at once. Rebuilding after either means merging each group by hand again.
:::

Groups are saved in your backups, so they survive a backup and restore.

## Manage sources

On the details screen, open <nav to="overflow"> and tap **Manage sources** to see every source grouped with the entry you have open.

- Tick one or more sources, then tap **Split** to detach them, the same as long-pressing a chip.
- **Remove from library** unfavorites the ticked sources.
- **Remove all from library** unfavorites every source in the group, which is the only way to remove a whole group from the details screen.

Which source leads a merged chapter list is decided by **Preferred sources**, in <nav to="library"> under **Sources**.

## Removing a grouped series

The heart on the details screen only ever adds or removes the one source you are viewing.

To remove more, select the entry in your library and delete it.
When the selection includes a merged card, the Remove dialog gains an **All N grouped sources** checkbox.

::: warning That checkbox starts unticked
Leave it and only the leading source is removed. The others stay in your library, grouped out of sight, so tick it to remove the whole series.
:::
