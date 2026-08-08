package reikai.domain.novel.interactor

import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelChapter

/** A merged novel's chapters in reading order, and the ids another source of the group already read. */
data class NovelGroupChapters(
    val chapters: List<NovelChapter>,
    val readInOtherSources: Set<Long> = emptySet(),
)

/**
 * Novel twin of [tachiyomi.domain.history.interactor.GetNextChapters] for History-tab resume: given a
 * recorded chapter, return the chapter to reopen. If the recorded chapter isn't fully read, reopen it;
 * otherwise the next chapter in reading order (source_order). Null when there is nothing after it.
 */
class GetNextNovelChapter(
    private val chapterRepository: NovelChapterRepository,
    private val novelRepository: NovelRepository,
    private val mergeManager: NovelMergeManager,
    private val libraryPreferences: ReikaiLibraryPreferences,
) {
    suspend fun await(novelId: Long, fromChapterId: Long): NovelChapter? {
        val chapters = chapterRepository.getByNovelId(novelId) // ordered by source_order
        val index = chapters.indexOfFirst { it.id == fromChapterId }
        if (index < 0) return null
        return if (!chapters[index].read) chapters[index] else chapters.getOrNull(index + 1)
    }

    /**
     * The first unread chapter, for a row with no recorded chapter to resume from (the recents
     * surface's newly-added lane). Twin of `GetNextChapters.await(mangaId, onlyUnread = true)`, which
     * the manga side already had; without it the lane could only resolve a target for manga.
     */
    suspend fun awaitFirstUnread(novelId: Long): NovelChapter? =
        chapterRepository.getByNovelId(novelId).firstOrNull { !it.read }

    /**
     * The group's chapters as one cross-source list, the same one the details "All" view shows, plus
     * what counts as read on another source. An unmerged novel gets its own list in source order.
     */
    suspend fun groupChapters(novelId: Long): NovelGroupChapters {
        val memberIds = mergeManager.computeRelatedIds(novelId).toList()
        if (memberIds.size <= 1) {
            return NovelGroupChapters(chapterRepository.getByNovelId(novelId).sortedBy { it.sourceOrder })
        }
        val byNovel = memberIds.associateWith { chapterRepository.getByNovelId(it) }
        val sourceIdByNovel = memberIds.associateWith { novelRepository.getById(it)?.source.orEmpty() }
        val unified = NovelChapterAggregation.aggregate(
            byNovel,
            sourceIdByNovel,
            libraryPreferences.preferredNovelSources.get(),
            mergeManager.overrideRankingMemberIds(novelId),
        )
            // chapterNumber is the cross-source reading order (sourceOrder isn't comparable across sources).
            .sortedBy { it.chapterNumber }
        return NovelGroupChapters(unified, NovelChapterAggregation.readInOtherSources(byNovel, unified))
    }

    /** The group's first unread chapter, skipping what another of its sources has already read. */
    suspend fun awaitFirstUnreadInGroup(novelId: Long): NovelChapter? {
        val group = groupChapters(novelId)
        return group.chapters.firstOrNull { !it.read && it.id !in group.readInOtherSources }
    }
}
