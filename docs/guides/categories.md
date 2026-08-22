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

**Reikai** keeps one category list covering both libraries, and every category says which of them it applies to. When you create one from <nav to="categories">, you choose between:

| Option | Where it appears |
|---|---|
| **Manga and novels** | Both libraries. A shared "Reading" holds manga and novels together. *(Default.)* |
| **Manga only** | The manga library only. |
| **Novels only** | The novels library only. |

The choice is made when the category is created and cannot be changed afterwards; renaming a category leaves its type alone. To move entries to a differently-typed category, create the new one and reassign them.

A category that spans both libraries is a single category, not a copy in each, so hiding it, deleting it, or giving it its own sort applies in both places at once. It also holds one position in the drag order, so it sits in the same spot in each library.

## Category sort order

Open the display settings sheet in <nav to="main_library"> with the <icon name="filter"> icon, go to the **Display** tab, and set **Category sort order** under **Categories**.

By default, categories appear in the order you arranged them, which you set by dragging them in the category manager. This setting lets you override that with a static sort:

| Option | Behavior |
|---|---|
| **Off** | Manual order, dragged in the category manager. *(Default.)* |
| **A to Z** | Ascending alphabetical. |
| **Z to A** | Descending alphabetical. |

Switching to A to Z or Z to A doesn't destroy your manual order; flipping back to **Off** restores it. The sort applies everywhere categories are listed: the library tab strip, the **Set categories** sheet, and the categories screen.

## Deleting several categories at once

Long-press any category in <nav to="categories"> to enter multi-select mode. Once in multi-select:

- Tap any other category to add it to the selection, or remove it.
- The toolbar shows how many categories you have selected.
- Tap the delete (trash) icon to delete all of them at once, after one confirmation.
- An undo snackbar appears at the bottom afterwards: tap **Undo** to restore the deleted categories, with the entry assignments they had.

This avoids deleting one at a time when cleaning up a long list. Since the category manager is one list, a selection can mix manga, novel and shared categories.
