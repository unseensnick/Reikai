package reikai.domain.manga

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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.source.service.SourceManager

/**
 * The manga half of keeping a merge group's stored stitch current, and the only place manga chapters
 * are stitched at all. Chapters are loaded WITHOUT the scanlator filter: an excluded scanlator is a
 * display choice, and the stored copy ranking lets a reader skip its rows and fall to the next source,
 * so excluding one needs no restitch.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class MangaGroupStitcher(
    private val mergeGroupRepository: MergeGroupRepository,
    private val getMangaWithChapters: GetMangaWithChapters,
    private val mergeManager: MangaMergeManager,
    private val sourceManager: SourceManager,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) : MergedGroupStitcher {

    override val contentType = ContentType.MANGA

    override suspend fun stitch(groupId: Long): GroupStitch {
        val group = load(groupId) ?: return GroupStitch(emptyList(), emptyMap())
        // True gallery sources (E-Hentai / ExHentai / nhentai / Pururin / 8Muses / HentaiFox / AsmHentai)
        // treat each chapter as a whole standalone gallery numbered 1, so exempt them from cross-source
        // number dedup: merging two keeps both instead of collapsing on "1".
        val gallerySourceMangaIds = group.sourceIdByManga
            .filterValues { sourceId -> GallerySources.isGallerySource(sourceId, sourceManager) }
            .keys
        val merged = ChapterAggregation.merge(
            // The stitched order follows each source's own, so that order is stated here rather than
            // left to whatever the rows come back in: the chapter query carries no ORDER BY.
            group.chaptersBySource.mapValues { (_, chapters) -> chapters.sortedBy { it.sourceOrder } },
            group.sourceIdByManga,
            reikaiLibraryPreferences.preferredMangaSources.get(),
            gallerySourceMangaIds,
            group.memberRanking,
        )
        val units = storedUnitsOf(
            chapters = group.chaptersBySource.values.flatten(),
            merged = merged,
            id = { it.id },
            name = { it.name },
            number = { it.chapterNumber },
            order = { it.sourceOrder },
        )
        // Saved so the library ranks members without re-reading every member chapter on each write.
        val counts = group.chaptersBySource.mapValues { (_, chapters) ->
            ChapterAggregation.recognizedChapterCount(chapters).toLong()
        }
        return GroupStitch(units, counts)
    }

    override suspend fun trunkNumbers(groupId: Long): Map<Long, Double> {
        val group = load(groupId) ?: return emptyMap()
        val trunk = ChapterAggregation.ranking.walkOrder(
            group.chaptersBySource,
            group.sourceIdByManga,
            reikaiLibraryPreferences.preferredMangaSources.get(),
            group.memberRanking,
        ).firstOrNull()?.memberId
        return group.chaptersBySource[trunk].orEmpty().associate { it.id to it.chapterNumber }
    }

    override suspend fun rankings(): Map<Long, String> =
        rankingStamps(
            mergeGroupRepository.getRankedMangaMembers(),
            reikaiLibraryPreferences.preferredMangaSources.get(),
        )

    private class Inputs(
        val chaptersBySource: Map<Long, List<Chapter>>,
        val sourceIdByManga: Map<Long, Long>,
        val memberRanking: List<Long>,
    )

    private suspend fun load(groupId: Long): Inputs? {
        val members = mergeGroupRepository.getFavoriteMembers(contentType, groupId)
        if (members.isEmpty()) return null
        return Inputs(
            chaptersBySource = members.associateWith { getMangaWithChapters.awaitChapters(it) },
            sourceIdByManga = members.associateWith { getMangaWithChapters.awaitManga(it).source },
            memberRanking = mergeManager.overrideRankingMemberIds(members.first()),
        )
    }
}
