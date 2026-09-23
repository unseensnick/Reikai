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
