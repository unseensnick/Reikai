package reikai.data.readinglog

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import reikai.domain.library.ContentType
import reikai.domain.readinglog.ReadingLogRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReadingLogRepositoryImpl(
    private val database: Database,
) : ReadingLogRepository {

    private val queries = database.reading_sessionQueries

    override suspend fun record(contentType: ContentType, chapterId: Long, endedAt: Instant, duration: Long) {
        // A reader opened and closed in the same instant spent no time, and history adds nothing for it either.
        if (duration <= 0L) return
        check(contentType != ContentType.ALL) { "ContentType.ALL has no reading log" }
        try {
            if (contentType == ContentType.MANGA) {
                queries.insertManga(chapterId, endedAt.toEpochMilliseconds(), duration)
            } else {
                queries.insertNovel(chapterId, endedAt.toEpochMilliseconds(), duration)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to record a reading session for chapterId=$chapterId" }
        }
    }
}
