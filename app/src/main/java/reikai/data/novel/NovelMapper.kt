package reikai.data.novel

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import reikai.data.coil.NovelCover
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.novel.model.NovelWithChapterCount

/**
 * Mappers from the generated SQLDelight row types to the domain models. SQLDelight invokes these
 * as the `mapper:` argument; the parameter order must match the column order declared in the
 * `.sq` file. The `genre` / `favorite` / `update_strategy` columns arrive already typed via the
 * registered column adapters, so no conversion happens here beyond null-coalescing the nullable
 * `last_update` / `date_added` columns.
 */

fun mapNovel(
    id: Long,
    source: String,
    url: String,
    title: String,
    author: String?,
    artist: String?,
    description: String?,
    genre: List<String>?,
    status: Long,
    thumbnailUrl: String?,
    favorite: Boolean,
    lastUpdate: Long?,
    initialized: Boolean,
    chapterFlags: Long,
    dateAdded: Long?,
    updateStrategy: UpdateStrategy,
    coverLastModified: Long,
    totalPages: Long,
    lastReadAt: Long?,
    notes: String,
    viewerFlags: Long,
    version: Long,
    @Suppress("UNUSED_PARAMETER") isSyncing: Long,
): Novel = Novel(
    id = id,
    source = source,
    url = url,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genre = genre,
    status = status,
    thumbnailUrl = thumbnailUrl,
    favorite = favorite,
    lastUpdate = lastUpdate ?: 0L,
    initialized = initialized,
    chapterFlags = chapterFlags,
    dateAdded = dateAdded ?: 0L,
    updateStrategy = updateStrategy,
    coverLastModified = coverLastModified,
    totalPages = totalPages,
    lastReadAt = lastReadAt,
    notes = notes,
    viewerFlags = viewerFlags,
    version = version,
)

/**
 * Maps a `novelLibraryView` row to [LibraryNovel]. The first 21 args are the `novels` columns (same
 * order as [mapNovel]); the trailing 6 are the view's aggregates. `sum(...)` columns arrive as
 * `Double` (SQLDelight bypasses the Boolean adapter for aggregates), so they are narrowed to `Long`.
 * The download count is not in the view: it comes from NovelDownloadCache (disk), filled in the model.
 */
fun mapLibraryNovel(
    id: Long,
    source: String,
    url: String,
    title: String,
    author: String?,
    artist: String?,
    description: String?,
    genre: List<String>?,
    status: Long,
    thumbnailUrl: String?,
    favorite: Boolean,
    lastUpdate: Long?,
    initialized: Boolean,
    chapterFlags: Long,
    dateAdded: Long?,
    updateStrategy: UpdateStrategy,
    coverLastModified: Long,
    totalPages: Long,
    lastReadAt: Long?,
    notes: String,
    viewerFlags: Long,
    version: Long,
    isSyncing: Long,
    totalCount: Long,
    readCount: Double,
    latestUpload: Long,
    chapterFetchedAt: Long,
    bookmarkCount: Double,
    categories: String,
): LibraryNovel = LibraryNovel(
    novel = mapNovel(
        id,
        source,
        url,
        title,
        author,
        artist,
        description,
        genre,
        status,
        thumbnailUrl,
        favorite,
        lastUpdate,
        initialized,
        chapterFlags,
        dateAdded,
        updateStrategy,
        coverLastModified,
        totalPages,
        lastReadAt,
        notes,
        viewerFlags,
        version,
        isSyncing,
    ),
    categories = categories.split(",").map { it.toLong() },
    totalChapters = totalCount,
    readCount = readCount.toLong(),
    bookmarkCount = bookmarkCount.toLong(),
    // placeholder; NovelLibraryScreenModel fills this from NovelDownloadCache
    downloadCount = 0,
    latestUpload = latestUpload,
    chapterFetchedAt = chapterFetchedAt,
)

/**
 * Maps a `getDuplicateLibraryNovel` row to [NovelWithChapterCount]. The first 21 args are the
 * `novels` columns (same order as [mapNovel]); the trailing arg is the joined chapter count.
 */
