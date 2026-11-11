// A local series' volume the user ordered or named, carried on BackupManga and BackupNovel alike at 703.
// It is keyed by the volume's file or folder, which a restore of the same files finds again.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.chapter.LocalVolume
import reikai.domain.local.VolumeOwner

@Serializable
class BackupLocalVolume(
    @ProtoNumber(1) var key: String,
    @ProtoNumber(2) var position: Long? = null,
    @ProtoNumber(3) var label: String? = null,
) {
    fun toLocalVolume() = LocalVolume(key, position, label)
}

fun LocalVolume.toBackup() = BackupLocalVolume(key, position, label)

/**
 * A volume a local series lists from another folder ([reikai.domain.local.VolumeOwner]), carried on the series that
 * lists it, BackupManga and BackupNovel alike at 705, so a restore groups the same files as before.
 */
@Serializable
class BackupVolumeOwner(
    @ProtoNumber(1) var folder: String,
    @ProtoNumber(2) var volume: String,
) {
    fun toOwner(series: String) = VolumeOwner(folder, volume, series)
}
