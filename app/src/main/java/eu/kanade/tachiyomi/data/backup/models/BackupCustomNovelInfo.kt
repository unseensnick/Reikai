// RK: novel custom-info backup. Net-new Reikai file, the novel twin of BackupCustomMangaInfo. User edits
// live in the custom_novel_info table keyed by local novel id, which changes on restore, so each entry
// carries its {url, source} ref and is re-keyed to the restored novel's fresh id. Only set fields are
// carried; an empty entry is never backed up (the row wouldn't exist).
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
