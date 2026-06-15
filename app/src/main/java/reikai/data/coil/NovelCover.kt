package reikai.data.coil

/**
 * Coil model for a light-novel cover, the novel twin of [tachiyomi.domain.manga.model.MangaCover].
 *
 * Self-contained on purpose: [reikai.novel.source.NovelSourceManager] is per-screen and can't be
 * resolved from the global image loader, so the call sites that hold the source populate [site] here.
 * [site] is sent as the Referer so hosts that gate cover delivery on it serve the full image (matching
 * LNReader and the Yokai-era fork). The source icon is rendered by a separate badge, not the fetcher,
 * so it isn't carried here.
 */
data class NovelCover(
    val url: String?,
    val site: String?,
    val isNovelFavorite: Boolean,
    val lastModified: Long,
)
