---
title: Source migration
description: Migration is the process of moving series between sources without losing progress.
---

# Source migration
Migration moves a series to another source without losing your progress.
It is most often used when a source stops working or another source is more up to date.

These are the steps in **Reikai**.
For migrating in another app, such as Mihon or Komikku, see that app's own docs.

::: warning
Always make sure to have a backup in case anything unexpected occurs.
:::

::: danger
Downloaded chapter(s) do not transfer with migrations.

Migrations leave the old entry's downloads behind unless you tick **Delete downloaded** when confirming. For manga, that only works while the old source is still installed.
Anything left behind stays on your device until you delete it: from the old entry's own page (open <nav to="overflow"> and tap **Clear downloads**) while its source is still installed, or otherwise with a file manager.
:::

## Migration guide

From any of the routes below, migration runs the same three steps: pick where to search, look over what was found, then confirm. Manga and light novels both go through it, with the same screens and search options.

### Starting a migration

::: tip From a whole source
Best when a source has died and you want everything off it.

1. Go to <nav to="main_browse"> and open the **Migrate** tab.
1. Pick the source to move away from. The list shows how many of your entries came from each one, and **All** / **Manga** / **Novels** filters it.
1. Tap the entries you want, or use **Select all** in the toolbar.
1. Tap **Continue**.
:::

::: tip From one series
1. Open a series in your library.
1. Open <nav to="overflow"> and tap **Migrate**.
:::

::: tip From your library
1. Long-press an entry in <nav to="main_library"> to start selecting.
1. Tap the others you want.
1. Open the overflow at the end of the bottom bar and tap **Migrate**. If the bar has room, because the download action is hidden, Migrate sits on the bar itself instead.
:::

Select entries of one type at a time: the migrate action disappears entirely on a selection mixing manga and novels, since one migration moves entries of a single type.

::: info A merged series asks which source to move first
If what you picked is a [merged series](/docs/multi-source), a **Migrate** screen lists its sources with their chapter counts so you can tap the ones to move (what you picked starts selected). Anything left unselected stays where it is. Entries that are not merged skip this step, as does the whole-source route above.
:::

### Choosing where to search

The **Migrate** screen lists your sources under **Selected** and **Available**. Only the selected ones are searched, and dragging reorders which is tried first.

* **Select all** and **Select none** are in the toolbar, and **Select pinned sources** is in its overflow.
* The filter icon opens **Search options**.

::: details Search options
* **Additional keywords (optional)** narrows the search when a title alone finds too much.
* **Hide entries without a match** and **Hide entries without newer chapters** trim the list you have to read.
* **Advanced search mode** breaks the title into keywords for a wider search.
* **Match based on chapter number** picks the match that is furthest ahead, rather than the first by source order.

The app warns about these two, and the warning is worth taking seriously: both send many more searches per entry, which is slow and can get you rate-limited or blocked by a source.
:::

Tap **Continue** when you are happy.

### Looking over the matches

One entry goes straight to a search screen across your selected sources: tap the result you want. Of the search options, only **Additional keywords** applies here; the others shape the **Migration** list below.

Several entries open the **Migration** list, which searches in the background and counts up as it goes. Each row names the entry, the match it found with the move it makes (`current source → new source`), and the latest chapter number on each side (`Latest: 68 → 201`), with the gap shown in red when the match is behind, so you can see at a glance whether a match is worth taking.

Each match starts as a suggestion. Tap the check button on a row to accept it, or tap the double-check icon in the toolbar to accept every match at once. A row's overflow has more:

* **Search manually** opens a search you drive yourself, for when the automatic match is wrong. Picking a result there accepts it for that row, and tapping a source's header browses that one source with its own filters.
* Once a row is accepted, **Migrate now** moves that one entry, and **Copy now** adds the new source while leaving the old entry in place. Both go ahead straight away with your saved data choices.
* **Don't migrate** skips the row.

When every row has been searched, the **Migrate** and **Copy** buttons at the bottom of the screen act on all the accepted rows.

Backing out asks **Stop migrating?** first, so you do not leave the list by accident.

### Confirming

Confirming asks which data to carry over. Tracking always carries; the rest is up to you, and when the old entry has downloads there is an option to delete them afterwards.

