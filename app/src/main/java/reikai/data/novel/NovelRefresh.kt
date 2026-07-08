package reikai.data.novel

import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSource
import tachiyomi.data.Database

/**
 * Overlay freshly [parsed] source metadata onto the stored [existing] novel. Edits now live in the
 * non-destructive `custom_novel_info` overlay, so a refresh takes the source value for every
 * source-owned field, including the title (a null/blank parsed value never wipes existing data on a
 * partial parse). A deliberate rename lives in the overlay and still wins on the display; refreshing
 * the row title lets a legacy destructive-era title edit recover its source value. Identity and library
 * state are preserved from [existing]; the edit overlay is applied on read, not here.
 */
fun mergeRefreshedNovel(existing: Novel, parsed: Novel): Novel = existing.copy(
    // Track the source title too (the overlay masks it when the user renamed via Edit info). Guard the
    // toNovel placeholder so a nameless parse doesn't overwrite a real title with "Untitled".
    title = parsed.title.takeIf { it.isNotBlank() && it != "Untitled" } ?: existing.title,
    author = parsed.author?.takeIf { it.isNotBlank() } ?: existing.author,
    artist = parsed.artist?.takeIf { it.isNotBlank() } ?: existing.artist,
    description = parsed.description?.takeIf { it.isNotBlank() } ?: existing.description,
    genre = parsed.genre?.takeIf { it.isNotEmpty() } ?: existing.genre,
    // Source UNKNOWN (0) doesn't clobber a known stored status.
    status = parsed.status.takeIf { it != NovelStatusCode.UNKNOWN.toLong() } ?: existing.status,
    thumbnailUrl = parsed.thumbnailUrl?.takeIf { it.isNotBlank() } ?: existing.thumbnailUrl,
    // A partial parse reporting 0 never shrinks a known page count.
    totalPages = parsed.totalPages.takeIf { it > 0L } ?: existing.totalPages,
    initialized = true,
)

/**
 * Re-parse a favorited [novel] from its [source] and bring its stored data up to date: merge the
 * freshly parsed metadata (persisting only when it changed), sync the first page's chapters, then walk
 * any pages opened since the novel's previous [Novel.totalPages]. Returns the merged novel (carrying the
 * new totalPages).
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
