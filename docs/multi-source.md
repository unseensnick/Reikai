# Multi-source manga grouping

Many manga are available from multiple sources. Reikai folds same-title entries from different sources into a single library card so the library reflects unique manga rather than duplicates, and lets you switch between which source you're reading from on a per-manga basis.

Light novels support the same cross-source merge, so a novel available from several sources groups the same way.

Each section below leads with the in-app path or trigger so you know exactly where the feature lives.

## Grouping across sources

*Master toggle: **Group series across sources**, in the library display menu (the filter/funnel icon, Display tab) and under Settings → Library.*

When grouping is on, two or more library entries in the same group are rendered as a single card. Grouping is explicit: an entry only joins a group when you say so, either through the add-time "Add to existing group" prompt (below) or a manual merge. There is no silent same-title force; turning the master toggle off expands every group back to its per-source entries, and turning it on collapses them again.

**The add-time prompt.** When you add a series (or novel) from browse, global search, history, or MangaDex Follows that matches one already in your library, an "Add to existing group" action appears on the duplicate dialog. You pick the group to join from the duplicate cards shown; the new copy is favorited and merged into the selected match's group. This add-time suggestion is itself toggleable (see Settings, below), and it never groups anything you didn't confirm.

A merged card shows the grouped sources' icons in its corner (up to three, with a "+N" overflow if there are more). If source icons are turned off or unavailable, the card falls back to a numeric count instead.

Grouping is per-category, so the same manga across two categories still shows once per category.

## Source-switcher chips

*Manga details screen, in the header below the cover.*

Open any manga that's part of a multi-source group and you'll see a horizontal chip row, one chip per source. The currently-displayed source is highlighted. Tap any other chip to switch to that source's version of the manga while keeping the merged-group context: chapters, progress, and library state all stay tied to the group.

Long-press a chip to split that source out of the group. A confirmation titled "Split" appears, reading "Split <source> out of this merged group?" Confirming returns that source to a standalone library card; the rest of the group stays merged.

The chip row refreshes automatically whenever you return to the manga details screen from another screen (for example, after adding a new same-title source via Global Search), so there's no need to back out to Library and come back.

## Reading a merged group

*Reader: open any chapter of a merged entry.*

A merged entry reads as one continuous series. The in-reader chapter list shows every source's chapters in a single list, each labeled with the source it came from, and the previous / next controls span the whole group: reaching the end of one source's chapters flows straight into the next source's without leaving the reader.

Each chapter's downloads, read state, and tracker updates still follow its own source underneath, so the group reads like one series while staying correct per source.

This applies to both merged manga and merged novels.

## Manual merge & unmerge

The auto-grouping is title-based; sometimes you want to merge entries that don't have identical titles (different romanizations, e.g. "Kaijuu 8-gou" vs. "Kaiju No. 8") or split a group apart.

### Merge

*Library → long-press an entry to enter multi-select → tap any other entries you want to include → tap **Merge** in the bottom action bar.*

The **Merge** button appears in the bottom action bar once two or more entries are selected. The selected entries become one library card and share the same chapter list, progress, and library state going forward.

### Unmerge

Two paths, both ending at the same confirmation:

- **From the source chips**: *Manga details → long-press a source chip → confirm "Split".* Quickest if you only want to detach one source from the chip row you're already looking at.
- **From the Manage sources dialog**: *Manga details → overflow menu (⋮) → Manage sources → choose a source → "Split".* Easier than chip long-press on smaller screens.

Either way, the source you split goes back to being a standalone library entry; the rest of the group stays merged. The split shows an Undo snackbar, so an accidental tap can be reverted within the grace period.

Merge grouping is included in app backups, so it survives a backup-and-restore.

## Manage sources dialog

*Manga details → overflow menu (⋮) → Manage sources.*

This shared dialog (the same one for manga and novels) shows every source currently grouped with the open entry and offers:

- **Drag to reorder**: each source row has a drag handle. Reordering sets which source leads the group, a per-group override of the global **Preferred sources** ranking. The top row is the group's lead and carries a **primary** badge; reordering applies live and re-aggregates the chapter list.
- **Reset order**: clears the per-group override so the group falls back to the global Preferred sources ranking.
- **Split**: detaches a source from the group (same effect as long-pressing its chip), leaving it as a standalone library entry.
- **Remove from library**: unfavorites a source outright, deleting its downloaded chapters and covers. Useful when you want to drop an unwanted source duplicate entirely rather than just splitting it off.
- **Remove all from library**: unfavorites every source in the merged group at once. This is the only way to remove a whole group in one action.
- **Long-press multi-select**: long-pressing a source row enters selection mode for batch Split or Remove across several sources at once.

Split and remove actions show an Undo snackbar, so an accidental tap can be reverted within the grace period.

## Removing merged entries from the library

The favorite (heart) button on the manga details screen adds or removes only the one entry you're viewing. It does not offer a group-wide removal.

A library multi-select delete (long-press to multi-select, then delete) removes the entries you selected. When the selection includes a merged cover, the Remove dialog offers an **All N grouped sources** checkbox that widens the delete to every source in the group at once (manga and novels).

You can also remove an entire merged group from the details screen with **Remove all from library** in the Manage sources dialog (above).

## Settings

Two settings govern grouping, both under **Settings → Library**:

- **Group series across sources**: the master toggle (also in the library display menu). Off expands every group to its per-source entries; on collapses them.
- **Suggest grouping for same-title series**: gates the add-time "Add to existing group" prompt. When off, adding a same-title series never suggests joining an existing group.

The manual merge and split actions themselves are driven through the chip and multi-select interactions described above.
