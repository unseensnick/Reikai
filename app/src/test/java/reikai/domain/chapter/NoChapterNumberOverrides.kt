package reikai.domain.chapter

import reikai.domain.library.ContentType

/** A repository holding no correction, for a test whose subject is not chapter numbers. */
object NoChapterNumberOverrides : ChapterNumberOverrideRepository {
    override suspend fun getByOwner(type: ContentType, ownerId: Long) = emptyMap<String, ChapterNumberOverride>()

    override suspend fun set(type: ContentType, ownerId: Long, url: String, number: Double) = Unit

    override suspend fun clear(type: ContentType, ownerId: Long, url: String) = Unit

    override suspend fun restore(type: ContentType, ownerId: Long, overrides: List<ChapterNumberOverride>) = Unit

    override suspend fun updateSourceNumbers(type: ContentType, ownerId: Long, overrides: List<ChapterNumberOverride>) =
        Unit
}
