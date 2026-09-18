package reikai.domain.novel.interactor

/**
 * Whether the categories kept from download removal cover a novel, the one rule both removal paths
 * honour. An uncategorized novel sits in Default (id 0), as manga's `DownloadManager` counts it.
 * [categoryIds] is only asked when something is excluded, so the common case costs no lookup.
 */
internal suspend fun isExcludedFromRemoval(excluded: Set<String>, categoryIds: suspend () -> List<Long>): Boolean {
    val excludedIds = excluded.mapNotNullTo(HashSet()) { it.toLongOrNull() }
    if (excludedIds.isEmpty()) return false
    return categoryIds().ifEmpty { listOf(0L) }.any { it in excludedIds }
}
