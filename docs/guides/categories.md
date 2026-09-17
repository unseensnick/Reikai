---
title: Categories
titleTemplate: Guides
description: Organize your favorite series effortlessly with categories that declutter and structure your library.
---

# Categories

_Dev records: [novel-categories.md](../dev/plans/novel-categories.md), [category-schema-unification.md](../dev/plans/category-schema-unification.md), [library-sort-overrides.md](../dev/plans/library-sort-overrides.md). Doc map: [README.md](../README.md)._

Organize your favorite series effortlessly with categories that declutter and structure your library.

To manage your categories, navigate to <nav to="categories">.

- You can name and sort categories as you prefer (e.g., by `Genre`, `Reading Status`).
- Add series to multiple categories and control update options through Library settings, even auto-download chapters from chosen categories.
  > If you've enabled **Download new chapters** in the Downloads settings.

## Content

Categories would be useless without any content in them.
Below are some tips for using them.

:::: tabs
== Add entries
### Add series to a category

1. Long press the series you want to add.
1. Press the **Set categories** button.
1. Select which category or categories you want it in and press **OK**.

::: tip
You can also add multiple series to a category by selecting them when you see the **Set categories** button.
:::
== Remove entries
### Remove series from a category

1. Long press series that you want to remove.
1. Press the **Set categories** button.
1. Deselect the category or categories you want to remove it from and press **OK**.

::: tip
You can also remove multiple series from a category by selecting them when you see the **Set categories** button.
:::
::::

## Categories that span manga and novels

**Reikai** keeps one category list covering both libraries, and every category says which of them it applies to, on a line under its name. When you create one from <nav to="categories">, **Show in** offers:

| Option | Where it appears |
|---|---|
| **Manga and novels** | Both libraries. A shared "Reading" holds manga and novels together. |
| **Manga only** | The manga library only. |
| **Novels only** | The novels library only. |

The **All** / **Manga** / **Novels** chips at the top narrow the list to the categories one library shows, so **Manga** lists the manga and shared categories. **Show in** starts on the chip you are viewing: **Manga and novels** under **All**.

The choice is made when the category is created and cannot be changed afterwards; renaming a category leaves its type alone. To move entries to a differently-typed category, create the new one and reassign them.

A category that spans both libraries is a single category, not a copy in each, so hiding it, deleting it, or giving it its own sort applies in both places at once. It also holds one position in the drag order, so it sits in the same spot in each library.

## Category sort order

Open the display settings sheet in <nav to="main_library"> with the <icon name="filter"> icon, go to the **Display** tab, and set **Category sort order** under **Categories**.

By default, categories appear in the order you arranged them in the category manager. This setting lets you override that with a static sort:

| Option | Behavior |
|---|---|
| **Off** | Manual order, dragged in the category manager. *(Default.)* |
| **A to Z** | Ascending alphabetical. |
| **Z to A** | Descending alphabetical. |

::: tip Arranging the manual order
Drag a category by its handle, or open its <nav to="overflow"> menu and pick **Move to top** or **Move to bottom**, which is quicker on a long list. Dragging only works under the **All** chip, while the menu works under any of them. With a sort set, or while selecting, neither is offered.
:::

Switching to A to Z or Z to A doesn't destroy your manual order; flipping back to **Off** restores it. The sort applies everywhere categories are listed: the library tab strip, the **Set categories** sheet, and the categories screen.

## Deleting several categories at once

Long-press any category in <nav to="categories"> to enter multi-select mode. Once in multi-select:

- Tap any other category to add it to the selection, or remove it.
- Long-press another category to select every category between it and the last one you touched. Long-pressing a selected category removes it instead.
- The toolbar shows how many categories you have selected, with **Select all** and **Select inverse** beside it.
- Tap the delete (trash) icon to delete all of them at once, after one confirmation.
- An undo snackbar appears at the bottom afterwards: tap **Undo** to restore the deleted categories, with the entry assignments they had.

This avoids deleting one at a time when cleaning up a long list. **Select all** and **Select inverse** act on the categories the current chip shows, and under **All** a selection can mix manga, novel and shared categories.
