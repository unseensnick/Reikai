package reikai.data.novel

import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.mergeRefreshedNovel
import reikai.novel.source.NovelSource
import tachiyomi.data.Database

/**
 * Re-parse a favorited [novel] from its [source] and bring its stored data up to date: merge the
 * freshly parsed metadata under the edit-lock (persisting only when it changed), sync the first
 * page's chapters, then walk any pages opened since the novel's previous [Novel.totalPages]. Returns
 * the merged novel (carrying the new totalPages).
 *
 * Shared by the background update job ([reikai.data.novel.update.NovelUpdateJob]) and the details
 * refresh. The browse-open path stays on `insertOrGet` in the details model: that inserts a
 * non-favorite shadow row and does not walk, a genuinely different operation.
 */
suspend fun refreshNovelFromSource(
    novel: Novel,
    source: NovelSource,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    database: Database,
): Novel {
    val sourceNovel = source.parseNovel(novel.url)
    val parsed = sourceNovel.toNovel(sourceId = source.id, favorite = novel.favorite)
    val merged = mergeRefreshedNovel(novel, parsed)
    if (merged != novel) novelRepository.update(merged)

    val firstChapters = sourceNovel.chapters.orEmpty()
    if (firstChapters.isNotEmpty()) {
        // A paged source's first page is page "1"; tag it so the page-"1" query finds these rows.
        val pageTag = if (sourceNovel.totalPages > 1) "1" else null
        syncChaptersWithNovelSource(firstChapters, merged, novelChapterRepository, novelRepository, database, page = pageTag)
    }
    if (merged.totalPages > 1L) {
        walkNovelPages(merged, source, maxOf(2L, novel.totalPages), merged.totalPages, novelChapterRepository, novelRepository, database)
    }
    return merged
}
