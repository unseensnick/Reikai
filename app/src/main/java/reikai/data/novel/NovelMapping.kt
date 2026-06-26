package reikai.data.novel

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import tachiyomi.i18n.MR

/**
 * Status codes used by the `novels.status` column. Mirrors the lnreader NovelStatus enum the
 * plugins return as strings; kept as ints to match the manga side's `mangas.status` column shape,
 * even though the value spaces don't overlap.
 */
object NovelStatusCode {
    const val UNKNOWN = 0
    const val ONGOING = 1
    const val COMPLETED = 2
    const val LICENSED = 3
    const val PUBLISHING_FINISHED = 4
    const val CANCELLED = 5
    const val ON_HIATUS = 6

    fun fromString(status: String?): Int = when (status?.trim()) {
        "Ongoing" -> ONGOING
        "Completed" -> COMPLETED
        "Licensed" -> LICENSED
        "Publishing Finished" -> PUBLISHING_FINISHED
        "Cancelled" -> CANCELLED
        "On Hiatus" -> ON_HIATUS
        else -> UNKNOWN
    }

    /** Display label resource for a `novels.status` code; unknown/unrecognized falls back to `unknown`. */
    fun toStringRes(status: Long): StringResource = when (status.toInt()) {
        ONGOING -> MR.strings.ongoing
        COMPLETED -> MR.strings.completed
        LICENSED -> MR.strings.licensed
        PUBLISHING_FINISHED -> MR.strings.publishing_finished
        CANCELLED -> MR.strings.cancelled
        ON_HIATUS -> MR.strings.on_hiatus
        else -> MR.strings.unknown
    }
}

/**
 * Translate a freshly-parsed [SourceNovel] (lnreader plugin output) into a domain [Novel] ready for
 * the novels table. `id = -1L` (the unsaved sentinel) because the caller routes through
 * [reikai.domain.novel.NovelRepository.insertOrGet], which inserts by (url, source) and returns the
 * stored row.
 *
 * `genres` on SourceNovel is a comma-joined string per the lnreader convention; split to a clean
 * `List<String>` here.
 */
fun SourceNovel.toNovel(
    sourceId: String,
    favorite: Boolean = true,
    now: Long = System.currentTimeMillis(),
): Novel = Novel(
    id = -1L,
    source = sourceId,
    url = path,
    title = name ?: "Untitled",
    author = author,
    artist = artist,
    description = summary,
    genre = genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
    status = NovelStatusCode.fromString(status).toLong(),
    thumbnailUrl = cover,
    favorite = favorite,
    lastUpdate = 0L,
    initialized = true,
    chapterFlags = 0L,
    dateAdded = if (favorite) now else 0L,
    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    coverLastModified = 0L,
    totalPages = totalPages.toLong(),
    lastReadAt = null,
    editedFlags = 0L,
    notes = "",
    viewerFlags = 0L,
    version = 0L,
)

/**
 * Translate a [ChapterItem] (lnreader plugin's chapter list entry) into a domain [NovelChapter]
 * ready for upsert. `id = -1L` because the caller is about to insert; existing rows are looked up
 * via [reikai.domain.novel.NovelChapterRepository.getByUrlAndNovelId] first. `read`/`bookmark`/
 * `lastTextProgress` start cleared; the reader updates them as the user reads.
 */
fun ChapterItem.toNovelChapter(
    novelId: Long,
    sourceOrder: Long = 0L,
    now: Long = System.currentTimeMillis(),
): NovelChapter = NovelChapter(
    id = -1L,
    novelId = novelId,
    url = path,
    name = name,
    read = false,
    bookmark = false,
    lastTextProgress = 0L,
    chapterNumber = chapterNumber ?: 0.0,
    sourceOrder = sourceOrder,
    dateFetch = now,
    dateUpload = 0L,
    page = page.orEmpty(),
    isDownloaded = false,
)
