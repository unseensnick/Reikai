// Novel backup. Net-new Reikai file. Merge groups live in the merge_group tables keyed by novel
// id, and ids change on restore, so each group is serialized here as a list of stable {url, source}
// refs and rebuilt into fresh ids after the novels are restored.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupNovelSourceRef(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var source: String,
)

@Serializable
class BackupNovelMergeGroup(
    @ProtoNumber(1) var refs: List<BackupNovelSourceRef> = emptyList(),
)
