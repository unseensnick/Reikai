---
title: Feed and saved searches
titleTemplate: Guides
description: Keep the latest from the sources you pick on one Browse tab, and save a search to run again later.
---

# Feed and saved searches

_Dev record: [browse-feed-tab.md](dev/plans/browse-feed-tab.md). Doc map: [README.md](README.md)._

The Feed is a tab in <nav to="main_browse"> that shows a row of covers from each source you add to it, so you can see what is new without opening each source in turn.
A saved search keeps a source search, with its filters, so you can run it again with one tap or add it to the Feed as a row of its own.
Both work for manga and light-novel sources.

## Turning on the Feed

The Feed tab is off until you turn it on.

1. Go to <nav to="browse">.
1. Under **Feed**, turn on **Show Feed tab**.

Two more switches appear once it is on, both off by default:

* **Open Browse on the Feed tab** puts Feed first in Browse and opens it there. That loads every source in your feed each time you open Browse.
* **Hide entries already in library** leaves out anything you already have.

## Adding a row

1. Open the **Feed** tab and tap **Add to feed** in the toolbar.
1. Pick a source. The list holds every enabled source, manga and novels, and the **Search** field narrows it by name.
1. Pick what the row shows: **Latest** (or **Popular**, for a source with no latest list), or one of that source's saved searches.

A feed holds up to 20 rows. Each row shows the first page of what the source returns.

Tap a row's heading to open that source on the same list or search.
To remove a row, long-press its heading and confirm. A row whose source you uninstalled stays, with a note saying so, until you remove it or install the source again.

## Arranging and refreshing

* **Reorder feed** appears once you have two rows. Drag the rows into the order you want, then tap **Done reordering**.
* **Select** lets you pick covers across any of the rows and add them all to your library.
* Pull down to load every row again. The feed does not refresh by itself, apart from when you change its rows or your installed sources.

## Saved searches

A saved search belongs to one source and lives on that source's page.

1. Open a source from <nav to="sources">.
1. Search, or change the filters from their defaults.
1. Open the overflow and tap **Save search**, then give it a name. Names must be unique for that source.

Saved searches show as chips after **Popular**, **Latest** and **Filter** on the source's page. Tap one to run it again, or long-press it to delete it. There is no rename: delete it and save it again under the new name.

On a light-novel source, a saved search that holds only filters, with no search text, runs as the source's popular list with those filters. One with search text keeps its filters only where the source allows filters and a search together.

## Backups

Your feed rows and saved searches go into a backup when **Feed and saved searches** is ticked in the backup options. See [Backups](/docs/guides/backups).
