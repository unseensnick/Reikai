package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.chapter.ReadingOrder
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.hiddenKey
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.readingOrderComparator
import reikai.domain.novel.model.sortedAndFiltered

/** A merged novel's chapters in reading order, and the ids another source of the group already read. */
data class NovelGroupChapters(
    val chapters: List<NovelChapter>,
    val readInOtherSources: Set<Long> = emptySet(),
    /** The stored stitch behind [chapters], and every member's chapters, so a caller can ask a
     *  cross-source question (bookmarked anywhere, on disk anywhere) about the copies it stands in for. */
    val stitch: List<ChapterUnit> = emptyList(),
    val pooledChapters: List<NovelChapter> = chapters,
)

/**
 * Novel twin of [tachiyomi.domain.history.interactor.GetNextChapters]: where a novel starts reading,
 * over its own source or across its merge group. Resuming from a recorded chapter is not here, because
 * that rule is shared with manga and lives on the recents surface.
 */
@Inject
class GetNextNovelChapter(
    private val chapterRepository: NovelChapterRepository,
    private val novelRepository: NovelRepository,
    private val novelPreferences: NovelPreferences,
    private val mergeManager: NovelMergeManager,
    private val mergedChapterProvider: NovelMergedChapterProvider,
) {
    /**
     * The group's chapters as one cross-source list, the same one the details "All" view shows, plus
     * what counts as read on another source. Ordered by the novel's own chapter sort, ascending, which
     * is the order its reader pages in; a merged list is restamped to the stitch first, so the default
     * "by source order" reads that cross-source order rather than interleaving the sources.
     */
    suspend fun groupChapters(novelId: Long): NovelGroupChapters {
        val order = readingOrder(novelId)
        val memberIds = mergeManager.computeRelatedIds(novelId).toList()
        if (memberIds.size <= 1) {
            return NovelGroupChapters(chapterRepository.getByNovelId(novelId).sortedWith(order))
        }
        val pooled = memberIds.flatMap { chapterRepository.getByNovelId(it) }
        // The stored stitch, which is the cross-source reading order. A chapter number is not: it is
        // whatever its own source counted, so two sources of one novel disagree.
        val stitch = mergedChapterProvider.stitchOf(novelId)
        val unified = mergedChapterProvider.merged(pooled, stitch).sortedWith(order)
        return NovelGroupChapters(
            chapters = unified,
            readInOtherSources = flaggedOnAnotherSource(pooled, unified, stitch, { it.id }, { it.read }),
            stitch = stitch,
            pooledChapters = pooled,
        )
    }

    /** One novel's own chapters, in the order its reader pages through them. The group's list above
     *  spans every source; this is the single-source list a caller projects rows back onto. */
    suspend fun ownSourceChapters(novelId: Long): List<NovelChapter> =
        chapterRepository.getByNovelId(novelId).sortedWith(readingOrder(novelId))

    /**
     * The group's first unread chapter among those the novel's own chapter filters list, skipping what
     * another of its sources has already read, and what the user hid unless only hidden chapters are
     * left. The filters are the details list's ([sortedAndFiltered]), as the manga library's resume
     * applies its manga's. [downloadedIds] answers disk membership for one member's chapters.
     */
    suspend fun awaitFirstUnreadInGroup(
        novelId: Long,
        downloadedOnly: Boolean,
        downloadedIds: (Novel, List<NovelChapter>) -> Set<Long>,
    ): NovelChapter? {
        val group = groupChapters(novelId)
        val listed = listedByFilters(novelId, group, downloadedOnly, downloadedIds)
        val shown = ReadingOrder.hiddenLast(listed, hiddenAmong(group.pooledChapters))
        return ReadingOrder.nextToRead(shown) { it.read || it.id in group.readInOtherSources }
    }

    /** [group]'s chapters the filters keep, still in reading order: the filter's own sort is display order. */
    private suspend fun listedByFilters(
        novelId: Long,
        group: NovelGroupChapters,
        downloadedOnly: Boolean,
        downloadedIds: (Novel, List<NovelChapter>) -> Set<Long>,
    ): List<NovelChapter> {
        val novel = novelRepository.getById(novelId) ?: return group.chapters
        val downloaded = group.chapters.groupBy { it.novelId }.flatMapTo(HashSet()) { (memberId, chapters) ->
            novelRepository.getById(memberId)?.let { downloadedIds(it, chapters) }.orEmpty()
        }
        val kept = group.chapters.sortedAndFiltered(
            novel,
            novelPreferences,
            downloaded,
            group.readInOtherSources,
            flaggedOnAnotherSource(group.pooledChapters, group.chapters, group.stitch, { it.id }, { it.bookmark }),
            downloadedOnly,
        ).mapTo(HashSet()) { it.id }
        return group.chapters.filter { it.id in kept }
    }

    /** Whether the user hid a chapter of [chapters], each copy keyed by the source of its own novel. */
    suspend fun hiddenAmong(chapters: List<NovelChapter>): (NovelChapter) -> Boolean {
        val hidden = novelPreferences.hiddenChapters().get()
        if (hidden.isEmpty()) return { false }
        val sourceOf = chapters.mapTo(HashSet()) { it.novelId }.associateWith { novelRepository.getById(it)?.source }
        return { chapter -> chapter.hiddenKey(sourceOf) in hidden }
    }

    /** Falls back to source order for a novel that is no longer stored, which only a stale id reaches. */
    private suspend fun readingOrder(novelId: Long): Comparator<NovelChapter> {
        val novel = novelRepository.getById(novelId) ?: return compareBy { it.sourceOrder }
        return readingOrderComparator(novel, novelPreferences)
    }
}
