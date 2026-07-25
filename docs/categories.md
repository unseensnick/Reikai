# Category features

_Dev records: [novel-categories.md](dev/plans/novel-categories.md), [category-schema-unification.md](dev/plans/category-schema-unification.md), [library-sort-overrides.md](dev/plans/library-sort-overrides.md). Doc map: [README.md](README.md)._

Reikai additions to category management on top of Mihon, all working for manga and novel categories alike. Each section leads with the in-app path so you know exactly where to find the feature.

## Categories that span manga and novels

*Settings → Library → **Edit categories** → the add (+) button.*

Edit categories is one list covering both libraries, and every category says which of them it applies to. When you create one you choose between:

| Option | Where it appears |
|---|---|
| **Manga and novels** | Both libraries. A shared "Reading" holds manga and novels together. *(Default.)* |
| **Manga only** | The manga library only. |
| **Novels only** | The novels library only. |

The choice is made when the category is created and cannot be changed afterwards; renaming a category leaves its type alone. To move entries to a differently-typed category, create the new one and reassign them.

A category that spans both libraries is a single category, not a copy in each, so hiding it, deleting it, or giving it its own sort applies in both places at once. It also holds one position in the drag order, so it sits in the same spot in each library.

## Category sort order

*Library → open the display settings sheet (the sliders icon) → **Display** tab → **Category sort order** (under Categories).*

By default, categories appear in the order you arranged them (manual order, drag-to-reorder via Edit categories). This setting lets you override that with a static sort:

| Option | Behavior |
|---|---|
| **Off** | Manual order (drag-to-reorder via Edit categories). *(Default.)* |
| **A→Z** | Ascending alphabetical. |
| **Z→A** | Descending alphabetical. |

Switching to A→Z or Z→A doesn't destroy your manual order; flipping back to **Off** restores it. The sort applies everywhere categories are listed: the library tab strip, the "Move to category" sheet, and the categories screen.

## Category bulk delete

*Settings → Library → **Edit categories**, then long-press any category.*

Long-pressing a category enters multi-select mode. Once in multi-select:

- Tap any other category to add it to the selection (or remove it).
- The toolbar shows a counter of how many categories you have selected.
- Tap the delete (trash) icon in the toolbar to delete all selected categories at once.
- A single confirmation dialog asks you to confirm the bulk delete.
- After deletion, an undo snackbar appears at the bottom: tap **Undo** to restore the deleted categories (with the entry assignments they had).

This avoids having to delete each category one at a time when cleaning up a long list. Since the category manager is one list, a selection can mix manga, novel and shared categories.
