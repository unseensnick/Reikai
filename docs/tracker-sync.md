# Tracker sync across grouped sources

When a manga in your library is part of a multi-source group (see [multi-source.md](multi-source.md)), tracker links don't have to be set per-source. Adding a tracker on one source can mirror the same link onto every other source in the group automatically.

## Add — propagates by default

*Manga details → tap "Add tracker" → search the tracker → pick the matching entry.*

The tracker link (the `(service, remote_id)` pair pointing to your AniList / MAL / MangaUpdates entry) is inserted for the source you're viewing **and** for every other still-in-library source in the same merged group. Open another source's detail page and you'll see the same tracker chip already populated.

Unfavorited siblings (grouped but not in the library) are skipped — the tracker only goes on entries you're actually keeping.

If a sibling already has a different `remote_id` linked for the same tracker service, it gets overwritten. The grouping intent here is "all sources in this group point to the same tracker entry"; conflict-resolution is last-write-wins.

## Merge — new joiners inherit the group's trackers

*Library → long-press to multi-select → tap "Merge selected".*

When the resulting group has any tracker links, every member ends up with the union of those links after the merge. For each tracker service, the binding shared by **strictly more** members of the new group wins, so a brand-new source joining an already-tracked group inherits the group's existing binding rather than overwriting it. On ties (no strict majority) the tracker is left as-is on every member — so a 2-member group where each member has a different binding for the same service keeps both bindings instead of silently overwriting one.

The same reconciliation runs on **auto-grouped** entries — when you add a manga to your library and it shares a title with an already-tracked entry, the new source picks up the existing tracker on the next library refresh. Reconciliation runs once per group composition (cached persistently across app restarts) so the user's later explicit tracker-chip removals on a member aren't undone on subsequent passes.

When a manga is removed from the library, any cached reconciliation keys containing it are invalidated. So if you later re-add the same manga (or any other manga with that ID), the group reforms and reconciliation runs fresh on the new joiner — which is what you want, because the re-added entry starts with no trackers and needs the group's binding.

Behind the same `syncTrackerLinksGrouped` toggle as add-side propagation.

## Remove — does NOT propagate

*Manga details → long-press the tracker chip → "Remove".*

Explicit tracker removal only affects the source you're viewing. Siblings keep their tracker chips. Per-source removal is a deliberate intent ("I don't want this source linked anymore"), not a "remove everywhere" intent.

## Split a source out of the group — trackers untouched

*Manage Sources sheet → check a source → "Split selected".*

Splitting a source out of a merged group is a grouping change, not a library or tracker change. Every source — both the split-off one and the remaining group — keeps its tracker chip exactly as-is.

## Remove from library — that manga's trackers are cleaned up

Removing a manga from the library — by *any* path — clears its tracker rows. Siblings still in the library keep their own trackers.

- *Manage Sources sheet → "Remove from library"* — just the entries you ticked.
- *Manga details → heart button → "Remove from library"* — just the manga you're viewing.
- *Manga details → heart button → "Remove all sources from library"* — every source in the merged group.
- *Library multi-select → delete* — every selected manga.

Once a manga isn't in your library, its tracker association is stale by definition; cleaning it up avoids leaving orphaned rows behind. This cleanup always runs, regardless of the `syncTrackerLinksGrouped` toggle.

## Setting

*Settings → Tracking → "Sync tracker links across grouped sources".*

Default **on**. Toggle off to disable add-side and merge-side propagation — tracker linking then behaves exactly like upstream Yōkai (one source at a time). The remove-side cleanup on Remove-from-library always runs and isn't governed by this toggle.
