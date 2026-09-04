// RK: novel backup. Net-new Reikai file: novel-category twin of BackupCategory. Novel
// categories now share the `categories` table, but they keep their own backup list: a novel's category
// memberships are stored as each category's `order` and resolved against this list alone, so dropping a
// category from it would orphan them. It needs no content type of its own; a category spanning both
// libraries is written as universal via BackupCategory, which restores first, and the name check in
// NovelRestorer.restoreCategories then recognises it (that read covers universal rows).
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
