// A local series' volume the user ordered or named, carried on BackupManga and BackupNovel alike at 703.
// It is keyed by the volume's file or folder, which a restore of the same files finds again.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.chapter.LocalVolume

@Serializable
class BackupLocalVolume(
    @ProtoNumber(1) var key: String,
    @ProtoNumber(2) var position: Long? = null,
    @ProtoNumber(3) var label: String? = null,
) {
    fun toLocalVolume() = LocalVolume(key, position, label)
}

fun LocalVolume.toBackup() = BackupLocalVolume(key, position, label)
