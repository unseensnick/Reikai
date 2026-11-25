package reikai.domain.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.renderMergedReadingOrder
import reikai.domain.novel.model.NovelChapter

/**
 * A novel group's chapter list as the stored stitch decided it, never stitched at the call site. Twin
 * of [reikai.domain.manga.MergedChapterProvider], pinned by [ReconcileMergedChapters.currentStitch] and
 * [renderMergedReadingOrder], which both call, and by MergedTrunkConformanceTest for [rankedMemberIds];
 * only the chapter type differs.
 */
@Inject
@SingleIn(AppScope::class)
class NovelMergedChapterProvider(
    private val mergeManager: NovelMergeManager,
    private val reconcile: ReconcileMergedChapters,
    private val novels: NovelRepository,
    private val chapters: NovelChapterRepository,
    private val preferences: ReikaiLibraryPreferences,
) {

    /** The group's stored stitch, rebuilt first when it is stale. Empty when the novel is ungrouped. */
    suspend fun stitchOf(anchorId: Long): List<ChapterUnit> =
        mergeManager.groupIdOf(anchorId)?.let { reconcile.currentStitch(ContentType.NOVELS, it) }.orEmpty()

    /** [chapters] as the merged reading order [stitch] describes. */
    fun merged(chapters: List<NovelChapter>, stitch: List<ChapterUnit>): List<NovelChapter> =
        renderMergedReadingOrder(chapters, stitch, { it.id }) { chapter, order -> chapter.copy(sourceOrder = order) }

    /** The member novel ids in trunk order (first = trunk), for ordering the manage-sources rows. Ranks each
     *  member's stored source, installed or not, over its whole chapter list, as the stitch does;
     *  [memberRanking] is the group's override (empty = the global ranking wins). */
    suspend fun rankedMemberIds(memberIds: List<Long>, memberRanking: List<Long>): List<Long> =
        NovelChapterAggregation.ranking.rankIds(
            memberIds,
            { chapters.getByNovelId(it) },
            { novels.getById(it)?.source.orEmpty() },
            preferences.preferredNovelSources.get(),
            memberRanking,
        )
}
