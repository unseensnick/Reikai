package reikai.data.novel

import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.hasCustomCover
import reikai.domain.source.keptCover
import reikai.domain.source.keptDetail
import reikai.domain.source.keptGenres
import reikai.domain.source.keptStatus
import reikai.domain.source.refreshedCover
import reikai.domain.source.refreshedTitle
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelSource
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.time.Clock

/** What a refresh makes of the details already stored for a novel. */
enum class StoredDetails {
    /** The novel's own: a field the parse leaves out keeps its stored value, and the title follows [refreshedTitle]. */
    KEPT,

    /**
     * Perhaps another novel's, as the repair finds them: it cannot tell a victim from the neighbour it copied. A parse
     * naming the novel by another title proves them another's, so every source-owned field, title included, takes the
     * parse's and one it leaves out is cleared. Any other parse keeps them, as [KEPT] does.
     */
    SUSPECT,
}

/**
 * Overlay freshly [parsed] source metadata onto the stored [existing] novel. User edits live in the
 * non-destructive `custom_novel_info` overlay, applied on read, so every source-owned field takes the
 * source value; one the parse leaves out falls back to [kept]'s, which is [existing] unless its details
 * are another novel's. Identity and library state stay [existing]'s.
 */
private fun mergeRefreshedNovel(existing: Novel, parsed: Novel, updateTitles: Boolean, kept: Novel): Novel =
    existing.copy(
        // A title cannot be cleared.
        title = refreshedTitle(parsed.sentTitle, existing.favorite, updateTitles) ?: existing.title,
        author = keptDetail(kept.author, parsed.author),
        artist = keptDetail(kept.artist, parsed.artist),
        description = keptDetail(kept.description, parsed.description),
        genre = keptGenres(kept.genre, parsed.genre),
        status = keptStatus(kept.status, parsed.status),
        thumbnailUrl = keptCover(kept.thumbnailUrl, parsed.thumbnailUrl),
        // A partial parse reporting 0 never shrinks a known page count.
        totalPages = parsed.totalPages.takeIf { it > 0L } ?: kept.totalPages,
        initialized = true,
    )

private val Novel.sentTitle: String?
    get() = sentNovelName(title)

/** Whether a [StoredDetails.SUSPECT] novel's [parsed] details prove its stored ones another novel's. */
private fun wearsAnothersDetails(existing: Novel, parsed: Novel): Boolean {
    val sent = parsed.sentTitle ?: return false
    return !sent.trim().equals(existing.title.trim(), ignoreCase = true)
}

/**
 * Stores [parsed] over [existing] and returns the novel as merged: the one write every novel refresh
 * makes. Only the source-owned fields are written, as `UpdateMangaFromRemote` writes a partial
 * `MangaUpdate`, so a library change made after [existing] was read survives. A new title moves the
 * download folder with it, as manga's `renameManga` does. The cover follows manga's rule, [refreshedCover].
 */
suspend fun storeRefreshedNovel(
    existing: Novel,
    parsed: Novel,
    novelRepository: NovelRepository,
    libraryPreferences: LibraryPreferences,
    novelDownloadManager: NovelDownloadManager?,
    coverCache: CoverCache,
    manualFetch: Boolean = false,
    details: StoredDetails = StoredDetails.KEPT,
): Novel {
    val replaced = details == StoredDetails.SUSPECT && wearsAnothersDetails(existing, parsed)
    val updateTitles = replaced || libraryPreferences.updateMangaTitles.get()
    val merged = mergeRefreshedNovel(existing, parsed, updateTitles, kept = if (replaced) Novel.create() else existing)
    // Novels have no local source, so a cover is never only stamped.
    val refreshedUrl = keptCover(null, parsed.thumbnailUrl)
    val cover = refreshedCover(existing.thumbnailUrl, refreshedUrl, manualFetch, isLocal = false) {
        existing.hasCustomCover(coverCache)
    }
    if (cover.deletesCachedFile) coverCache.getCoverFile(existing.thumbnailUrl)?.delete()
    val coverLastModified = Clock.System.now().toEpochMilliseconds().takeIf { cover.stamps }
    if (merged == existing && coverLastModified == null) return existing
    val newTitle = merged.title.takeIf { it != existing.title }
    val stored = novelRepository.update(
        NovelUpdate(existing.id) {
            title = newTitle
            author = merged.author
            artist = merged.artist
            description = merged.description
            genre = merged.genre
            status = merged.status
            thumbnailUrl = merged.thumbnailUrl
            this.coverLastModified = coverLastModified
            totalPages = merged.totalPages
            initialized = true
        },
    )
    if (stored && newTitle != null) novelDownloadManager?.renameNovel(existing, newTitle)
    return coverLastModified?.let { merged.copy(coverLastModified = it) } ?: merged
}

