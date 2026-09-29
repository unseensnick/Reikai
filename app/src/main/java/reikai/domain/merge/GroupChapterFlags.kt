package reikai.domain.merge

/**
 * Read, bookmarked and on disk as a merge group answers them for the rows of [shown]. Read and
 * bookmarked are group-wide in every scope: whether the story was read is not a property of one copy.
 * On disk follows [scope] through [CopyToOpen], so a row reads as downloaded exactly when the copy a tap
 * opens is. [pooled] is every member's chapters; with an empty [stitch] each row answers for itself.
 * [onDisk] is only probed when a caller asks about downloads, since it costs a disk check per chapter.
 */
class GroupChapterFlags<T>(
    scope: MergeScope,
    pooled: List<T>,
    private val shown: List<T>,
    stitch: List<ChapterUnit>,
    private val id: (T) -> Long,
    private val read: (T) -> Boolean,
    private val bookmark: (T) -> Boolean,
    onDisk: () -> Set<Long>,
) {
    val readElsewhere = flaggedOnAnotherSource(pooled, shown, stitch, id, read)
    val bookmarkedElsewhere = flaggedOnAnotherSource(pooled, shown, stitch, id, bookmark)
    private val copies by lazy { CopyToOpen(scope, pooled, stitch, id, onDisk()) }

    /** The rows of [shown] that read as downloaded. */
    val downloadedIds: Set<Long> by lazy { shown.asSequence().filter(::isDownloaded).mapTo(HashSet(), id) }

    fun isRead(chapter: T): Boolean = read(chapter) || id(chapter) in readElsewhere

    fun isBookmarked(chapter: T): Boolean = bookmark(chapter) || id(chapter) in bookmarkedElsewhere

    fun isDownloaded(chapter: T): Boolean = copies.isOnDisk(id(chapter))
}
