// RK: novel backup. Net-new Reikai file: the novel twin of BackupSource, keyed by the text source id
// novels use, so a restore can name a source that is not installed.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupNovelSource(
    @ProtoNumber(1) var name: String = "",
    @ProtoNumber(2) var sourceId: String,
)
