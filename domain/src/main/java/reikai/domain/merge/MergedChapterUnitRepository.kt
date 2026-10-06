package reikai.domain.merge

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType

/**
 * Storage for what the cross-source stitch decided about a merge group's chapters. See
 * `data/merged_chapter_unit.sq` for why it is a rebuildable cache and why it is kept current by
 * reconciliation rather than by hooking every chapter write.
 */
interface MergedChapterUnitRepository {

    /** Groups of [contentType] whose stored stitch no longer matches the chapters behind them. */
    suspend fun getStaleGroups(contentType: ContentType): List<Long>

    /** [getStaleGroups] for one group, at the cost of that group's chapters rather than the library's. */
    suspend fun isStale(contentType: ContentType, groupId: Long): Boolean

    /** The ranking stamp each group was last stitched under, keyed by group; see [rankingStamps]. */
    suspend fun getRankings(): Map<Long, String>

    /**
     * [groupId]'s stored stitch, in merged order, for a screen about to render it. Chapters the stitch
     * dropped are left out, since they render nowhere. Empty when the group has never been stitched.
     */
    suspend fun getStitch(contentType: ContentType, groupId: Long): List<ChapterUnit>

    /**
     * Replace [groupId]'s stored stitch with [units] and the [ranking] stamp it was built under,
     * clearing what was there first, in one transaction. Passing no units clears the group, which is
     * what a group with too few library members left to stitch comes to; a null [ranking] records none.
     */
    suspend fun replaceGroup(contentType: ContentType, groupId: Long, units: List<StoredUnit>, ranking: String?)

    /**
     * Chapter counts per merge group, keyed by group. Every stitched group has an entry, zeros included.
     * A missing group has not been stitched yet, which a caller must show as its leading source's own
     * counts.
     */
    suspend fun getGroupCounts(contentType: ContentType): Map<Long, MergedGroupCounts>

    /** Reactive [getGroupCounts]: re-emits when the counts change, so a badge is not left showing what
     *  the group looked like before it was stitched. A write that leaves them as they were emits nothing,
     *  since every chapter write re-runs the query and the library rebuilds on each emission. */
    fun getGroupCountsAsFlow(contentType: ContentType): Flow<Map<Long, MergedGroupCounts>>

    /**
     * Every merged group's member chapters, keyed by group, with what a download probe needs to find
     * the file. Whether one is on disk lives on disk, not here, so the count is the caller's to take:
     * it probes these rows and counts the merged chapters that answer.
     *
     * A flow, not a read, emitting only when the rows changed: they change only when the chapters behind
     * a group do, while the library re-emits on far more than that, including once per finished download.
     */
    fun getDownloadUnitsAsFlow(contentType: ContentType): Flow<Map<Long, List<DownloadUnitRow>>>

    /**
     * Every library copy of each of [chapterIds]' merged chapters, keyed by the chapter asked for: what a
     * row naming one copy probes to say whether the copy a tap opens is on disk. Besides that chapter, the
     * same copies [getDownloadUnitsAsFlow] counts. The chapter asked for is always among its own copies,
     * even off the library or under an excluded scanlator, since callers look it up by id. A chapter no
     * stitched group places has no entry.
     */
    fun getCopiesAsFlow(contentType: ContentType, chapterIds: Collection<Long>): Flow<Map<Long, List<ChapterCopyRow>>>

    /**
     * How many distinct recognized chapter numbers each grouped library manga lists, keyed by manga id:
     * the count the stitch ranks the trunk on, so the collapsed library row leads on the same source the
     * details chapter list does. Read from the chapter rows, so a group not yet stitched has it too. A
     * manga absent from the map lists no recognized number, which ranks as zero. Emits only when a count
     * changed, like [getGroupCountsAsFlow].
     */
    fun getRecognizedChapterCountsAsFlow(): Flow<Map<Long, Long>>

    /**
     * One chapter's place in its group's stitch. [unit] is its position in the merged list, null when
     * the stitch dropped it. The derived values are the stitch's inputs, stored so a changed chapter
     * reads as stale: the identity's (which of name and number matter is per content type, and the
     * other is written anyway so one shape serves both tables) and [sourceOrder], its place in the walk.
     */
    data class StoredUnit(
        val chapterId: Long,
        val unit: Int?,
        val copyOrder: Int,
        val chapterName: String,
        val chapterNumber: Double,
        val sourceOrder: Long,
    )
}
