---
title: Merged series
titleTemplate: Library
description: Fold the same series from several sources into one library entry that reads as one.
---

# Merged series

_Dev records: [merge-system-rebuild.md](dev/plans/merge-system-rebuild.md), [merge-aware-manga-reader.md](dev/plans/merge-aware-manga-reader.md), [merge-component-consolidation.md](dev/plans/merge-component-consolidation.md), [merged-read-state.md](dev/plans/merged-read-state.md). Doc map: [README.md](README.md)._

The same series is often available from several sources.
**Reikai** can fold those into a single library entry that reads as one series, called a merged series, so your library shows what you read rather than how many copies of it you have.
On this page, a merged series' group means the sources behind it.

Merged series work the same way for manga and for light novels.

::: info A series is only merged when you ask
Nothing is merged behind your back.
An entry joins a group when you accept the prompt shown as you add it, when you merge entries yourself, or when you migrate a merged series: the series you migrate to takes the old entry's place in the group, or joins the group beside it if you keep the old one.
A source you remove with the heart or from the library keeps its place in the group, so adding it back puts it straight back in. **Remove from library** in Manage sources splits it out first, so it comes back on its own.
:::

## Grouping series

Grouping is on by default.
Turn it off or back on with **Group series across sources**, in <nav to="library"> under **Merged series**, or in the library display sheet.

With it on, every group renders as one card.
Turning it off expands each group back into its per-source entries and keeps the groups, so turning it on again collapses them exactly as they were.

A merged card is filed under the categories of the source that leads it, and shows once in each of them.
Changing a merged card's categories from the library changes them for every source in the group.
Changing them from the details screen changes only the source the page was opened through.

### Joining a group as you add a series

When you add a series that matches one already in your library, the duplicate dialog offers **Add to existing group**.
Pick the entry it belongs with, and the new copy is added to your library and joined to that group.

::: tip Where the prompt appears
Adding from Browse, global search, a series' own details page, History or Updates, or MangaDex Follows.
:::

The **Add to existing group** option is controlled by **Suggest grouping same-titled series**, in <nav to="library"> under **Merged series**, once for **Manga** and once for **Novels**, and is also hidden while **Group series across sources** is off.
With it off, adding a matching series never offers to group it.

### Reading a merged card

A merged card carries the icons of its grouped sources in the corner, one per source, up to three, then a `+N` for the rest.
When the corner is short of room (a narrow cover, or a long unread count beside it), it shows fewer icons, or just the count.
Turn those off with **Show source icons on merged covers** in the library display sheet, and the card falls back to a plain count.

## Switching source

Open a merged series and a row of chips sits below its details: **All** for the combined list, selected when you open it, then one chip per source.

Tap another chip to read that source's version.
Read and bookmark marks, and the chapter list's sort, filter and display settings, are shared by the whole group, so switching source does not restart anything and every chip lists chapters the same way.

The row keeps up with the group on its own, so a source you add through global search appears without backing out to the library first.

If the source you opened the series through is no longer installed, the **All** view opens WebView and shares from the first installed source in the group's order, and each chapter downloads from an installed source that has it.

### Changing the cover

Tapping the cover shows the cover of whichever source you have selected, so it matches the
page you are looking at. Edit cover and Delete custom cover are offered on the **All** chip and
on the chip of the source the series was opened through, since both show that source's cover.
Other sources' chips hide them.

Your library card shows the cover of the source that leads the group. Opening the series from
the library opens it through that source, so a custom cover set there is the one your library
shows. If you open the series from somewhere else, such as History, the page can belong to
another source, and a custom cover set there stays on that source instead of reaching your
library card.

To change it, switch to **All** and tap the cover there.

## Reading a group

A merged series reads as one list.
The chapter list in the reader is the group's merged list: each chapter once, labelled with the source it comes from. Previous and next follow that list across sources without leaving the reader.
Opened from a source chip, from Updates or from a notification, the reader stays on that one source's chapters.
Manga chapters are paired across sources by chapter number. Novel chapters are paired by title, or by number when a chapter has no title. A chapter the sources name or number differently can show twice, and a manga source's unnumbered extras show only when that source leads the group.

