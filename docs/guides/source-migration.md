---
title: Source migration
titleTemplate: Guides
description: Migration is the process of moving series between sources without losing progress.
---

# Source migration
Migration is the process of moving series between sources without losing progress. This is most often used when a source is no longer accessible or another source is more up-to-date.

::: warning
Always make sure to have a backup in case anything unexpected occurs.
:::

::: danger
Downloaded chapter(s) do not transfer with migrations.

Migrations with downloaded chapter(s) may leave the download behind.
You will need to remove these manually with a file manager.
:::

## Migration guide

::::tabs
==Reikai
However you start it, migration runs the same three steps: pick where to search, look over what was found, then confirm. It works the same for manga and for light novels.

### Starting a migration

::: tip From a whole source
Best when a source has died and you want everything off it.

1. Go to <nav to="main_browse"> and open the **Migrate** tab.
1. Pick the source to move away from. The list shows how many of your entries came from each one, and **All** / **Manga** / **Novels** filters it.
1. Tap the entries you want, or use **Select all** in the toolbar.
1. Tap **Continue**.
:::

::: tip From one series
1. Open the series.
1. Open <nav to="overflow"> and tap **Migrate**.
:::

::: tip From your library
1. Long-press an entry in <nav to="main_library"> to start selecting.
1. Tap the others you want.
1. Open the overflow at the end of the bottom bar and tap **Migrate**.
:::

::: info Grouped entries ask which source to move first
If what you picked is a [merged group](/docs/multi-source), a **Migrate** screen lists its sources with their chapter counts so you can choose the one to move. Everything else is left where it is. Entries that are not grouped skip this step, as does the whole-source route above.
:::

### Choosing where to search

The **Migrate** screen lists your sources under **Selected** and **Available**. Only the selected ones are searched, and dragging reorders which is tried first.

* **Select all** and **Select none** are in the toolbar, and **Select pinned sources** is in its overflow.
* The sliders icon opens **Search options**.

::: details Search options
* **Additional keywords (optional)** narrows the search when a title alone finds too much.
* **Hide entries without a match** and **Hide entries without newer chapters** trim the list you have to read.
* **Advanced search mode** breaks the title into keywords for a wider search.
* **Match based on chapter number** picks the match that is furthest ahead, rather than the first by source order.

The app warns you about the last two, and means it: both are slow and hit sources hard enough to get you rate-limited or blocked.
:::

Tap **Continue** when you are happy.

### Looking over the matches

One entry goes straight to a search screen: tap the result you want.

Several entries open the **Migration** list, which searches in the background and counts up as it goes. Each row names the entry, the move it found (`current source → match`), and how the chapter counts compare (`Latest: 68 → 201`), so you can see at a glance whether a match is worth taking.

Tap the double-check icon in the toolbar to accept every match at once, or use a row's overflow:

* **Search manually** opens a search you drive yourself, for when the automatic match is wrong. Tapping a source's header there browses that one source with its own filters.
* **Migrate now** moves that one entry, and **Copy now** adds the new source while leaving the old entry in place.
* **Don't migrate** skips the row.

Backing out asks **Stop migrating?** first, so nothing is half-done by accident.

### Confirming

Confirming asks which data to carry over. Tracking always carries; the rest is up to you, and there is an option to delete the old entry's downloads afterwards.

==TachiyomiJ2K
### Migrating multiple Series {#migrating-multiple-series-j2k}

1. Tap **Settings** -> **Sources** -> **Source migration**.
1. Select the **Source** you'd like to migrate _from_ and select **All**.
1. Select the **Sources** that you'd like to migrate _to_ and search by and tap the arrow at the bottom right.
1. Choose which data you want to transfer over.
1. Wait until all your **Series** is found and hit the done at the top and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing Overflow -> **Search manually**.

### Migrating a single Series {#migrating-a-single-series-j2k}

1. Tap into a **Series** in your Library.
1. Tap **Overflow** -> **Migrate**.
1. Select the **Sources** you'd like to search and migrate _to_ and hit the arrow at the bottom right.
1. Wait until it is found and select _done_ in the top right and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing Overflow -> **Search manually**

==TachiyomiSY
### Migrating from Library {#migrating-from-library-sy}

1. Tap into **Library**.
1. Tap **Overflow** -> **Source migration**.
1. Select the **Source** you'd like to migrate _from_ and select **All**.
1. Select the **Sources** that you'd like to migrate _to_ and search by and tap the arrow labeled Migrate at the bottom right.
1. Choose which data you want to transfer over.
1. Wait until all your **Series** is found and hit the done at the top and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing **Overflow** -> **Search manually**.

### Migrating from Source {#migrating-from-source-sy}

1. Tap into Browse on the bottom navbar.
1. Press the Migrate tab at the top next to Extensions.
1. Select the **Source** that you'd like to migrate _from_.
1. Select the **Source** you'd like to migrate _from_ and select **All**.
1. Select the **Sources** that you'd like to migrate _to_ and search by and tap the arrow labeled Migrate at the bottom right.
1. Choose which data you want to transfer over.
1. Wait until all your **Series** is found and hit the done at the top and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing **Overflow** -> **Search manually**.

==TachiyomiAZ
### Instructions {instructions-az}

1. Tap into **Library**.
1. Tap **Overflow** -> **Source migration**.
1. Select the **Source** you'd like to migrate _from_ and select **All**.
1. Select the **Sources** that you'd like to migrate _to_ and search by and tap the arrow at the bottom right.
1. Choose which data you want to transfer over.
1. Wait until all your **Series** is found and hit the done at the top and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing **Overflow** -> **Search manually**.

==Yokai
### Migrating multiple Series {#migrating-multiple-series-yokai}

1. Tap **Settings** -> **Sources** -> **Source migration**.
1. Select the **Source** you'd like to migrate _from_ and select **All**.
1. Select the **Sources** that you'd like to migrate _to_ and search by and tap the arrow at the bottom right.
1. Choose which data you want to transfer over.
1. Wait until all your **Series** is found and hit the done at the top and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing Overflow -> **Search manually**.

### Migrating a single Series {#migrating-a-single-series-yokai}

1. Tap into a **Series** in your Library.
1. Tap **Overflow** -> **Migrate**.
1. Select the **Sources** you'd like to search and migrate _to_ and hit the arrow at the bottom right.
1. Wait until it is found and select _done_ in the top right and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing Overflow -> **Search manually**

==Komikku
### Migrating from Library {#migrating-from-library-komikku}

1. Tap into **Library**.
1. Tap **Overflow** -> **Source migration**.
1. Select the **Source** you'd like to migrate _from_ and select **All**.
1. Select the **Sources** that you'd like to migrate _to_ and search by and tap the arrow labeled Migrate at the bottom right.
1. Choose which data you want to transfer over.
1. Wait until all your **Series** is found and hit the done at the top and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing **Overflow** -> **Search manually**.

### Migrating from Source {#migrating-from-source-komikku}

1. Tap into Browse on the bottom navbar.
1. Press the Migrate tab at the top next to Extensions.
1. Select the **Source** that you'd like to migrate _from_.
1. Select the **Source** you'd like to migrate _from_ and select **All**.
1. Select the **Sources** that you'd like to migrate _to_ and search by and tap the arrow labeled Migrate at the bottom right.
1. Choose which data you want to transfer over.
1. Wait until all your **Series** is found and hit the done at the top and you're done.

    > If a series is not found, or is wrong you can manually search it by pressing **Overflow** -> **Search manually**.
::::
