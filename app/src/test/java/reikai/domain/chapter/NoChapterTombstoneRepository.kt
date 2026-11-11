package reikai.domain.chapter

import reikai.domain.library.ContentType

/** Tombstone tables that hold nothing and keep nothing, for a test about something else. */
object NoChapterTombstoneRepository : ChapterTombstoneRepository {
    override suspend fun byOwner(type: ContentType, ownerId: Long) = emptyList<SyncedChapter>()

    override suspend fun getByOwners(type: ContentType, ownerIds: List<Long>) = emptyMap<Long, List<ChapterTombstone>>()

    override suspend fun <T> capturing(
        type: ContentType,
        ownerId: Long,
        removedIds: List<Long>,
        revivals: List<Revival>,
        write: suspend () -> T,
    ): T = write()

    override suspend fun move(type: ContentType, fromOwner: Long, fromUrl: String, toOwner: Long, toUrl: String) = Unit

    override suspend fun settle(type: ContentType, ownerId: Long, urls: Collection<String>) = Unit

    override suspend fun forget(type: ContentType, ownerId: Long, urlPrefixes: List<String>) = Unit

    override suspend fun restore(
        type: ContentType,
        ownerId: Long,
        tombstones: List<ChapterTombstone>,
        chapterUrls: Collection<String>,
    ) = Unit
}
