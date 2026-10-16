package reikai.domain.readinglog

import reikai.domain.library.ContentType
import kotlin.time.Instant

/**
 * The reading log: one row per reading session, kept beside history because history overwrites a
 * chapter's last read and so cannot say when its time was spent. [ContentType.ALL] is not a table and
 * throws. See `data/reading_session.sq`.
 */
interface ReadingLogRepository {

    /** Records a session of [duration] ms on [chapterId] ending at [endedAt]; a non-positive one is dropped. */
    suspend fun record(contentType: ContentType, chapterId: Long, endedAt: Instant, duration: Long)

    /** [entryId]'s sessions keyed by chapter url, the form a backup carries them in. */
    suspend fun sessionsByUrl(contentType: ContentType, entryId: Long): List<UrlReadingSession>

    /** Writes [sessions] onto [entryId]'s chapters with the same url; one whose chapter is gone is dropped. */
    suspend fun restore(contentType: ContentType, entryId: Long, sessions: List<UrlReadingSession>)

    /** Copies each matched source chapter's sessions onto its target, keyed target id to source id. */
    suspend fun copy(contentType: ContentType, sourceByTargetChapter: Map<Long, Long>)
}

data class UrlReadingSession(val chapterUrl: String, val endedAt: Instant, val duration: Long)
