package reikai.domain.chapter

import reikai.domain.library.StoredType

/** Tombstone rules that confirm every removal and keep nothing, for a sync test about something else. */
object NoChapterTombstones : LocalChapterTombstones {
    override suspend fun held(type: StoredType, isLocal: Boolean, series: String, removed: List<SyncedChapter>) =
        emptySet<String>()

    override suspend fun confirm(
        type: StoredType,
        isLocal: Boolean,
        removed: List<SyncedChapter>,
        listed: Collection<String>,
    ) = Unit

    override suspend fun <T> around(
        type: StoredType,
        ownerId: Long,
        isLocal: Boolean,
        removed: List<SyncedChapter>,
        arriving: List<SyncedChapter>,
        write: suspend () -> T,
    ) = Revived(write(), emptySet<String>())
}
