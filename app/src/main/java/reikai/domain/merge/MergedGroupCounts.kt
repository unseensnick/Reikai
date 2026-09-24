package reikai.domain.merge

/**
 * A merge group's chapters as its stored stitch counts them: one per chapter the group covers, read or
 * bookmarked when any member source's copy is. Excluded scanlators and members outside the library are
 * already left out, so these are the numbers the group's chapter list shows.
 */
data class MergedGroupCounts(
    val total: Long,
    val read: Long,
    val bookmarked: Long,
) {
    val unread: Long get() = total - read
}
