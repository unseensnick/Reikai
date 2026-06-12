package reikai.data.novel

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelChapter

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
