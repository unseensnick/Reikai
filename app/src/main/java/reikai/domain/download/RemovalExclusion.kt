package reikai.domain.download

/**
 * Whether the categories kept from download removal cover an entry, the one rule every removal path of
 * both content types honours. An uncategorized entry sits in Default (id 0).
 * [categoryIds] is only asked when something is excluded, so the common case costs no lookup.
 */
private suspend fun isExcludedFromRemoval(excluded: Set<String>, categoryIds: suspend () -> List<Long>): Boolean {
    val excludedIds = excluded.mapNotNullTo(HashSet()) { it.toLongOrNull() }
    if (excludedIds.isEmpty()) return false
    return categoryIds().ifEmpty { listOf(0L) }.any { it in excludedIds }
}

/**
 * The downloaded [chapters] automatic removal may take (delete after read, delete after marked read),
 * the rule both content types honour: in a category kept from removal only unread chapters go, then
 * whatever [deletableDownloads] leaves.
 */
internal suspend fun <T> removableDownloads(
    chapters: List<T>,
    excluded: Set<String>,
    allowBookmarked: Boolean,
    isRead: (T) -> Boolean,
    isBookmarked: (T) -> Boolean,
    categoryIds: suspend () -> List<Long>,
): List<T> {
    val kept = if (isExcludedFromRemoval(excluded, categoryIds)) chapters.filterNot(isRead) else chapters
    return deletableDownloads(kept, allowBookmarked, isBookmarked)
}

/**
 * The downloaded [chapters] a Delete the user asked for takes, in both content types' managers: all
 * of them, but a bookmarked one only when [allowBookmarked]. The categories kept from removal are not
 * asked, since they govern automatic removal only; Mihon applies them to a manual delete too.
 */
internal fun <T> deletableDownloads(
    chapters: List<T>,
    allowBookmarked: Boolean,
    isBookmarked: (T) -> Boolean,
): List<T> = if (allowBookmarked) chapters else chapters.filterNot(isBookmarked)
