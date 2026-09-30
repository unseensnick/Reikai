package tachiyomi.domain.manga.model

import androidx.compose.runtime.Immutable
import reikai.domain.entry.EntryCustomInfo

/**
 * A user's per-field overrides for a favorite manga, stored non-destructively in `custom_manga_info`
 * (the source `mangas` row is never changed). The overlay rules live on [EntryCustomInfo].
 */
@Immutable
data class CustomMangaInfo(
    val mangaId: Long,
    override val title: String? = null,
    override val author: String? = null,
    override val artist: String? = null,
    override val description: String? = null,
    override val genre: List<String>? = null,
    override val status: Long? = null,
    override val thumbnailUrl: String? = null,
) : EntryCustomInfo
