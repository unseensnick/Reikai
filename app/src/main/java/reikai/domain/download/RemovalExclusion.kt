package reikai.domain.download

/**
 * Whether the categories kept from download removal cover an entry, the one rule every removal path of
 * both content types honours. An uncategorized entry sits in Default (id 0).
 * [categoryIds] is only asked when something is excluded, so the common case costs no lookup.
 */
internal suspend fun isExcludedFromRemoval(excluded: Set<String>, categoryIds: suspend () -> List<Long>): Boolean {
    val excludedIds = excluded.mapNotNullTo(HashSet()) { it.toLongOrNull() }
    if (excludedIds.isEmpty()) return false
    return categoryIds().ifEmpty { listOf(0L) }.any { it in excludedIds }
}

/**
 * The downloaded [chapters] a delete may remove, the rule both content types' deletes honour: in a
 * category kept from removal only unread chapters go, and a bookmarked chapter stays unless
 * [allowBookmarked]. Mihon's manga delete filters the same way before touching a file.
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
    return if (allowBookmarked) kept else kept.filterNot(isBookmarked)
}
