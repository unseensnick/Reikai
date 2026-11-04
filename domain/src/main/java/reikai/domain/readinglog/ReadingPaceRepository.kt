package reikai.domain.readinglog

import reikai.domain.library.ContentType

/** What reading pace is learned from: finished chapters' time and length. See `data/reading_pace.sq`. */
interface ReadingPaceRepository {

    /**
     * Up to [limit] finished chapters of [contentType], most recently read first. [ContentType.ALL] is
     * not a table and throws.
     */
    suspend fun samples(contentType: ContentType, limit: Int): List<PaceSample>

    /**
     * Keeps [chapterId]'s length in its type's [PaceUnit] for [samples]: a novel chapter's word count. A
     * manga chapter's page count is already kept with its progress, so there it stores nothing.
     */
    suspend fun setLength(contentType: ContentType, chapterId: Long, units: Long)

    /** Adds [ms] of read-aloud to a novel chapter, the only type that reads aloud. */
    suspend fun addListenedTime(chapterId: Long, ms: Long)

    /** Raises a novel chapter's read-aloud to at least [ms], for a restore or a migration carrying it. */
    suspend fun keepListenedTime(chapterId: Long, ms: Long)

    /** What is kept for these novels' chapters, which a backup and a migration carry. */
    suspend fun novelChapterPace(novelIds: List<Long>): List<NovelChapterPace>
}

/** A novel chapter's kept word count and read-aloud time, with the url a backup keys it by. */
data class NovelChapterPace(
    val novelId: Long,
    val chapterId: Long,
    val url: String,
    val words: Long,
    val listenedMs: Long,
)

/** How many finished chapters a pace is asked for, enough for one series' and the overall one. */
const val PACE_SAMPLE_LIMIT = 200
