package yokai.data.novel

import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelChapter
import yokai.domain.novel.models.NovelTrack

/**
 * Mappers from the generated SQLDelight row types to the domain models. SQLDelight invokes
 * these as the `mapper:` argument on its `findAll` / `findById` / etc. queries; the parameter
 * order must match the column order declared in the `.sq` file.
 */

fun novelMapper(
    id: Long,
    source: String,
    url: String,
    title: String,
    author: String?,
    artist: String?,
    description: String?,
    genre: String?,
    status: Long,
    thumbnail_url: String?,
    favorite: Boolean,
    last_update: Long?,
    initialized: Boolean,
    chapter_flags: Long,
    date_added: Long?,
    update_strategy: Long,
    cover_last_modified: Long,
    total_pages: Long,
    last_read_at: Long?,
): Novel = Novel(
    id = id,
    source = source,
    url = url,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genres = genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
    status = status.toInt(),
    thumbnailUrl = thumbnail_url,
    favorite = favorite,
    lastUpdate = last_update ?: 0L,
    initialized = initialized,
    chapterFlags = chapter_flags.toInt(),
    dateAdded = date_added ?: 0L,
    updateStrategy = update_strategy.toInt(),
    coverLastModified = cover_last_modified,
    totalPages = total_pages.toInt(),
    lastReadAt = last_read_at,
)

fun novelTrackMapper(
    id: Long,
    novel_id: Long,
    sync_id: Long,
    remote_id: Long,
    library_id: Long?,
    title: String,
    last_chapter_read: Double,
    total_chapters: Long,
    status: Long,
    score: Double,
    remote_url: String,
    start_date: Long,
    finish_date: Long,
): NovelTrack = NovelTrack(
    id = id,
    novelId = novel_id,
    syncId = sync_id,
    remoteId = remote_id,
    libraryId = library_id,
    title = title,
    lastChapterRead = last_chapter_read.toFloat(),
    totalChapters = total_chapters,
    status = status.toInt(),
    score = score.toFloat(),
    remoteUrl = remote_url,
    startDate = start_date,
    finishDate = finish_date,
)

fun novelChapterMapper(
    id: Long,
    novel_id: Long,
    url: String,
    name: String,
    read: Boolean,
    bookmark: Boolean,
    last_text_progress: Long,
    chapter_number: Double,
    source_order: Long,
    date_fetch: Long,
    date_upload: Long,
    page: String,
): NovelChapter = NovelChapter(
    id = id,
    novelId = novel_id,
    url = url,
    name = name,
    read = read,
    bookmark = bookmark,
    lastTextProgress = last_text_progress.toInt(),
    chapterNumber = chapter_number.toFloat(),
    sourceOrder = source_order,
    dateFetch = date_fetch,
    dateUpload = date_upload,
    page = page,
)
