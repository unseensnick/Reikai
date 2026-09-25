// Novel custom info as Reikai 0.3.x wrote it (Backup field 714), keyed by {url, source}. Read only:
// a backup now carries custom info on each BackupNovel, and LegacyCustomInfo folds these entries onto it.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupCustomNovelInfo(
    @ProtoNumber(1) var source: String,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String? = null,
    @ProtoNumber(4) var author: String? = null,
    @ProtoNumber(5) var artist: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Long? = null,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
)
