package reikai.domain.merge

/**
 * Which of [unified]'s rows are unread themselves but already read on another source of the group.
 * The stitch keeps one row per cross-source chapter and drops the rest, so without this a chapter
 * reads as unread purely because the copy that won happens to be the unread one.
 * One definition for both content types: only [key] is genuinely per type (manga matches on chapter
 * number, exempting gallery sources; novels on normalized title), and that is the caller's to supply.
 * A null key cannot be matched, so nothing stands in for it.
 */
fun <C> crossSourceReadIds(
    bySource: Map<Long, List<C>>,
    unified: List<C>,
    id: (C) -> Long,
    read: (C) -> Boolean,
    key: (C) -> String?,
): Set<Long> {
    if (bySource.size <= 1) return emptySet()
    val readKeys = bySource.values.asSequence()
        .flatten()
        .filter(read)
        .mapNotNullTo(HashSet(), key)
    if (readKeys.isEmpty()) return emptySet()
    return unified.asSequence()
        .filter { !read(it) && key(it) in readKeys }
        .mapTo(HashSet(), id)
}
