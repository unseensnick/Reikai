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
    val marks = GroupMarks.of(pooled, shown, stitch, id, read, bookmark)
    private val copies by lazy { CopyToOpen(scope, pooled, stitch, id, onDisk()) }

    /** The rows of [shown] that read as downloaded. */
    val downloadedIds: Set<Long> by lazy { shown.asSequence().filter(::isDownloaded).mapTo(HashSet(), id) }

    fun isRead(chapter: T): Boolean = marks.isRead(id(chapter), read(chapter))

    fun isBookmarked(chapter: T): Boolean = marks.isBookmarked(id(chapter), bookmark(chapter))

    fun isDownloaded(chapter: T): Boolean = copies.isOnDisk(id(chapter))
}

/**
 * The group-wide read and bookmarked rule, held as plain ids so a screen state can carry it: a row is
 * flagged when its own copy is, or when another source's copy of it is. Every surface asks through
 * here rather than reading the sets, which stay private so the rule cannot be restated beside it.
 * Bookmark writes reach every copy already, so that half shows only where the copies were never in
 * sync: a bookmark set before the merge, or one a backup restored onto a copy the stitch does not show.
 */
data class GroupMarks(
    private val readElsewhere: Set<Long> = emptySet(),
    private val bookmarkedElsewhere: Set<Long> = emptySet(),
) {
    fun isRead(id: Long, ownRead: Boolean): Boolean = ownRead || id in readElsewhere

    fun isBookmarked(id: Long, ownBookmark: Boolean): Boolean = ownBookmark || id in bookmarkedElsewhere

    companion object {
        /** An unmerged entry's marks: every row answers for itself. */
        val NONE = GroupMarks()

        /** The marks for the rows of [shown], from [pooled], every member's chapters, under [stitch]. */
        fun <T> of(
            pooled: List<T>,
            shown: List<T>,
            stitch: List<ChapterUnit>,
            id: (T) -> Long,
            read: (T) -> Boolean,
            bookmark: (T) -> Boolean,
        ) = GroupMarks(
            flaggedOnAnotherSource(pooled, shown, stitch, id, read),
            flaggedOnAnotherSource(pooled, shown, stitch, id, bookmark),
        )
    }
}
