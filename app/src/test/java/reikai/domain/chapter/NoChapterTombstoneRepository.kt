package reikai.domain.chapter

import reikai.domain.library.StoredType

/** Tombstone tables that hold nothing and keep nothing, for a test about something else. */
object NoChapterTombstoneRepository : ChapterTombstoneRepository {
    override suspend fun byOwner(type: StoredType, ownerId: Long) = emptyList<String>()

    override suspend fun getByOwners(type: StoredType, ownerIds: List<Long>) = emptyMap<Long, List<ChapterTombstone>>()

    override suspend fun <T> capturing(
        type: StoredType,
        ownerId: Long,
        removedIds: List<Long>,
        revivals: List<Revival>,
        write: suspend () -> T,
    ): T = write()

    override suspend fun move(type: StoredType, fromOwner: Long, fromUrl: String, toOwner: Long, toUrl: String) = Unit

    override suspend fun settle(type: StoredType, ownerId: Long, urls: Collection<String>) = Unit

    override suspend fun forget(type: StoredType, ownerId: Long, urlPrefixes: List<String>, urls: List<String>) = Unit

    override suspend fun restore(
        type: StoredType,
        ownerId: Long,
        tombstones: List<ChapterTombstone>,
        chapterUrls: Collection<String>,
    ) = Unit
}
