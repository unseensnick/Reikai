package reikai.domain.merge

/**
 * Read, bookmarked and on disk as a merge group answers them for the rows of [shown]: a row carries a
 * flag when any source's copy of it does, the answer the details lists give. Every reader surface that
 * shows, filters, skips or queues a merged row asks here, so none can fall back to the one copy the
 * stitch happened to keep. [pooled] is every member's chapters; with an empty [stitch] each row answers
 * for itself. [onDisk] names the pooled chapters whose own copy is on disk, and is only probed when a
 * caller asks [isDownloaded], since it costs a disk check per chapter of every member.
 */
class GroupChapterFlags<T>(
    pooled: List<T>,
    shown: List<T>,
    stitch: List<ChapterUnit>,
    private val id: (T) -> Long,
    private val read: (T) -> Boolean,
    private val bookmark: (T) -> Boolean,
    onDisk: () -> Set<Long>,
) {
    private val readElsewhere = flaggedOnAnotherSource(pooled, shown, stitch, id, read)
    private val bookmarkedElsewhere = flaggedOnAnotherSource(pooled, shown, stitch, id, bookmark)
    private val downloaded by lazy {
        val own = onDisk()
        own + flaggedOnAnotherSource(pooled, shown, stitch, id) { id(it) in own }
    }

    fun isRead(chapter: T): Boolean = read(chapter) || id(chapter) in readElsewhere

    fun isBookmarked(chapter: T): Boolean = bookmark(chapter) || id(chapter) in bookmarkedElsewhere

    fun isDownloaded(chapter: T): Boolean = id(chapter) in downloaded
}