/** What [refreshNovelFromSource] left stored: the merged novel, and the chapters its syncs report as new. */
data class NovelRefreshResult(val novel: Novel, val newChapters: List<NovelChapter>)

/**
 * Re-parse a favorited [novel] from its [source] and bring its stored data up to date: merge the
 * parsed metadata (persisting only on a change), sync the first page's chapters, walk any pages
 * opened since the previous [Novel.totalPages], then predict the next update once over the result.
 * Shared by the update job and the details refresh; browse-open inserts a shadow row instead. Fails as
 * manga's refresh does: [NoChaptersException] when the source lists no chapter, else what the source threw.
 */
suspend fun refreshNovelFromSource(
    novel: Novel,
    source: NovelSource,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    libraryPreferences: LibraryPreferences,
    numberOverrides: ChapterNumberOverrideRepository,
    coverCache: CoverCache,
    novelDownloadManager: NovelDownloadManager? = null,
    manualFetch: Boolean = false,
    fetchWindow: Pair<Long, Long> = Pair(0, 0),
    details: StoredDetails = StoredDetails.KEPT,
): NovelRefreshResult {
    val sourceNovel = source.parseNovel(novel.url)
    val parsed = sourceNovel.toNovel(sourceId = source.id, favorite = novel.favorite)
    val merged = storeRefreshedNovel(
        novel,
        parsed,
        novelRepository,
        libraryPreferences,
        novelDownloadManager,
        coverCache,
        manualFetch,
        details,
    )

    // After the details are stored, as manga's sync throws after its details write.
    if (sourceNovel.chapters.isNullOrEmpty() && merged.totalPages <= 1L) throw NoChaptersException()

    var synced = syncFirstPage(
        sourceNovel,
        merged,
        novelChapterRepository,
        novelRepository,
        libraryPreferences,
        numberOverrides,
        novelDownloadManager,
    )
    if (merged.totalPages > 1L) {
        val walked = walkNovelPages(
            merged,
            source,
            maxOf(2L, novel.totalPages),
            merged.totalPages,
            novelChapterRepository,
            novelRepository,
            libraryPreferences,
            numberOverrides,
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

/**
 * Stores a novel the user opened but has not added: a non-favourite row with its first page of chapters,
 * so it can be viewed without being silently added. [NovelRepository.insertOrGet] reuses a row created
 * meanwhile instead of duplicating it. Null when the row could not be written.
 */
suspend fun insertOpenedNovel(
    sourceNovel: SourceNovel,
    sourceId: String,
    novelRepository: NovelRepository,
    novelChapterRepository: NovelChapterRepository,
    libraryPreferences: LibraryPreferences,
    numberOverrides: ChapterNumberOverrideRepository,
    novelDownloadManager: NovelDownloadManager? = null,
): Novel? {
    val target = novelRepository.insertOrGet(sourceNovel.toNovel(sourceId = sourceId, favorite = false))
        ?: return null
    syncOpenedChapters(
        sourceNovel,
        target,
        novelRepository,
        novelChapterRepository,
        libraryPreferences,
        numberOverrides,
        novelDownloadManager,
    )
    return target
}

/**
 * Syncs the first page of an opened novel's chapters and predicts its next update. Only the first page:
 * the details screen fetches the others as they are shown, where [refreshNovelFromSource] walks them all.
 */
suspend fun syncOpenedChapters(
    sourceNovel: SourceNovel,
    target: Novel,
    novelRepository: NovelRepository,
    novelChapterRepository: NovelChapterRepository,
    libraryPreferences: LibraryPreferences,
    numberOverrides: ChapterNumberOverrideRepository,
    novelDownloadManager: NovelDownloadManager? = null,
) {
    val synced = syncFirstPage(
        sourceNovel,
        target,
        novelChapterRepository,
        novelRepository,
        libraryPreferences,
        numberOverrides,
        novelDownloadManager,
    ) ?: return
    predictNovelFetchInterval(target, synced.changed, manualFetch = false, novelChapterRepository, novelRepository)
}

/** Syncs the chapters [sourceNovel] parsed with [novel], or returns null when it parsed none. */
private suspend fun syncFirstPage(
    sourceNovel: SourceNovel,
    novel: Novel,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    libraryPreferences: LibraryPreferences,
    numberOverrides: ChapterNumberOverrideRepository,
    novelDownloadManager: NovelDownloadManager?,
): NovelChapterSyncResult? {
    val chapters = sourceNovel.chapters?.takeIf { it.isNotEmpty() } ?: return null
    // A paged source's first page is page "1"; tag it so the page-"1" query finds these rows.
    val pageTag = if (sourceNovel.totalPages > 1) "1" else null
    return syncChaptersWithNovelSource(
        chapters,
        novel,
        novelChapterRepository,
        novelRepository,
        libraryPreferences,
        numberOverrides,
        page = pageTag,
        novelDownloadManager = novelDownloadManager,
    )
}
