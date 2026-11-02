package reikai.domain.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository.GroupStitch
import reikai.domain.merge.MergedGroupStitcher
import reikai.domain.merge.rankingStamps
import reikai.domain.merge.storedUnitsOf
import reikai.domain.novel.model.NovelChapter

/**
 * The novel half of keeping a merge group's stored stitch current. Twin of
 * [reikai.domain.manga.MangaGroupStitcher], pinned by MergedTrunkConformanceTest,
 * MergedStitchReconcileTest and TrackedChapterNumberConformanceTest; the novel stitch reads no scanlator
 * filter and has no gallery notion, so this side only differs in where the chapters and the source
 * ranking come from.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class NovelGroupStitcher(
    private val mergeGroupRepository: MergeGroupRepository,
    private val novelRepository: NovelRepository,
    private val chapterRepository: NovelChapterRepository,
    private val mergeManager: NovelMergeManager,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) : MergedGroupStitcher {

    override val contentType = ContentType.NOVELS

    override suspend fun stitch(groupId: Long): GroupStitch {
        val group = load(groupId) ?: return GroupStitch(emptyList(), emptyMap())
        val merged = NovelChapterAggregation.merge(
            group.chaptersByNovel,
            group.sourceIdByNovel,
            reikaiLibraryPreferences.preferredNovelSources.get(),
            group.memberRanking,
        )
        val units = storedUnitsOf(
            chapters = group.chaptersByNovel.values.flatten(),
            merged = merged,
            id = { it.id },
            name = { it.name },
            number = { it.chapterNumber },
            order = { it.sourceOrder },
        )
        // No counts: the novel stitch ranks members on row count, which the library row already carries.
        return GroupStitch(units, recognizedCounts = emptyMap())
    }

    override suspend fun trunkNumbers(groupId: Long): Map<Long, Double> {
        val group = load(groupId) ?: return emptyMap()
        val trunk = NovelChapterAggregation.ranking.walkOrder(
            group.chaptersByNovel,
            group.sourceIdByNovel,
            reikaiLibraryPreferences.preferredNovelSources.get(),
            group.memberRanking,
        ).firstOrNull()?.memberId
        return group.chaptersByNovel[trunk].orEmpty().associate { it.id to it.chapterNumber }
    }

    override suspend fun rankings(): Map<Long, String> =
        rankingStamps(
            mergeGroupRepository.getRankedNovelMembers(),
            reikaiLibraryPreferences.preferredNovelSources.get(),
        )

    private class Inputs(
        val chaptersByNovel: Map<Long, List<NovelChapter>>,
        val sourceIdByNovel: Map<Long, String>,
        val memberRanking: List<Long>,
    )

    private suspend fun load(groupId: Long): Inputs? {
        val members = mergeGroupRepository.getFavoriteMembers(contentType, groupId)
        if (members.isEmpty()) return null
        return Inputs(
            chaptersByNovel = members.associateWith { chapterRepository.getByNovelId(it) },
            sourceIdByNovel = members.associateWith { novelRepository.getById(it)?.source.orEmpty() },
            // Any member resolves the group for its per-group ranking override (empty = none).
            memberRanking = mergeManager.overrideRankingMemberIds(members.first()),
        )
    }
}
