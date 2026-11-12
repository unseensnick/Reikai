package reikai.domain.chapter

import kotlinx.coroutines.flow.StateFlow
import reikai.domain.library.ContentType

/** The volume chip each local series of a content type was left on, by entry; a series with none shows all. */
interface VolumePickRepository {
    fun picks(type: ContentType): StateFlow<Map<Long, String>>

    /** Leaves [entryId] on the volume [volumeKey] names, or on every volume when it is null. */
    suspend fun set(type: ContentType, entryId: Long, volumeKey: String?)

    /** [picks] read from the table now, for a backup or a relink that must not take the empty seed. */
    suspend fun loadedPicks(type: ContentType): Map<Long, String>

    /** Leaves the local series at [series] on the chip [key] a backup carried, unless this device already left it on one. */
    suspend fun restore(type: ContentType, series: String, key: String)
}
