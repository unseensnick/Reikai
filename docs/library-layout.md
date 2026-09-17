---
title: Library layout
titleTemplate: Guides
description: Show every category in one scrolling list, jump between them with the hopper, and group the library by tag, source, author and more.
---

# Library layout

_Dev records: [library-screen-carry.md](dev/plans/library-screen-carry.md), [library-tabbed-shell.md](dev/plans/library-tabbed-shell.md). Doc map: [README.md](README.md)._

By default, <nav to="main_library"> shows one category at a time, and you swipe sideways to reach the next.
Reikai can also stack every category in one list you scroll down, with a floating button to jump between them, and it can group your library by something other than your categories.

The settings below are in the filter icon of <nav to="main_library">, on the **Display** and **Group** tabs.
They work the same for manga and light novels.

## All categories in one list

Turn on **Show all categories in one list** under **Tabs** on the **Display** tab.

Each category gets a header with its name, plus its item count when **Show number of items** is on.
Tap the header to collapse or expand that category.
A category's header also shows its sort, which you can tap to change for that category alone, and a button that updates just that category.
A group from the **Group** tab has neither, since it is not one of your categories.

With the setting off you are back to one category per page, and **Show category tabs** puts the category names in a row of tabs above them.
**Show number of items** adds the item counts to the toolbar title, the category headers and **Jump to category**.

## The category hopper

A floating button sits at the bottom of the library, in both views:

- The up and down arrows jump to the previous or next category.
- Tapping the middle opens **Jump to category**, a list of every category, with item counts when **Show number of items** is on.
- Long-pressing the middle does whatever **Hopper long-press** is set to: search the library, collapse or expand every category, open the **Display** or **Group** settings, or open a random entry from the category you are in (**Random**) or from the whole library (**Random (global)**).
- Drag the button sideways to move it to the left, the middle or the right of the screen.

These options are under **Categories** at the bottom of the **Display** tab:

- **Hide category hopper** removes the button.
- **Hide hopper while scrolling** tucks it away while the one-list view scrolls.
- **Always show current category** replaces "Library" in the toolbar with the name of the category you are in. With **Show category tabs** off, the toolbar does this anyway.
- **Category sort order** sorts the categories themselves A to Z or Z to A. **Off** keeps the order you set on the categories screen.
- **Show hidden categories** brings back the categories you hid there.

## Grouping

The **Group** tab changes what the sections are:

| Option | Sections |
|---|---|
| **By default** | your own categories |
| **By tag** | one per tag |
| **By source** | one per source |
| **By status** | ongoing, completed, and the other publishing statuses |
| **By tracking status** | one per tracking status, plus **Not tracked** |
| **By author** | one per author |
| **By language** | one per language |
| **Ungrouped** | everything in one section |

A series with several tags, or several authors, appears under each of them.
**Move dynamic groups to the bottom**, on the **Display** tab, sends the groups you have collapsed to the end of the list.
