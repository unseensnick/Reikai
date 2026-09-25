package reikai.data.coil

import reikai.domain.novel.model.Novel

/**
 * Coil model for a light-novel cover, the novel twin of [tachiyomi.domain.manga.model.MangaCover].
 * [sourceId] picks the client and headers the cover is fetched with (`NovelImageRequests`), which
 * answers without loading plugins, since the image loader cannot wait on the source registry.
 * [novelId] locates a user-set custom cover, cached under `EntryId.Novel`'s key so it cannot collide
 * with a same-id manga's, and is 0 in browse contexts where custom covers do not apply.
 */
data class NovelCover(
    val url: String?,
    val sourceId: String?,
    val isNovelFavorite: Boolean,
    val lastModified: Long,
    val novelId: Long = 0L,
)

/**
 * A stored novel's own cover, as [tachiyomi.domain.manga.model.asMangaCover] is a manga's. The favorite
 * flag comes off the row: callers that guessed it sent the fetcher past the library cover cache.
 */
fun Novel.asNovelCover(url: String? = thumbnailUrl) = NovelCover(
    url = url,
    sourceId = source,
    isNovelFavorite = favorite,
    lastModified = coverLastModified,
    novelId = id,
)
