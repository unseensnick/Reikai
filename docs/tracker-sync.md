# Tracker sharing across merged sources

_Dev records: [novel-tracking.md](dev/plans/novel-tracking.md), [tracker-aware-duplicate-detection.md](dev/tracker-aware-duplicate-detection.md). Doc map: [README.md](README.md)._

When an entry in your library is part of a multi-source group (see [multi-source.md](multi-source.md)), you don't have to set tracker links per-source. One tracker binding covers the whole group.

This works the same way for manga and light novels.

## Add a tracker: it counts for the whole group

*Details → tap "Add tracker" → search the tracker → pick the matching entry.*

The tracker is bound once, to the source you're viewing, and it counts for every source in the group. Open another source's details and the same tracker chip is there. Reading a chapter from any source advances it, the library's tracker filter, tracker-score sort and tracking-status grouping all see it, and refreshing from the tracker updates it.

There is one binding while the group is merged, not a copy per source. That means the progress you see is always the group's, never a source's stale snapshot.

## Merge: nothing to copy

*Library → long-press to multi-select → tap "Merge".*

Merging entries needs no tracker step: the moment they are one group, a tracker bound on any member counts for all of them. If two members were already tracked with different remote entries for the same tracker, both bindings stay; the furthest-read one is the one that counts.

## Remove a tracker: clears the group

*Details → long-press the tracker chip → "Remove".*

Removing a tracker unbinds it from every source in the group. Because the group reads as one, leaving a sibling's binding in place would keep the entry showing as tracked in the library filters even though its chip is gone.

## Break the group up: each source keeps a copy

*Manage Sources sheet → check a source → "Split", the library's Unmerge action, "Remove from library", or Settings → Advanced → "Clear merges".*

Every one of those hands each in-library source its own copy of the group's tracker bindings first, carrying the group's furthest-read progress. Both the split-off source and the remaining group keep their tracker chips, and each then tracks on its own.

Two cases are skipped rather than guessed: a source that isn't in your library (grouped but unfavorited), and a tracker whose linked remote entry disagrees across the group.

## Remove from library: tracker rows stay

Removing an entry from the library doesn't clear its tracker rows. They are dropped only when the entry itself is deleted from the database (Settings → Advanced → Clear database), which cascades. Re-adding a removed source to your library brings its tracker back with it.

## Refresh the whole library's tracker data

*Library → the three-dot menu → **Refresh tracker data**.*

Scores, statuses and remote progress are normally pulled when you open an entry's page, so an entry you have not opened lately can hold stale values. Since the library can sort and filter by tracker score, that stale data is what those read. This action pulls fresh data for every tracked entry in one pass, manga and light novels together, whichever chip you are on.

It only visits entries that carry a tracker you are signed into, so an untracked library finishes immediately, and it is group-aware in the sense above: a merged entry refreshes every tracker bound anywhere in its group.

When it finishes you get a count, plus the names of any services that had failures. The usual cause is an entry whose remote list item no longer exists, for instance because you deleted it on the service; re-binding that entry fixes it. It is deliberately not attached to the library update, because that runs on a schedule and this is a network call per bound tracker.

## Setting

*Settings → Tracking → "Share trackers across merged sources".*

Default **on**, and it governs everything above. Turn it off and each source tracks on its own again: a tracker counts only for the source it is bound to, removing one leaves the others alone, and a split no longer hands each source a copy, so only the source holding the binding stays tracked. Existing bindings are left alone either way, so you can switch back.
