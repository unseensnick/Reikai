package reikai.data.novel

import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.keptCover
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import tachiyomi.data.Database
import tachiyomi.domain.library.service.LibraryPreferences

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
    thumbnailUrl = keptCover(existing.thumbnailUrl, parsed.thumbnailUrl),
    // A partial parse reporting 0 never shrinks a known page count.
    totalPages = parsed.totalPages.takeIf { it > 0L } ?: existing.totalPages,
    initialized = true,
)

/** What [refreshNovelFromSource] left stored: the merged novel, and the chapters its syncs report as new. */
data class NovelRefreshResult(val novel: Novel, val newChapters: List<NovelChapter>)

/**
 * Re-parse a favorited [novel] from its [source] and bring its stored data up to date: merge the
 * parsed metadata (persisting only on a change), sync the first page's chapters, walk any pages
 * opened since the previous [Novel.totalPages], then predict the next update once over the result.
 * Shared by the background update job and the details refresh. The browse-open path stays on
 * `insertOrGet` in the details model: that inserts a non-favorite shadow row and does not walk, a
 * genuinely different operation.
 */
suspend fun refreshNovelFromSource(
    novel: Novel,
    source: NovelSource,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    database: Database,
    libraryPreferences: LibraryPreferences,
    novelDownloadManager: NovelDownloadManager? = null,
    manualFetch: Boolean = false,
    fetchWindow: Pair<Long, Long> = Pair(0, 0),
): NovelRefreshResult {
    val sourceNovel = source.parseNovel(novel.url)
    val parsed = sourceNovel.toNovel(sourceId = source.id, favorite = novel.favorite)
    val merged = mergeRefreshedNovel(novel, parsed)
    if (merged != novel) novelRepository.update(merged)

    var synced: NovelChapterSyncResult? = null
    val firstChapters = sourceNovel.chapters.orEmpty()
    if (firstChapters.isNotEmpty()) {
        // A paged source's first page is page "1"; tag it so the page-"1" query finds these rows.
        val pageTag = if (sourceNovel.totalPages > 1) "1" else null
        synced = syncChaptersWithNovelSource(
            firstChapters,
            merged,
            novelChapterRepository,
            novelRepository,
            database,
            libraryPreferences,
            page = pageTag,
            novelDownloadManager = novelDownloadManager,
        )
    }
    if (merged.totalPages > 1L) {
        val walked = walkNovelPages(
            merged,
            source,
            maxOf(2L, novel.totalPages),
            merged.totalPages,
            novelChapterRepository,
            novelRepository,
            database,
            libraryPreferences,
            novelDownloadManager = novelDownloadManager,
        )
        synced = synced?.plus(walked) ?: walked
    }
    if (synced != null) {
        predictNovelFetchInterval(
            merged,
            synced.changed,
            manualFetch,
            novelChapterRepository,
            novelRepository,
            fetchWindow,
        )
    }
    return NovelRefreshResult(merged, synced?.newChapters.orEmpty())
}
