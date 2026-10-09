package reikai.presentation.recents

import reikai.domain.entry.EntryId
import reikai.domain.merge.coverFallbacks
import reikai.domain.merge.dedupeByMergeGroup

/*
 * The two algorithms every recents view is built from: one order, and one collapse. They are separate
 * functions rather than one assembly because the scope of the collapse is a render policy's decision,
 * not the kernel's: the flat modes and the digest both collapse across all lanes, so an entry gets one
 * row, History collapses a merged series' sources, and the Updates mode does not collapse at all.
 * Record: docs/dev/subsystems/recents.md.
 */

/**
 * Newest first, and total: equal timestamps break on the lane, then on the content type, then on the
 * entry's own id. A partial order would let two rows swap places between emissions for no reason the
 * user could see, since a stable sort only preserves whatever order the lanes happened to arrive in.
 */
private val recentsOrder: Comparator<RecentsItem> = compareByDescending<RecentsItem> { it.timestamp }
    .thenBy { it.lane.rank }
    .thenBy { it.entryId.contentType.ordinal }
    .thenBy { it.entryId.rawId }

/** Read leads at the same instant: it is the one thing the user did, the other two happened to them. */
private val RecentsLane.rank: Int
    get() = when (this) {
        is RecentsLane.Read -> 0
        is RecentsLane.Updated -> 1
        RecentsLane.Added -> 2
    }

/** Newest first. */
fun orderRecents(items: List<RecentsItem>): List<RecentsItem> = items.sortedWith(recentsOrder)

/**
 * One row per entry, the most recent winning, which falls out of ordering first. Keyed on [EntryId]
 * rather than a raw id because a manga and a novel can share one, and collapsing them together would
 * hide a row rather than fail. Merge groups are not consulted, so two sources of one merged series
 * are still two rows.
 */
fun collapseByEntry(items: List<RecentsItem>): List<RecentsItem> =
    orderRecents(items).distinctBy { it.entryId }

/**
 * One row per merge group, and per entry for anything ungrouped. Runs the per-entry collapse first, so
 * the member that represents a group is that member's own most recent activity rather than an arbitrary
 * row. An empty [membership] (which is what merging-off emits) leaves the per-entry result untouched.
 * The dedupe itself is [dedupeByMergeGroup], the one definition of counting a merged series once.
 */
fun collapseByGroup(items: List<RecentsItem>, membership: Map<EntryId, Long>): List<RecentsItem> =
    collapseByEntry(items).dedupeByMergeGroup(membership) { it.entryId }

/**
 * Stamps each merged row with the covers it falls back to when its own is missing or fails to load: its
 * group's other library members' ([coverFallbacks]), in member order. A row outside a group, and every
 * row while [membership] is empty (merging off), is left as it is.
 */
fun List<RecentsItem>.withGroupCovers(
    membership: Map<EntryId, Long>,
    coversByGroup: Map<Long, List<RecentsMemberCover>>,
): List<RecentsItem> = map { item ->
    val members = membership[item.entryId]?.let(coversByGroup::get) ?: return@map item
    item.copy(coverFallbacks = coverFallbacks(item.entryId, members) { it.entryId }.map { it.cover })
}
