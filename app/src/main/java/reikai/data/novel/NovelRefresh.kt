package reikai.data.novel

import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.source.keptCover
import reikai.domain.source.refreshedTitle
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import tachiyomi.data.Database
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Overlay freshly [parsed] source metadata onto the stored [existing] novel. User edits live in the
 * non-destructive `custom_novel_info` overlay, applied on read, so every source-owned field takes the
 * source value (a null or blank parsed value never wipes existing data on a partial parse). The title
 * follows the one rule manga follows, [refreshedTitle]. Identity and library state stay [existing]'s.
 */
private fun mergeRefreshedNovel(existing: Novel, parsed: Novel, updateTitles: Boolean): Novel = existing.copy(
    // toNovel's placeholder for a nameless parse is not a title.
    title = refreshedTitle(parsed.title.takeIf { it != "Untitled" }, existing.favorite, updateTitles)
        ?: existing.title,
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

/**
 * Stores [parsed] over [existing] and returns the novel as merged: the one write every novel refresh
 * makes. Only the source-owned fields are written, as `UpdateMangaFromRemote` writes a partial
 * `MangaUpdate`, so a library change made after [existing] was read survives. A new title moves the
 * download folder with it, as manga's `renameManga` does.
 */
suspend fun storeRefreshedNovel(
    existing: Novel,
    parsed: Novel,
    novelRepository: NovelRepository,
    libraryPreferences: LibraryPreferences,
    novelDownloadManager: NovelDownloadManager?,
): Novel {
    val merged = mergeRefreshedNovel(existing, parsed, libraryPreferences.updateMangaTitles.get())
    if (merged == existing) return existing
    val newTitle = merged.title.takeIf { it != existing.title }
    val stored = novelRepository.update(
        NovelUpdate(
            id = existing.id,
            title = newTitle,
            author = merged.author,
            artist = merged.artist,
            description = merged.description,
            genre = merged.genre,
            status = merged.status,
            thumbnailUrl = merged.thumbnailUrl,
            totalPages = merged.totalPages,
            initialized = true,
        ),
    )
    if (stored && newTitle != null) novelDownloadManager?.renameNovel(existing, newTitle)
    return merged
}
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
    val merged = storeRefreshedNovel(novel, parsed, novelRepository, libraryPreferences, novelDownloadManager)

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
