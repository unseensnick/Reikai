package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupLocalVolumeFields
import eu.kanade.tachiyomi.data.backup.models.BackupVolumeOwner
import eu.kanade.tachiyomi.data.backup.models.toBackup
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.VolumePickRepository
import reikai.domain.library.StoredType
import reikai.domain.local.VolumeOwner
import reikai.novel.local.LocalNovelSource.Companion.isChildName

/**
 * Writes each local series' own volume rows (saved order and names, the volumes it lists from other folders, the
 * chip it was left on), both content types. A pass of its own, gated only on the series being local ([isLocal]):
 * a series with no chapter rows has its grouping all the same. Keyed by the series' address, so no id remaps.
 */
suspend fun <E, B : BackupLocalVolumeFields> List<Pair<E, B>>.writeLocalVolumes(
    type: StoredType,
    volumes: LocalVolumeRepository,
    picks: VolumePickRepository,
    isLocal: (E) -> Boolean,
    idOf: (E) -> Long,
    urlOf: (E) -> String,
) {
    val local = filter { isLocal(it.first) }
    if (local.isEmpty()) return
    val volumesBySeries = volumes.loadedVolumes(type)
    val ownersBySeries = volumes.all(type).groupBy { it.owner }
    val picked = picks.loadedPicks(type)
    local.forEach { (entry, backup) ->
        val url = urlOf(entry)
        backup.localVolumes = volumesBySeries[url].orEmpty().map { it.toBackup() }
        backup.volumeOwners = ownersBySeries[url].orEmpty().map { BackupVolumeOwner(it.folder, it.volume) }
        backup.volumePick = picked[idOf(entry)]
    }
}

/**
 * Stores what [writeLocalVolumes] carried for the local series at [url], both content types, except the volumes it
 * lists from other folders: those regroup chapters across series, so the restore applies them once every series
 * is back ([restoredOwners], CombineLocalVolumes.restoreOwners).
 */
suspend fun BackupLocalVolumeFields.restoreLocalVolumes(
    type: StoredType,
    volumes: LocalVolumeRepository,
    picks: VolumePickRepository,
    url: String,
) {
    volumes.restore(type, url, localVolumes.map { it.toLocalVolume() })
    volumePick?.let { picks.restore(type, url, it) }
}

/**
 * The owner rows the local series at [url] carried: the volumes it lists from other folders. A row naming more
 * than one entry of a folder is dropped, since the backup is outside input and a `..` or a separator would list,
 * delete or move files outside the local folder.
 */
fun BackupLocalVolumeFields.restoredOwners(url: String): List<VolumeOwner> = volumeOwners
    .filter { isChildName(it.folder) && (it.volume in KEYWORD_VOLUMES || isChildName(it.volume)) }
    .map { it.toOwner(url) }

// A folder's loose chapters and its whole contents, the two volume names that are no entry of their own.
private val KEYWORD_VOLUMES = setOf(VolumeOwner.LOOSE, VolumeOwner.WHOLE)
