package reikai.presentation.details

/**
 * The member whose download folder the details overflow's Open folder opens. Its presence also offers
 * Clear downloads, since both act on the same members: a chip ([viewed]) alone, else the whole [group]
 * (just the entry itself when not merged), anchor first. Asked of the files on disk, never of the rows,
 * which fold a sibling's copy in and lose whatever a filter or a hide drops.
 */
fun <T> downloadFolderOwner(
    viewed: T?,
    group: List<T>,
    isAnchor: (T) -> Boolean,
    hasDownloads: (T) -> Boolean,
): T? {
    val candidates = viewed?.let(::listOf) ?: group.sortedByDescending(isAnchor)
    return candidates.firstOrNull(hasDownloads)
}
