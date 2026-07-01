// RK: novel backup (Roadmap 9). Net-new Reikai file: novel-category twin of BackupCategory. Novel
// categories live in their own table, so they back up separately from manga categories. The per-
// category drag order (novel_order) is not carried, mirroring how BackupCategory omits manga_order;
// new categories restore with an empty order.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupNovelCategory(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var order: Long = 0,
    @ProtoNumber(3) var id: Long = 0,
    @ProtoNumber(4) var flags: Long = 0,
)
