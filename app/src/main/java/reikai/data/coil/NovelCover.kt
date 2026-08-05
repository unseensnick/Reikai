package reikai.data.coil

/**
 * Coil model for a light-novel cover, the novel twin of [tachiyomi.domain.manga.model.MangaCover].
 * Self-contained on purpose: [reikai.novel.source.NovelSourceManager] is per-screen and cannot be
 * resolved from the global image loader, so call sites holding the source populate [site], which is
 * sent as the Referer so hosts that gate cover delivery on it serve the full image. [novelId] locates
 * a user-set custom cover, cached under the NEGATED id so it cannot collide with a same-id manga's,
 * and is 0 in browse contexts where custom covers do not apply.
 */
data class NovelCover(
    val url: String?,
    val site: String?,
    val isNovelFavorite: Boolean,
    val lastModified: Long,
    val novelId: Long = 0L,
)
