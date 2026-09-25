// Manga merge backup. Net-new Reikai file, the manga form of BackupNovelMerge. Merge groups live in
// the merge_group tables keyed by manga id, and ids change on restore, so each group is serialized here
// as a list of stable {url, source} refs and rebuilt into fresh ids after the manga are restored.
// Differs from the novel version only in `source` being a Long (extension source id).
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
