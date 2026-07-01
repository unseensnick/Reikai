// RK: novel backup (Roadmap 9). Net-new Reikai file. Novel merge/unmerge groups are stored in
// preferences as comma-joined novel IDs, which change on restore. So instead of backing up the raw
// ID strings (the generic preference backup is told to skip them), each group is serialized here as a
// list of stable {url, source} refs and rebuilt into fresh IDs after the novels are restored.
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
