package reikai.domain.chapter

import kotlinx.coroutines.flow.StateFlow
import reikai.domain.library.ContentType
import reikai.domain.local.LocalVolumeOwnerRepository
import reikai.domain.local.VolumeOwner

/** A local series' volume the user has ordered or named, by its file or folder [key]. */
data class LocalVolume(val key: String, val position: Long?, val label: String?)

/** The volumes table and which series lists each volume ([LocalVolumeOwnerRepository.all]), both by folder. */
interface LocalVolumeRepository : LocalVolumeOwnerRepository {
    /** Every ordered or named volume of [type], by series folder. Held, so a source lists without a query. */
    fun volumes(type: ContentType): StateFlow<Map<String, List<LocalVolume>>>

    /** [volumes] once the table has been read, for a backup or a listing that must not take the empty seed. */
    suspend fun loadedVolumes(type: ContentType): Map<String, List<LocalVolume>>

    /** Orders [series]' volumes as [keys] lists them. */
    suspend fun setOrder(type: ContentType, series: String, keys: List<String>)

    /** Names a volume [label], or back to the name its file gives when it is null. */
    suspend fun setLabel(type: ContentType, series: String, key: String, label: String?)

    /** Stores [volumes] as a backup carried them for [series]. */
    suspend fun restore(type: ContentType, series: String, volumes: List<LocalVolume>)

    /** Stores the volumes a backup said a series lists from other folders, in its order. */
    suspend fun restoreOwners(type: ContentType, owners: List<VolumeOwner>)

    /** The volume chip each local series of [type] was left on, by entry id ([VolumePickRepository]). */
    suspend fun loadedPicks(type: ContentType): Map<Long, String>

    /** Leaves the local series at [series] on the chip [key] a backup carried, unless this device already left it on one. */
    suspend fun restorePick(type: ContentType, series: String, key: String)
}

/** [this] volumes in the user's order: the ones [saved] places, by position, then the rest as they came. */
fun <V> List<V>.inUserOrder(keyOf: (V) -> String, saved: List<LocalVolume>?): List<V> {
    val positions = saved.orEmpty().mapNotNull { volume -> volume.position?.let { volume.key to it } }.toMap()
    if (positions.isEmpty()) return this
    val (placed, rest) = partition { keyOf(it) in positions }
    return placed.sortedBy { positions.getValue(keyOf(it)) } + rest
}
