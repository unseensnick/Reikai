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
}
