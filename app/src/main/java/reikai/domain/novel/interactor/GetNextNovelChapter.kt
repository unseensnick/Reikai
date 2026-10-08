package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.chapter.ReadingOrder
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.CopyToOpen
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope
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
import reikai.domain.novel.ownersOf

/** A merged novel's chapters in reading order. */
data class NovelGroupChapters(
    val chapters: List<NovelChapter>,
    /** The stored stitch behind [chapters], and every member's chapters, so a caller can ask a
     *  cross-source question (bookmarked anywhere, on disk anywhere) about the copies it stands in for. */
    val stitch: List<ChapterUnit> = emptyList(),
    val pooledChapters: List<NovelChapter> = chapters,
) {
    /** [chapters]' flags across the whole group, as the library's resume and download ask them; twin of
     *  manga's `MergedChapterProvider.Group?.groupFlags`, pinned by MergedResumeDownloadedConformanceTest. */
    fun groupFlags(onDisk: (pooled: List<NovelChapter>) -> Set<Long>) = GroupChapterFlags(
        MergeScope.Group,
        pooledChapters,
        chapters,
        stitch,
        { it.id },
        { it.read },
        { it.bookmark },
    ) { onDisk(pooledChapters) }
}

/**
 * [GetNextNovelChapter.readingRows]: [rows] in the reader's scope, plus the group behind them. [pooled] is
 * empty and [copies] null for a novel in no group; [copies] is null in source scope too.
 */
data class NovelReadingRows(
    val rows: List<NovelChapter>,
    val memberIds: List<Long>,
    val pooled: List<NovelChapter>,
    val stitch: List<ChapterUnit>,
    val copies: CopyToOpen<NovelChapter>?,
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
     * The group's chapters as one cross-source list, the same one the details "All" view shows. Ordered
     * by the novel's own chapter sort, ascending, which is the order its reader pages in; a merged list is
     * restamped to the stitch first, so the default "by source order" reads that cross-source order
     * rather than interleaving the sources.
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
            stitch = stitch,
            pooledChapters = pooled,
        )
    }

    /**
     * The rows [novelId]'s reader lists in its scope, unsorted. Group scope lists the group's unified rows,
     * each merged row swapped for the copy a tap opens, so a row is read from disk when any copy is there
     * and from a source [isInstalled] answers for when its own is gone; source scope lists the novel's own
     * rows. [downloadedIds] answers disk membership per owning novel.
     */
    suspend fun readingRows(
        novelId: Long,
        sourceScoped: Boolean,
        isInstalled: suspend (Novel) -> Boolean,
        downloadedIds: (List<NovelChapter>, Map<Long, Novel>) -> Set<Long>,
    ): NovelReadingRows {
        // Both scopes need the group: whether the story has been read is not a property of the row.
        val ids = mergeManager.computeRelatedIds(novelId).toList()
        val pooled = if (ids.size <= 1) emptyList() else ids.flatMap { chapterRepository.getByNovelId(it) }
        val stitch = if (pooled.isEmpty()) emptyList() else mergedChapterProvider.stitchOf(novelId)
        val memberIds = ids.ifEmpty { listOf(novelId) }
        if (sourceScoped || pooled.isEmpty()) {
            return NovelReadingRows(chapterRepository.getByNovelId(novelId), memberIds, pooled, stitch, copies = null)
        }
        val owners = novelRepository.ownersOf(pooled)
        val installed = owners.filterValues { isInstalled(it) }.keys
        val fetched = DownloadTargets.of(MergeScope.Group, pooled, pooled, stitch, {
            it.id
        }) { it.novelId in installed }
        val copies = CopyToOpen(MergeScope.Group, pooled, stitch, { it.id }, downloadedIds(pooled, owners), fetched)
        val rows = copies.inPlaceOf(mergedChapterProvider.merged(pooled, stitch)) { copy, row ->
            copy.copy(sourceOrder = row.sourceOrder)
        }
        return NovelReadingRows(rows, memberIds, pooled, stitch, copies)
    }

    /** One novel's own chapters, in the order its reader pages through them. The group's list above
     *  spans every source; this is the single-source list a caller projects rows back onto. */
    suspend fun ownSourceChapters(novelId: Long): List<NovelChapter> =
        chapterRepository.getByNovelId(novelId).sortedWith(readingOrder(novelId))

    /**
     * The group's first unread chapter among those the novel's own chapter filters list, skipping what
     * another of its sources has already read, and what the user hid unless only hidden chapters are
     * left. The filters are the details list's ([sortedAndFiltered]), as the manga library's resume
     * applies its manga's. [downloadedIds] answers disk membership for chapters of the given novels.
     */
    suspend fun awaitFirstUnreadInGroup(
        novelId: Long,
        downloadedOnly: Boolean,
        downloadedIds: (List<NovelChapter>, Map<Long, Novel>) -> Set<Long>,
    ): NovelChapter? {
        val group = groupChapters(novelId)
        val pooled = group.pooledChapters
        val novels = novelRepository.ownersOf(pooled)
        val flags = group.groupFlags { downloadedIds(it, novels) }
        val listed = listedByFilters(novelId, group.chapters, flags, downloadedOnly)
        return ReadingOrder.resumeAt(listed, hiddenAmong(pooled), flags::isRead)
    }

    /**
     * [chapters] the filters keep, still in reading order: the filter's own sort is display order. Read,
     * bookmarked and on disk are [flags]' group answers, so a chapter whose only copy on disk is another
     * source's passes a Downloaded filter: that is the copy the reader opens.
     */
    private suspend fun listedByFilters(
        novelId: Long,
        chapters: List<NovelChapter>,
        flags: GroupChapterFlags<NovelChapter>,
        downloadedOnly: Boolean,
    ): List<NovelChapter> {
        val novel = novelRepository.getById(novelId) ?: return chapters
        val kept = chapters.sortedAndFiltered(novel, novelPreferences, flags.downloadedIds, flags.marks, downloadedOnly)
            .mapTo(HashSet()) { it.id }
        return chapters.filter { it.id in kept }
    }

    /** Whether the user hid a chapter of [chapters], each copy keyed by the source of its own novel. */
    suspend fun hiddenAmong(chapters: List<NovelChapter>): (NovelChapter) -> Boolean {
        val hidden = novelPreferences.hiddenChapters().get()
        if (hidden.isEmpty()) return { false }
        val sourceOf = novelRepository.ownersOf(chapters).mapValues { it.value.source }
        return { chapter -> chapter.hiddenKey(sourceOf) in hidden }
    }

    /** [novelId]'s own chapter sort, ascending, the order its reader pages in. Falls back to source order
     *  for a novel that is no longer stored, which only a stale id reaches. */
    suspend fun readingOrder(novelId: Long): Comparator<NovelChapter> {
        val novel = novelRepository.getById(novelId) ?: return compareBy { it.sourceOrder }
        return readingOrderComparator(novel, novelPreferences)
    }
}
