package reikai.domain.chapter

import reikai.domain.library.ContentType

/** Tombstone rules that confirm every removal and keep nothing, for a sync test about something else. */
object NoChapterTombstones : LocalChapterTombstones {
    override suspend fun confirm(
        type: ContentType,
        isLocal: Boolean,
        removed: List<SyncedChapter>,
        listed: Collection<String>,
    ) = Unit

    override suspend fun <T> around(
        type: ContentType,
        ownerId: Long,
        isLocal: Boolean,
        removed: List<SyncedChapter>,
        arriving: List<SyncedChapter>,
        write: suspend () -> T,
    ) = Revived(write(), emptySet<String>())
}
