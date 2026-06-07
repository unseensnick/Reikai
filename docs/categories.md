# Category features

Two fork-specific additions to category management on top of upstream Yōkai. Each section leads with the in-app path so you know exactly where to find the feature.

## Category sort order

*Settings → Library → **Category sort order**.*

By default, categories appear in the order you arranged them (manual order, drag-to-reorder via Edit categories). This setting lets you override that with a static sort:

| Option | Behavior |
|---|---|
| **Off** | Manual order — drag-to-reorder via Edit categories. *(Default.)* |
| **A→Z** | Ascending alphabetical. |
| **Z→A** | Descending alphabetical. |

Switching to A→Z or Z→A doesn't destroy your manual order — flipping back to **Off** restores it. The sort applies everywhere categories are listed: the library tab strip, the "Move to category" sheet, and the categories screen.

## Category bulk delete

*Settings → Library → **Edit categories**, then long-press any category.*

Long-pressing a category enters multi-select mode. Once in multi-select:

- Tap any other category to add it to the selection (or remove it).
- The toolbar shows a counter of how many categories you have selected.
- Tap the delete (trash) icon in the toolbar to delete all selected categories at once.
- A single confirmation dialog asks you to confirm the bulk delete.
- After deletion, an undo snackbar appears at the bottom — tap **Undo** to restore the deleted categories (with the manga assignments they had).

This avoids the upstream behavior of having to delete each category one-at-a-time when cleaning up a long category list.
