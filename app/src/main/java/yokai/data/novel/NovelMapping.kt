package yokai.data.novel

import yokai.domain.novel.models.Novel
import yokai.novel.host.SourceNovel

/**
 * Status codes used by the `novels.status` column. Mirrors the lnreader NovelStatus enum the
 * plugins return as strings; we keep them as ints to match the manga side's `mangas.status`
 * column shape, even though the value spaces don't overlap.
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
}

/**
 * Translate a freshly-parsed [SourceNovel] (lnreader plugin output) into a domain [Novel] ready
 * for the novels table. `id = null` because the caller is about to insert.
 *
 * `genres` on SourceNovel is a comma-joined string per the lnreader plugin convention; we split
 * to a clean List<String> here.
 */
fun SourceNovel.toNovel(
    sourceId: String,
    favorite: Boolean = true,
    now: Long = System.currentTimeMillis(),
): Novel = Novel(
    id = null,
    source = sourceId,
    url = path,
    title = name ?: "Untitled",
    author = author,
    artist = artist,
    description = summary,
    genres = genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
    status = NovelStatusCode.fromString(status),
    thumbnailUrl = cover,
    favorite = favorite,
    lastUpdate = 0L,
    initialized = true,
    chapterFlags = 0,
    dateAdded = if (favorite) now else 0L,
    updateStrategy = 0,
    coverLastModified = 0L,
)
