// An entry's places in Manual library orders, carried on BackupManga and BackupNovel alike at 702. A place
// names its category by order, as the entry's categories do, so it maps by name on restore; Default is
// order -1, the system row's own.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupManualPosition(
    @ProtoNumber(1) var categoryOrder: Long,
    @ProtoNumber(2) var position: Long,
)

/** The Manual order field both entry messages carry, so the shared kernels read and write either. */
interface BackupManualOrderFields {
    var manualOrder: List<BackupManualPosition>
}
