package reikai.domain.readinglog

import reikai.domain.library.ContentType

/** A reading pace store holding nothing, for a test whose subject is not the pace. */
object NoReadingPace : ReadingPaceRepository {
    override suspend fun samples(contentType: ContentType, limit: Int) = emptyList<PaceSample>()

    override suspend fun setLength(contentType: ContentType, chapterId: Long, units: Long) = Unit

    override suspend fun addListenedTime(chapterId: Long, ms: Long) = Unit

    override suspend fun keepLength(chapterId: Long, words: Long) = Unit

    override suspend fun keepListenedTime(chapterId: Long, ms: Long) = Unit

    override suspend fun novelChapterPace(novelIds: List<Long>) = emptyList<NovelChapterPace>()
}
