package reikai.domain.chapter

import reikai.domain.library.StoredType

/** A repository holding no correction, for a test whose subject is not chapter numbers. */
object NoChapterNumberOverrides : ChapterNumberOverrideRepository {
    override suspend fun getByOwner(type: StoredType, ownerId: Long) = emptyMap<String, ChapterNumberOverride>()

    override suspend fun getByOwners(type: StoredType, ownerIds: List<Long>) =
        emptyMap<Long, Map<String, ChapterNumberOverride>>()

    override suspend fun set(type: StoredType, ownerId: Long, url: String, number: Double) = Unit

    override suspend fun clear(type: StoredType, ownerId: Long, url: String) = Unit

    override suspend fun restore(type: StoredType, ownerId: Long, overrides: List<ChapterNumberOverride>) = Unit

    override suspend fun updateSourceNumbers(type: StoredType, ownerId: Long, overrides: List<ChapterNumberOverride>) =
        Unit
}
