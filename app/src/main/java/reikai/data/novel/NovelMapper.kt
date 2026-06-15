package reikai.data.novel

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import reikai.data.coil.NovelCover
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelChapter
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
    editedFlags: Long,
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
    editedFlags = editedFlags,
)

/**
 * Maps a `novelLibraryView` row to [LibraryNovel]. The first 20 args are the `novels` columns (same
 * order as [mapNovel]); the trailing 7 are the view's aggregates. `sum(...)` columns arrive as
 * `Double` (SQLDelight bypasses the Boolean adapter for aggregates), so they are narrowed to `Long`.
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
    editedFlags: Long,
    totalCount: Long,
    readCount: Double,
    latestUpload: Long,
    chapterFetchedAt: Long,
    downloadCount: Double,
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
        editedFlags,
    ),
    categories = categories.split(",").map { it.toLong() },
    totalChapters = totalCount,
    readCount = readCount.toLong(),
    bookmarkCount = bookmarkCount.toLong(),
    downloadCount = downloadCount.toLong(),
    latestUpload = latestUpload,
    chapterFetchedAt = chapterFetchedAt,
)

/**
 * Maps a `getDuplicateLibraryNovel` row to [NovelWithChapterCount]. The first 20 args are the
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
    editedFlags: Long,
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
        editedFlags,
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
    isDownloaded: Boolean,
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
    isDownloaded = isDownloaded,
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
    isDownloaded: Boolean,
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
    isDownloaded = isDownloaded,
)

fun mapNovelCategory(
    id: Long,
    name: String,
    sort: Long,
    flags: Long,
    novelOrder: String,
): NovelCategory = NovelCategory(
    id = id,
    name = name,
    order = sort,
    flags = flags,
    novelOrder = novelOrder,
)
