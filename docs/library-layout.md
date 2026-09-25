---
title: Library layout
titleTemplate: Guides
description: How the library looks, from covers and badges to showing every category in one list, jumping between them, and grouping by tag, source, author and more.
---

# Library layout

_Dev records: [library-screen-carry.md](dev/plans/library-screen-carry.md), [library-all-chip.md](dev/plans/library-all-chip.md). Doc map: [README.md](README.md)._

By default, <nav to="main_library"> shows one category at a time as a grid of covers, with the category names in a row of tabs, and you swipe sideways to reach the next.
Reikai can also stack every category in one list you scroll down, with a floating button to jump between them, and it can group your library by something other than your categories.

The settings below are in the <icon name="filter"> icon of <nav to="main_library">, on the **Display** and **Group** tabs.
They work the same for manga and light novels.

## How entries look

**Display mode** <Badge type="info" text="Compact grid" /> picks how each entry is drawn:

| Mode | Looks like |
|---|---|
| **Compact grid** | covers with the title laid over the bottom |
| **Comfortable grid** | covers with the title underneath |
| **List** | one row per entry, with a small cover |
| **Cover-only grid** | covers with no title |
| **Panorama comfortable grid** | a comfortable grid that shows wide covers whole instead of cropping them |

**Items per row** <Badge type="info" text="Auto" /> sets how many covers fit across. The portrait and landscape values are separate, so turn the device to set the other one. It is hidden in **List**.

### Overlay

These add small badges to each cover:

| Setting | Shows | Default |
|---|---|---|
| **Downloaded chapters** | how many chapters are downloaded | Off |
| **Unread chapters** | how many chapters are unread | On |
| **Local source** | that the entry comes from the local source (manga only) | On |
| **Language** | the source's language | Off |
| **Source icon** | the source's icon | On |
| **Show source icons on merged covers** | every source behind a [merged series](multi-source.md) | On |
| **Continue reading button** | a button on the cover that opens the next chapter | Off |

**Group series across sources** also sits in this section. It is described with [merged series](multi-source.md).

### Tabs

- **Show category tabs** <Badge type="info" text="On" /> puts the category names in a row of tabs above the library.
- **Show number of items** <Badge type="info" text="Off" /> adds the item counts to the toolbar title, the tabs, the category headers and **Jump to category**.
- **Show all categories in one list** <Badge type="info" text="Off" /> is described next.

## All categories in one list

Turn on **Show all categories in one list** under **Tabs** on the **Display** tab.

Each category gets a header with its name, plus its item count when **Show number of items** is on.
Tap the header to collapse or expand that category.
A category's header also shows its sort, which you can tap to change for that category alone, and a button that updates just that category.
On **Default**, the sort you pick there is the library-wide sort, which every category without its own sort follows.
A group from the **Group** tab has neither, since it is not one of your categories.

With the setting off you are back to one category per page.

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
- **Category sort order** sorts the categories themselves. See [category sort order](guides/categories.md#category-sort-order).
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
