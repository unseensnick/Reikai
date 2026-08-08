package reikai.presentation.recents

/**
 * One of an entry's chapters, projected to what a target rule needs. Each provider builds this from
 * its own engine (manga through the scanlator-filtered chapter read, novels in source order), so the
 * data access stays per type while the rule below stays one rule.
 */
data class RecentsChapter(
    val id: Long,
    val fetchedAt: Long,
    val read: Boolean,
)

/** How far either side of a row's own chapter still counts as the same update burst. */
const val BURST_WINDOW_MS: Long = 12 * 60 * 60 * 1000L

/**
 * The chapter an updated row opens: the first unread chapter of the same burst (everything fetched
 * within [BURST_WINDOW_MS] of the row's chapter), falling back to the row's own. That is what makes a
 * "5 new chapters" row open the first of the five. [chapters] arrives in reading order, because
 * "first" means first to read, not first fetched, and only the provider knows its type's order.
 */
fun firstUnreadInBurst(
    chapters: List<RecentsChapter>,
    rowChapterId: Long,
    windowMs: Long = BURST_WINDOW_MS,
): Long {
    val row = chapters.firstOrNull { it.id == rowChapterId } ?: return rowChapterId
    return chapters
        .firstOrNull { !it.read && kotlin.math.abs(it.fetchedAt - row.fetchedAt) <= windowMs }
        ?.id
        ?: rowChapterId
}
