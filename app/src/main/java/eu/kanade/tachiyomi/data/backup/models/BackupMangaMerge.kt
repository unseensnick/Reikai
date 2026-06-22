// RK: manga merge backup. Net-new Reikai file, the manga twin of BackupNovelMerge. Manga merge /
// unmerge groups live in preferences as comma-joined manga IDs, which change on restore. So instead
// of backing up the raw ID strings (the generic preference backup is told to skip them), each group is
// serialized here as a list of stable {url, source} refs and rebuilt into fresh IDs after the manga
// are restored. Differs from the novel version only in `source` being a Long (extension source id).
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupMangaSourceRef(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var source: Long,
)

@Serializable
class BackupMangaMergeGroup(
    @ProtoNumber(1) var refs: List<BackupMangaSourceRef> = emptyList(),
)
