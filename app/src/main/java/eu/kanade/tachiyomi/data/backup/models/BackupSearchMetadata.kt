package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Captured adult/EXH gallery metadata (the search_metadata / search_tags / search_titles rows)
 * carried with a [BackupManga] so a restore brings the namespaced tags back without re-opening each
 * gallery. Mirrors FlatMetadata; mangaId is contextual to the owning BackupManga.
 */
@Serializable
data class BackupSearchMetadata(
    @ProtoNumber(1) var uploader: String? = null,
    @ProtoNumber(2) var extra: String = "",
    @ProtoNumber(3) var indexedExtra: String? = null,
    @ProtoNumber(4) var extraVersion: Int = 0,
    @ProtoNumber(5) var tags: List<BackupSearchTag> = emptyList(),
    @ProtoNumber(6) var titles: List<BackupSearchTitle> = emptyList(),
)

@Serializable
data class BackupSearchTag(
    @ProtoNumber(1) var namespace: String? = null,
    @ProtoNumber(2) var name: String = "",
    @ProtoNumber(3) var type: Int = 0,
)

@Serializable
data class BackupSearchTitle(
    @ProtoNumber(1) var title: String = "",
    @ProtoNumber(2) var type: Int = 0,
)

/**
 * The same metadata as TachiyomiSY and Komikku write it, BackupManga field 601: the tags and titles beside
 * the metadata rather than inside it. Its fields 1 to 4 are [BackupSearchMetadata]'s own.
 */
@Serializable
class BackupFlatMetadata(
    @ProtoNumber(1) var searchMetadata: BackupSearchMetadata = BackupSearchMetadata(),
    @ProtoNumber(2) var searchTags: List<BackupSearchTag> = emptyList(),
    @ProtoNumber(3) var searchTitles: List<BackupSearchTitle> = emptyList(),
) {
    fun toSearchMetadata() = searchMetadata.copy(tags = searchTags, titles = searchTitles)
}
