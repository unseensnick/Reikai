# Tracker sharing across merged sources

When an entry in your library is part of a multi-source group (see [multi-source.md](multi-source.md)), you don't have to set tracker links per-source. The group can share one tracker binding across all of its sources.

This works the same way for manga and light novels.

## Add a tracker: shared across the group

*Details → tap "Add tracker" → search the tracker → pick the matching entry.*

The tracker is set for the source you're viewing and copied onto every other in-library source in the same merged group. Open another source's details and you'll see the same tracker chip already populated.

Only *missing* trackers are filled in:

- A source that already has a binding for that tracker is never overwritten.
- Sources not in your library (grouped but unfavorited) are skipped. The tracker only goes on entries you're actually keeping.
- If a tracker's linked entry disagrees across the group (different sources already point at different remote entries for the same tracker), that tracker is skipped rather than guessed. Nothing is overwritten.

## Merge: shared trackers spread to the group

*Library → long-press to multi-select → tap "Merge".*

When you merge entries into a group, the same simple sharing runs: each tracker that some members already have is copied onto the other in-library members that are missing it. Existing bindings are never overwritten, and a tracker is skipped if members disagree on which remote entry it points to.

There's no voting or majority rule, and nothing happens automatically during a library refresh or auto-grouping. Sharing runs only when you explicitly merge entries, or when you add a tracker from the tracking dialog.

## Remove a tracker: only that source

*Details → long-press the tracker chip → "Remove".*

Removing a tracker affects only the source you're viewing. Other sources in the group keep their trackers. Removal is never propagated: a per-source removal means "I don't want this source linked anymore", not "remove everywhere".

## Split a source out of the group: trackers untouched

*Manage Sources sheet → check a source → "Split".*

Each source ends up with its own copy of the tracker binding, so trackers survive a split. Both the split-off source and the remaining group keep their tracker chips exactly as-is. There's nothing to clean up.

For a merged light novel this is the same: while merged it shares one tracker binding across its sources, and each source keeps the tracker if you later split them apart.

## Remove from library: that entry's trackers are cleaned up

Removing an entry from the library, by *any* path, clears its tracker rows. Sources still in the library keep their own trackers.

- *Manage Sources sheet → "Remove from library"*: just the entries you ticked.
- *Manage Sources sheet → "Remove all from library"*: every source in the merged group.
- *Details → heart button*: just the entry you're viewing (no group-wide option here).
- *Library multi-select → delete*: the selected entries, or every grouped source if you tick **All N grouped sources** in the Remove dialog.

Once an entry isn't in your library, its tracker association is stale, so cleaning it up avoids leaving orphaned rows behind. This cleanup always runs, regardless of the sharing setting below.

## Setting

*Settings → Tracking → "Share trackers across merged sources".*

Default **on**. Turn it off to disable both add-side and merge-side sharing, so tracker linking happens one source at a time. The cleanup on Remove-from-library always runs and isn't governed by this toggle.
