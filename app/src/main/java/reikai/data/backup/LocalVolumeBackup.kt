package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupLocalVolumeFields
import eu.kanade.tachiyomi.data.backup.models.BackupVolumeOwner
import eu.kanade.tachiyomi.data.backup.models.toBackup
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.library.ContentType
import reikai.domain.local.VolumeOwner

/**
 * Writes each local series' own volume rows (saved order and names, the volumes it lists from other folders, the
 * chip it was left on), both content types. A pass of its own, gated only on the series being local ([isLocal]):
 * a series with no chapter rows has its grouping all the same. Keyed by the series' address, so no id remaps.
 */
suspend fun <E, B : BackupLocalVolumeFields> List<Pair<E, B>>.writeLocalVolumes(
    type: ContentType,
    volumes: LocalVolumeRepository,
    isLocal: (E) -> Boolean,
    idOf: (E) -> Long,
    urlOf: (E) -> String,
) {
    val local = filter { isLocal(it.first) }
    if (local.isEmpty()) return
    val volumesBySeries = volumes.loadedVolumes(type)
    val ownersBySeries = volumes.all(type).groupBy { it.owner }
    val picks = volumes.loadedPicks(type)
    local.forEach { (entry, backup) ->
        val url = urlOf(entry)
        backup.localVolumes = volumesBySeries[url].orEmpty().map { it.toBackup() }
        backup.volumeOwners = ownersBySeries[url].orEmpty().map { BackupVolumeOwner(it.folder, it.volume) }
        backup.volumePick = picks[idOf(entry)]
    }
}

/**
 * Stores what [writeLocalVolumes] carried for the local series at [url], both content types, except the volumes it
 * lists from other folders: those regroup chapters across series, so the restore applies them once every series
 * is back ([restoredOwners], CombineLocalVolumes.restoreOwners).
 */
suspend fun BackupLocalVolumeFields.restoreLocalVolumes(
    type: ContentType,
    volumes: LocalVolumeRepository,
    url: String,
) {
    volumes.restore(type, url, localVolumes.map { it.toLocalVolume() })
    volumePick?.let { volumes.restorePick(type, url, it) }
}

/** The owner rows the local series at [url] carried: the volumes it lists from other folders. */
fun BackupLocalVolumeFields.restoredOwners(url: String): List<VolumeOwner> = volumeOwners.map { it.toOwner(url) }
