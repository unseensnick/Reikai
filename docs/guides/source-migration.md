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
However you start it, you pick where to search, look over the matches, then confirm. Manga and light novels use different screens for the last two steps, described separately below.

### Starting a migration

::: tip From a whole source
Best when a source has died and you want everything off it.

1. Go to <nav to="main_browse"> and open the **Migrate** tab.
1. Pick the source to move away from. The list shows how many of your entries came from each one, and **All** / **Manga** / **Novels** filters it.
1. Tap the entries you want. Tapping a cover opens that entry instead.
1. Tap **Continue**.
:::

::: tip From one series
1. Open the series.
1. Open <nav to="overflow"> and tap **Migrate**.
:::

::: tip From your library
1. Long-press an entry in <nav to="main_library"> to start selecting.
1. Tap the others you want.
1. Open the overflow at the end of the bottom bar and tap **Migrate**. If the bar has room, because the download action is hidden, Migrate sits on the bar itself instead.
:::

The library shows manga or novels, never both, so a selection is always one type.

::: info Grouped entries ask which source to move first
If what you picked is a [merged group](/docs/multi-source), a **Migrate** screen lists every source in the group. The ones you picked start checked; check the sources to move and tap **Continue**. Entries that are not grouped skip this step, as does the whole-source route above.
:::

### Choosing where to search

The **Migrate** screen lists your sources under **Selected** and **Available**. Only the selected ones are searched, and dragging reorders which is tried first.

* **Select all** and **Select none** are in the toolbar, and **Select enabled sources** and **Select pinned sources** are in its overflow.

Tap **Continue**. For several manga, a sheet opens first:

::: details Migration options
* **Data to migrate** picks what carries over, including whether to delete the old entry's downloads afterwards.
* **Additional keywords (optional)** narrows the search when a title alone finds too much.
* **Hide entries without a match** and **Hide entries without newer chapters** trim the list you have to read.
* **Advanced search mode** and **Match based on chapter number** sit under a warning that both are slow and may get you restricted by sources.
:::

Light novels have no options sheet: **Continue** goes straight to the matches.

### Looking over the matches

**Manga.** One entry goes straight to a search across your selected sources: tap the result you want.

Several entries open the **Migration** list, which searches in the background and counts up as it goes. Each row shows the entry, its match and each side's latest chapter. When searching finishes, the toolbar's **Copy** and **Migrate** actions handle every match at once. A row's overflow has **Search manually**, **Don't migrate**, **Migrate now** and **Copy now**. Backing out asks **Stop migrating?** first.

**Light novels.** Every selection opens the **Migrate** list, which searches each row as it comes into view. Tap the check on a row to accept its match. A row's overflow has **Search manually**, which opens a search inside the row, and **Don't migrate**. The **Copy** and **Migrate** buttons at the bottom show how many rows have a match.

### Confirming

One manga, and every light novel migration, asks you to **Select data to include**, then **Copy** or **Migrate**. Several manga use the data chosen on the options sheet and only ask you to confirm. Trackers always carry over.

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
