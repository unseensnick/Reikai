package reikai.data.coil

/**
 * Coil model for a light-novel cover, the novel twin of [tachiyomi.domain.manga.model.MangaCover].
 * Self-contained on purpose: the source registry only fills once a novel surface has loaded the
 * plugins, so the image loader cannot rely on it, and call sites holding the source populate [site]
 * instead. It is sent as the Referer so hosts that gate cover delivery on it serve the full image.
 * [novelId] locates a user-set custom cover, cached under the NEGATED id so it cannot collide with a
 * same-id manga's, and is 0 in browse contexts where custom covers do not apply.
 */
data class NovelCover(
    val url: String?,
    val site: String?,
    val isNovelFavorite: Boolean,
    val lastModified: Long,
    val novelId: Long = 0L,
)
