package reikai.domain.manga

import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.source.MANGADEX_IDS
import exh.source.getMainSource
import reikai.domain.library.ReikaiLibraryPreferences
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
        val ids = mergeManager.computeRelatedMangaIds(anchor.id, anchor.title).ids
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
     *  "by source order" sort reads top to bottom instead of interleaving sources. */
    fun aggregate(chaptersBySource: Map<Long, List<Chapter>>, sourceIdByManga: Map<Long, Long>): List<Chapter> {
        // True gallery sources (E-Hentai / ExHentai / nhentai / Pururin / 8Muses / HentaiFox / AsmHentai)
        // treat each chapter as a whole standalone gallery numbered 1, so exempt them from cross-source
        // number dedup: merging two keeps both instead of collapsing on "1". They all implement
        // NamespaceSource, but so does the enhanced MangaDex, which has normal sequential chapters that
        // MUST dedup like any other source (otherwise a MangaDex + other-source merge never dedups). There
        // is no clean positive id-set for every gallery (installed extensions vary), so gate on
        // NamespaceSource and exclude MangaDex by its known ids.
        val gallerySourceMangaIds = sourceIdByManga
            .filterValues { sourceId ->
                sourceId !in MANGADEX_IDS && sourceManager.get(sourceId)?.getMainSource<NamespaceSource>() != null
            }
            .keys
        return ChapterAggregation
            .aggregate(
                chaptersBySource,
                sourceIdByManga,
                reikaiLibraryPreferences.preferredMangaSources.get(),
                gallerySourceMangaIds,
            )
            .let(::restampReadingOrder)
    }

    private fun restampReadingOrder(chapters: List<Chapter>): List<Chapter> =
        chapters.sortedByDescending { it.chapterNumber }
            .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }
}
