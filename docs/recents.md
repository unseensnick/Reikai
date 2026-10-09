---
title: Updates, History and Recents
description: Combine Updates and History into one Recents tab, filter both by category, and act on chapters with a swipe.
---

# Updates, History and Recents

_Dev records: [content-layer-recents-surface.md](dev/plans/content-layer-recents-surface.md), [recents-continue-reading-row.md](dev/plans/recents-continue-reading-row.md). Doc map: [README.md](README.md)._

Updates lists new chapters, and History lists what you have read. Both hold manga and light novels together, with **All** / **Manga** / **Novels** chips to narrow them.
You can keep them as two tabs, or combine them into one **Recents** tab.

## One Recents tab

Go to <nav to="appearance"> and turn on **Combine Updates and History**. The bottom bar swaps the two tabs for <nav to="main_recents"> straight away, with no restart. Turning it off brings the two tabs back.

The first time you turn it on, Recents takes the content chip and category filter Updates had. After that, Recents keeps its own.

Recents has four modes along the top, and remembers the one you last used:

* **Grouped** sorts your series into **New chapters**, **Continue reading** and **Newly added**, a few rows each, with the most recent section first. A series appears in only one section. **View all** at the end of the first two switches to the Updates or History mode.
* **Feed** is the default: one row per series, newest activity first, each saying what happened (updated, read or added). A series you have been reading shows the next chapter.
* **History** and **Updates** are the same lists as the separate tabs, with a heading for each day.

Grouped and Feed leave out series with nothing unread. Turn on **Show caught-up series** in the filter sheet to see them too. Newly added series always show.

Search works in every mode. **Update library** and **Upcoming Updates** are in the overflow in the modes that show updates (**Upcoming Updates** not on the **Novels** chip), and **Clear history** in the modes that show history. Pull down to update the library in the same modes that show updates.

The unread count from **Show unread count on Updates icon** in <nav to="library"> sits on the Recents icon. Opening a mode that shows updates clears it; History mode leaves it alone.

Tapping the Recents icon again while you are on it opens your most recently read chapter, or the download queue in Updates mode.

## Filtering

Tap **Filter** in the toolbar. The icon is highlighted while a filter is on. The sheet has three tabs:

* **General**: tick **Categories** and tap **Edit** to pick the categories to include or exclude. Unticking it keeps your picks but ignores them. The row is hidden if you have no categories. **Show caught-up series** is here too, and only changes Recents' Grouped and Feed modes.
* **Chapters**: **Downloaded**, **Unread**, **Started** and **Bookmarked**, each of which can include or exclude, plus **Filter excluded scanlators** for manga. These apply to Grouped, Feed and Updates, not to History.
* **Updates**: **Group by series** folds a series' new chapters from the same day into one row you can expand.

Updates, History and Recents each keep their own category selection, so filtering History does not change Updates. The **All** / **Manga** / **Novels** chip in the **Edit** dialog only narrows which categories it lists.

## Swipe actions

In Updates, swipe a chapter row left or right to act on it. The same two settings control the chapter list on a series' page:

* **Chapter on swipe to right**, **Mark as read** by default.
* **Chapter on swipe to left**, **Bookmark chapter** by default.

Both are in <nav to="library">, and each can be **Disabled**, **Bookmark chapter**, **Mark as read** or **Download**. Read and bookmark toggle on and off. Download starts a download, cancels one that is queued, or deletes one already downloaded.

A swipe acts on that row only and leaves any selection alone. It works on Updates rows for manga and novels, on the chapters inside an expanded **Group by series** row, and on **New chapters** rows in Recents' Grouped and Feed modes. History rows cannot be swiped.
