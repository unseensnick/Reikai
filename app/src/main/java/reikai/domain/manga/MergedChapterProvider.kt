package reikai.domain.manga

import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ChapterMatchKeys
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * RK: one-shot merge group resolver for the manga reader. Given the manga a chapter was opened from,
 * it resolves the whole merge group and returns the unified cross-source chapter list (reading
 * ordered), plus the group's manga keyed by id so the reader can build a per-source page loader.
 *
 * Reuses the same [MangaMergeManager] group math and [ChapterAggregation] stitcher the manga details
 * screen uses, so the reader's list matches what the user tapped. When the manga is not merged it
 * returns exactly the single-source list (zero behaviour change for the common case).
 *
 * [aggregate] is the shared aggregate + reading-order policy, also called by the details screen's
 * reactive flow so the ordering lives in one place.
 */
class MergedChapterProvider(
    private val getMangaWithChapters: GetMangaWithChapters = Injekt.get(),
    private val mergeManager: MangaMergeManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
) {

    /** The resolved group: every member manga keyed by id, the unified reading-ordered chapters, and
     *  each member's source name for per-source labels (empty when not merged). */
    class Group(
        val mangaById: Map<Long, Manga>,
        val chapters: List<Chapter>,
        val sourceNameByMangaId: Map<Long, String>,
    ) {
        val isMerged: Boolean get() = mangaById.size > 1
    }

    suspend fun load(anchor: Manga): Group {
        val ids = mergeManager.computeRelatedIds(anchor.id)
        if (ids.size <= 1) {
            return Group(
                mangaById = mapOf(anchor.id to anchor),
                chapters = getMangaWithChapters.awaitChapters(anchor.id, applyScanlatorFilter = true),
                sourceNameByMangaId = emptyMap(),
            )
        }
        val mangaById = ids.associateWith { getMangaWithChapters.awaitManga(it) }
        val chaptersBySource = ids.associateWith { getMangaWithChapters.awaitChapters(it, applyScanlatorFilter = true) }
        val sourceIdByManga = mangaById.mapValues { it.value.source }
        val sourceNameByMangaId = mangaById.mapValues { sourceManager.getOrStub(it.value.source).name }
        return Group(mangaById, aggregate(chaptersBySource, sourceIdByManga), sourceNameByMangaId)
    }

    /** Aggregate + reading order: stitch the sources into one list, then restamp source order so a
     *  "by source order" sort reads top to bottom instead of interleaving sources. Suspend because it
     *  resolves the group's per-source override; both callers (reader load, details flow) already are. */
    suspend fun aggregate(chaptersBySource: Map<Long, List<Chapter>>, sourceIdByManga: Map<Long, Long>): List<Chapter> {
        // True gallery sources (E-Hentai / ExHentai / nhentai / Pururin / 8Muses / HentaiFox / AsmHentai)
        // treat each chapter as a whole standalone gallery numbered 1, so exempt them from cross-source
        // number dedup: merging two keeps both instead of collapsing on "1". The predicate is shared with
        // the match-key reconciliation, so the stored key and this list agree on what dedups.
        val gallerySourceMangaIds = sourceIdByManga
            .filterValues { sourceId -> ChapterMatchKeys.isGallerySource(sourceId, sourceManager) }
            .keys
        // Members are the map keys, so any one resolves the group for its override ranking (empty = none).
        val memberRanking = chaptersBySource.keys.firstOrNull()
            ?.let { mergeManager.overrideRankingMemberIds(it) }
            .orEmpty()
        return ChapterAggregation
            .aggregate(
                chaptersBySource,
                sourceIdByManga,
                reikaiLibraryPreferences.preferredMangaSources.get(),
                gallerySourceMangaIds,
                memberRanking,
            )
            .let(::restampReadingOrder)
    }

    /** The member manga ids in trunk order (first = trunk), for ordering the manage-sources rows so the
     *  primary sits on top. Uses the same ranking as [aggregate]; [memberRanking] is the caller's
     *  already-resolved per-group override (empty = the global ranking wins). */
    fun rankedMemberIds(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long>,
        memberRanking: List<Long>,
    ): List<Long> = ChapterAggregation.rankedMemberIds(
        chaptersBySource,
        sourceIdByManga,
        reikaiLibraryPreferences.preferredMangaSources.get(),
        memberRanking,
    )

    private fun restampReadingOrder(chapters: List<Chapter>): List<Chapter> =
        chapters.sortedByDescending { it.chapterNumber }
            .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }

    /**
     * Re-add the [opened] chapter when the cross-source dedup dropped it (opened from history /
     * updates, or from a non-preferred source's chip).
     *
     * Restamped, because the re-added row still carries its own source's `sourceOrder` while the
     * unified list was renumbered to a single 0..N-1 scale, and the reader sorts on `sourceOrder`
     * alone. Two scales under one comparator drop it at an arbitrary index, which breaks prev/next
     * and leaves the reader describing a different chapter than it is showing.
     *
     * Returns [unified] untouched when there is nothing to add, so a single-source list never gets
     * renumbered over its own source's ordering.
     */
    fun withOpenedChapter(unified: List<Chapter>, opened: Chapter?): List<Chapter> = when {
        opened == null || unified.any { it.id == opened.id } -> unified
        else -> restampReadingOrder(unified + opened)
    }
}