fun mapNovelWithChapterCount(
    id: Long,
    source: String,
    url: String,
    title: String,
    author: String?,
    artist: String?,
    description: String?,
    genre: List<String>?,
    status: Long,
    thumbnailUrl: String?,
    favorite: Boolean,
    lastUpdate: Long?,
    initialized: Boolean,
    chapterFlags: Long,
    dateAdded: Long?,
    updateStrategy: UpdateStrategy,
    coverLastModified: Long,
    totalPages: Long,
    lastReadAt: Long?,
    notes: String,
    viewerFlags: Long,
    version: Long,
    isSyncing: Long,
    chapterCount: Long,
): NovelWithChapterCount = NovelWithChapterCount(
    novel = mapNovel(
        id,
        source,
        url,
        title,
        author,
        artist,
        description,
        genre,
        status,
        thumbnailUrl,
        favorite,
        lastUpdate,
        initialized,
        chapterFlags,
        dateAdded,
        updateStrategy,
        coverLastModified,
        totalPages,
        lastReadAt,
        notes,
        viewerFlags,
        version,
        isSyncing,
    ),
    chapterCount = chapterCount,
)

/**
 * Maps a `novelUpdatesView` row to [NovelUpdateWithRelations]. Param order matches the view's SELECT.
 * The feed sorts/groups/filters on `dateFetch` (date_upload is unreliable for LN sources). The cover
 * carries no source site (favorites load from the library cover cache).
 */
fun mapNovelUpdate(
    novelId: Long,
    novelTitle: String,
    chapterId: Long,
    chapterName: String,
    chapterUrl: String,
    read: Boolean,
    bookmark: Boolean,
    lastTextProgress: Long,
    source: String,
    favorite: Boolean,
    thumbnailUrl: String?,
    coverLastModified: Long,
    dateFetch: Long,
    novelUrl: String,
): NovelUpdateWithRelations = NovelUpdateWithRelations(
    novelId = novelId,
    novelTitle = novelTitle,
    chapterId = chapterId,
    chapterName = chapterName,
    chapterUrl = chapterUrl,
    read = read,
    bookmark = bookmark,
    lastTextProgress = lastTextProgress,
    source = source,
    dateFetch = dateFetch,
    novelUrl = novelUrl,
    coverData = NovelCover(
        url = thumbnailUrl,
        site = null,
        isNovelFavorite = favorite,
        lastModified = coverLastModified,
        novelId = novelId,
    ),
)

/**
 * Maps a `novelHistoryView` row to [NovelHistoryWithRelations]. Param order matches the `novelHistory`
 * / `getLatestNovelHistory` SELECT. The cover carries no source site (favorites load from the library
 * cover cache), mirroring [mapNovelUpdate].
 */
fun mapNovelHistoryWithRelations(
    id: Long,
    novelId: Long,
    chapterId: Long,
    title: String,
    thumbnailUrl: String?,
    favorite: Boolean,
    coverLastModified: Long,
    chapterNumber: Double,
    readAt: Long?,
    readDuration: Long,
): NovelHistoryWithRelations = NovelHistoryWithRelations(
    id = id,
    chapterId = chapterId,
    novelId = novelId,
    title = title,
    chapterNumber = chapterNumber,
    readAt = readAt,
    readDuration = readDuration,
    coverData = NovelCover(
        url = thumbnailUrl,
        site = null,
        isNovelFavorite = favorite,
        lastModified = coverLastModified,
        novelId = novelId,
    ),
)

fun mapNovelChapter(
    id: Long,
    novelId: Long,
    url: String,
    name: String,
    read: Boolean,
    bookmark: Boolean,
    lastTextProgress: Long,
    chapterNumber: Double,
    sourceOrder: Long,
    dateFetch: Long,
    dateUpload: Long,
    page: String,
    @Suppress("UNUSED_PARAMETER") isDownloaded: Boolean,
): NovelChapter = NovelChapter(
    id = id,
    novelId = novelId,
    url = url,
    name = name,
    read = read,
    bookmark = bookmark,
    lastTextProgress = lastTextProgress,
    chapterNumber = chapterNumber,
    sourceOrder = sourceOrder,
    dateFetch = dateFetch,
    dateUpload = dateUpload,
    page = page,
    // is_downloaded column ignored: downloaded state now comes from NovelDownloadCache (disk).
)
