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

## Manual merge & unmerge

The auto-grouping is title-based; sometimes you want to merge entries that don't have identical titles (different romanizations, e.g. "Kaijuu 8-gou" vs. "Kaiju No. 8") or split a group apart.

### Merge

*Library → long-press an entry to enter multi-select → tap any other entries you want to include → tap the **⋮ overflow menu** in the toolbar → **Merge selected**.*

The Merge action lives in the overflow menu rather than the main toolbar — it isn't destructive, but it's also not something you want to fire accidentally. The selected entries become one library card and share the same chapter list, progress, and library state going forward.

### Unmerge

Two paths, both ending at the same confirmation dialog:

- **From the source chips** — *Manga details → long-press a source chip → confirm "Remove from group".* Quickest if you only want to detach one source from the chip row you're already looking at.
- **From the manage sources sheet** — *Manga details → overflow menu (⋮) → Manage sources → tap the X icon next to any source → confirm "Remove from group".* Useful when you're in the sheet anyway (adding/removing several sources at once), or when chip long-press is awkward on smaller screens.

Either way, the source you remove goes back to being a standalone library entry; the rest of the group stays merged.

State is stored in two preferences:

- `mangaManualMerges` — string set of comma-separated ID pairs of manga that should be grouped even if titles don't match
- `mangaManualUnmerges` — string set of comma-separated ID pairs of manga that should *not* be grouped despite matching titles

Both preferences are included in app backups, so manual merge/unmerge state survives a backup-and-restore.

## Manage sources sheet

*Manga details → overflow menu (⋮) → Manage sources.*

This sheet lets you search across your library for entries to add to (or remove from) the current manga's source group. Useful when:

- A new source for a manga you already track gets added to your library — open the existing entry, Manage sources, search for the new entry, add it.
- You want to clean up sources you're no longer interested in tracking for a particular manga.

The sheet only appears when the manga has at least one related library entry to potentially merge with.

## Bulk "remove all sources from library"

When a multi-source group is in your library, removing it normally would only remove the entry you tapped on, leaving the other sources behind. Two paths handle the bulk case:

- **Single-manga path** — *Manga details → tap the favorite (heart) button → "Remove all sources from library"* in the popup. Removes every source that's part of the same merged group.
- **Library multi-select** — *Library, long-press to multi-select, include any merged-group entry in your selection, then delete.* The deletion automatically extends to all sources in any selected merged group, no extra confirmation needed.

This is from version 1.9.7.5.6 — before that, removing a merged group required removing each source individually.

## Settings

There's no dedicated settings screen for grouping. The two manual-merge preferences live in the standard preference store and are managed entirely through the chip / multi-select interactions described above.