Reading or bookmarking a chapter marks that same chapter on every source in the group.

From the **All** list, the library and History, and in a reader opened from them, each chapter downloads once: a downloaded copy from any source opens without going online. If its copy comes from a source you have uninstalled, downloading or reading it uses another installed source's copy, when one has that chapter. Under a source chip and in Updates, only that row's own copy counts.

A chapter that no installed source can fetch in that view shows no download button.

While **Share trackers across merged sources** is on in <nav to="tracking">, which it is by default, the group has one tracker binding rather than one per source; [Tracking](/docs/guides/tracking) explains how it behaves when you merge or split.

## Merging entries yourself

The add-time prompt matches on title, or on a tracker entry the series you are adding is already bound to. A series you have not tracked yet has no binding when you add it, so two romanizations of one series ("Kaijuu 8-gou" and "Kaiju No. 8") usually never meet.
Merge those by hand.

::: tip How to merge
1. Long-press an entry in <nav to="main_library"> to start selecting.
1. Tap the other entries you want with it.
1. Tap <icon name="merge"> in the bar along the bottom.
:::

The selection bar draws its actions as icons with no text beside them, so look for the shape rather than the word.
The merge icon only appears once you have selected two or more entries **of the same type**, since a group is either manga or novels, never a mix.
It is also hidden while **Group series across sources** is off.

The selected entries become one card and share one chapter list, read and bookmark marks, and chapter settings from then on. Moving the card to another category moves every source in it.

## Splitting a group up

Splitting a source out returns it to a standalone library entry and leaves the rest of the group merged.

::::tabs
== From the chip row
Long-press the source's chip on the details screen and confirm **Split**.

Quickest when you are already looking at the chip row. Shows an undo snackbar.
== From Manage sources
On the details screen, open <nav to="overflow"> and tap **Manage sources**, then tap the split icon on that source's row.

Easier than a long-press on a small screen. Shows an undo snackbar.
== From the library
Long-press a merged entry in <nav to="main_library"> and tap <icon name="unmerge">, which dissolves that whole group rather than splitting one source out of it.

The icon appears whenever your selection includes a merged entry. No undo.
== Every group at once
**Clear all merges** in <nav to="advanced">, once for manga and once for novels, splits every group you have back into separate entries as soon as you tap it, with no confirmation.
::::

::: danger Two of these cannot be undone
Splitting from the chip row or from Manage sources offers an undo snackbar. Unmerging from the library does not, and neither does **Clear all merges**, which dissolves every group of that content type at once. Rebuilding after either means merging each group by hand again.
:::

Groups are saved in your backups, so they survive a backup and restore.

## Manage sources

On the details screen, open <nav to="overflow"> and tap **Manage sources** to see every source grouped with the entry you have open.

- **Drag to reorder.** The top row leads the group's combined chapter list and carries a **Primary** badge. Reordering applies immediately, and overrides the global **Preferred sources** ranking for this group only.
- **Reset order** drops that override, so the group falls back to the global ranking again.
- **Split** detaches a source, the same as long-pressing its chip.
- **Remove from library** takes a source out of your library, with an **Undo**. Once the Undo is gone, it offers to delete that source's downloads, as the heart does.
- **Remove all from library** unfavorites every source in the group.

Long-press a row to select several sources and split or remove them together.

The global ranking those first two items refer to is **Preferred sources**, in <nav to="library"> under **Merged series**.
It decides which source leads a merged chapter list when a group has no order of its own.

## Removing a merged series

The heart on the details screen removes what the page is showing.
Under a source chip it removes that one source, and the rest of the group stays in your library.
Under **All** it asks first, with an **All grouped sources (N)** checkbox: keep it ticked to remove every source, or untick it to remove only the source you opened the page from.
If the sources that left have downloads, it then offers to delete them.

In the library, select the entry and delete it.
When the selection includes a merged card, the Remove dialog has the same **All grouped sources (N)** checkbox.

::: warning That checkbox starts ticked
Removing a merged series removes every source behind it unless you untick it first.
If you untick it, the series stays in your library under the sources that remain.
:::
