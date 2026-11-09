package reikai.data.readinglog

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.domain.readinglog.NovelChapterPace
import reikai.domain.readinglog.PaceSample
import reikai.domain.readinglog.ReadingPaceRepository
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReadingPaceRepositoryImpl(
    database: Database,
) : ReadingPaceRepository {

    private val queries = database.reading_paceQueries

    override suspend fun samples(contentType: ContentType, limit: Int): List<PaceSample> {
        val mapper = { entryId: Long, duration: Long, units: Long, listened: Long ->
            PaceSample(entryId, duration, units, listened)
        }
        return when (contentType) {
            ContentType.MANGA -> queries.mangaSamples(limit.toLong()) { id, duration, pages, listened ->
                mapper(id, duration, pages.toLong(), listened)
            }
            ContentType.NOVELS -> queries.novelSamples(limit.toLong(), mapper)
            ContentType.ALL -> error("ContentType.ALL has no reading pace")
        }.awaitAsList()
    }

    override suspend fun setLength(contentType: ContentType, chapterId: Long, units: Long) {
        when (contentType) {
            ContentType.MANGA -> Unit
            ContentType.NOVELS -> queries.setNovelWords(chapterId, units)
            ContentType.ALL -> error("ContentType.ALL has no reading pace")
        }
    }

    override suspend fun addListenedTime(chapterId: Long, ms: Long) {
        queries.addNovelListened(chapterId, ms)
    }

    override suspend fun keepLength(chapterId: Long, words: Long) {
        queries.keepNovelWords(chapterId, words)
    }

    override suspend fun keepListenedTime(chapterId: Long, ms: Long) {
        queries.keepNovelListened(chapterId, ms)
    }

    override suspend fun novelChapterPace(novelIds: List<Long>): List<NovelChapterPace> =
        queries.novelChapterPace(novelIds, ::NovelChapterPace).awaitAsList()
}
