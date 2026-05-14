# Multi-source manga grouping

Many manga are available from multiple sources. Yōkai-Y2K folds same-title entries from different sources into a single library card so the library reflects unique manga rather than duplicates, and lets you switch between which source you're reading from on a per-manga basis.

Each section below leads with the in-app path or trigger so you know exactly where the feature lives.

## Auto grouping

*No setup — automatic in the library.*

When two or more library entries share the same title (case-insensitive), they're rendered as a single card. A small pill badge in the corner of the card shows the source count (e.g. `2`, `3`).

Behind the scenes this happens in `LibraryPresenter.applySourceGrouping()`. Grouping is per-category, so the same manga across two categories still shows once per category.

## Source-switcher chips

*Manga details screen, in the header below the cover.*

Open any manga that's part of a multi-source group and you'll see a horizontal chip row, one chip per source. The currently-displayed source is highlighted. Tap any other chip to switch to that source's version of the manga while keeping the merged-group context — chapters, progress, and library state all stay tied to the group.

The chip row appears on phones and on sw600dp tablet/foldable layouts.

The chip row refreshes automatically whenever you return to the manga details screen from another screen (e.g. after adding a new same-title source via Global Search) — no need to back out to Library and come back.

## Manual merge & unmerge

The auto-grouping is title-based; sometimes you want to merge entries that don't have identical titles (different romanizations, e.g. "Kaijuu 8-gou" vs. "Kaiju No. 8") or split a group apart.

### Merge

*Library → long-press an entry to enter multi-select → tap any other entries you want to include → tap the **⋮ overflow menu** in the toolbar → **Merge selected**.*

The Merge action lives in the overflow menu rather than the main toolbar — it isn't destructive, but it's also not something you want to fire accidentally. The selected entries become one library card and share the same chapter list, progress, and library state going forward.

### Unmerge

Two paths, both ending at the same confirmation dialog:

- **From the source chips** — *Manga details → long-press a source chip → confirm "Remove from group".* Quickest if you only want to detach one source from the chip row you're already looking at.
- **From the manage sources sheet** — *Manga details → overflow menu (⋮) → Manage sources → check one or more sources → tap "Split selected" → confirm.* Lets you split several sources at once and is easier than chip long-press on smaller screens.

Either way, the source you remove goes back to being a standalone library entry; the rest of the group stays merged.

State is stored in two preferences:

- `mangaManualMerges` — string set of comma-separated ID pairs of manga that should be grouped even if titles don't match
- `mangaManualUnmerges` — string set of comma-separated ID pairs of manga that should *not* be grouped despite matching titles

Both preferences are included in app backups, so manual merge/unmerge state survives a backup-and-restore.

## Manage sources sheet

*Manga details → overflow menu (⋮) → Manage sources.*

This sheet shows every source currently grouped with the open manga and lets you act on them in bulk. Tap anywhere on a row to toggle its checkbox; the bottom action bar exposes two operations on the selection:

- **Split selected** — detaches the checked sources from the group (same effect as long-pressing each chip), leaving them as standalone library entries.
- **Remove selected from library** — unfavorites the checked sources outright, deleting their downloaded chapters and covers. Useful when you want to drop unwanted source duplicates entirely rather than just splitting them off.

Both actions confirm via dialog and then show an undo snackbar — the change isn't committed until the snackbar dismisses, so an accidental tap can be reverted within the grace period.

The sheet only appears when the manga has at least one related library entry. To merge two existing entries, use Library multi-select → Merge selected (described above).

## Bulk "remove all sources from library"

When a multi-source group is in your library, removing it normally would only remove the entry you tapped on, leaving the other sources behind. Two paths handle the bulk case:

- **Single-manga path** — *Manga details → tap the favorite (heart) button → "Remove all sources from library"* in the popup. Removes every source that's part of the same merged group.
- **Library multi-select** — *Library, long-press to multi-select, include any merged-group entry in your selection, then delete.* The deletion automatically extends to all sources in any selected merged group, no extra confirmation needed.

## Settings

There's no dedicated settings screen for grouping. The two manual-merge preferences live in the standard preference store and are managed entirely through the chip / multi-select interactions described above.
