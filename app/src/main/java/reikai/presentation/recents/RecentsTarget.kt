package reikai.presentation.recents

/**
 * One of an entry's chapters, projected to what a target rule needs. Each provider builds this from
 * its own engine, so the data access stays per type while the rules below stay one rule.
 *
 * **The list is always ascending reading order**, oldest first. Every rule here walks it forwards, and
 * the two engines' native orders disagree (a merged manga list arrives newest-first), so a provider
 * handing one over unsorted makes "the next chapter" mean the previous one. [read] folds in what
 * another source of a merge group already read.
 */
data class RecentsChapter(
    val id: Long,
    val fetchedAt: Long,
    val read: Boolean,
)

/**
 * Resume over a merge group: reopen the recorded chapter while it is unfinished, else the next unread
 * after it. One rule for both engines, over each one's own unified list, with a chapter another source
 * already read passed in as read. Null when [recordedId] is not in [chapters], which happens when the
 * cross-source stitch dropped that copy; the caller then falls back to its own single-source resume.
 */
fun resumeInGroup(chapters: List<RecentsChapter>, recordedId: Long): Long? {
    val index = chapters.indexOfFirst { it.id == recordedId }
    if (index < 0) return null
    if (!chapters[index].read) return recordedId
    return chapters.drop(index + 1).firstOrNull { !it.read }?.id
}

/**
 * The first chapter left to read, for a row that has no recorded chapter to resume from. One rule for
 * both engines: the library applies each entry's own chapter filters when it resolves the same thing,
 * and this surface deliberately does not, because a filter about what to list should not decide where
 * a newly added series starts.
 */
fun firstUnreadOf(chapters: List<RecentsChapter>): Long? = chapters.firstOrNull { !it.read }?.id

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
