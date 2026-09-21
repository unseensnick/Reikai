package reikai.data.coil

/**
 * Coil model for a light-novel cover, the novel twin of [tachiyomi.domain.manga.model.MangaCover].
 * [sourceId] picks the client and headers the cover is fetched with (`NovelImageRequests`), which
 * answers without loading plugins, since the image loader cannot wait on the source registry.
 * [novelId] locates a user-set custom cover, cached under the NEGATED id so it cannot collide with a
 * same-id manga's, and is 0 in browse contexts where custom covers do not apply.
 */
data class NovelCover(
    val url: String?,
    val sourceId: String?,
    val isNovelFavorite: Boolean,
    val lastModified: Long,
    val novelId: Long = 0L,
)
