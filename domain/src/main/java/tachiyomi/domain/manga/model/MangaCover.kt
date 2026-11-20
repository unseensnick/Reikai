package tachiyomi.domain.manga.model

import reikai.domain.entry.CoverColors
import kotlin.time.Instant

/**
 * Contains the required data for MangaCoverFetcher
 */
data class MangaCover(
    val mangaId: Long,
    val sourceId: Long,
    val isMangaFavorite: Boolean,
    val url: String?,
    val lastModified: Instant?,
) { // RK: a body for the cover colour below
    // RK --> vibrant color extracted from the cover, used to seed the reader/details theme.
    var vibrantCoverColor: Int?
        get() = CoverColors[mangaId]
        set(value) {
            if (value == null) CoverColors.remove(mangaId) else CoverColors.put(mangaId, value, isMangaFavorite)
        }
    // RK <--
}

fun Manga.asMangaCover(): MangaCover {
    return MangaCover(
        mangaId = id,
        sourceId = source,
        isMangaFavorite = favorite,
        url = thumbnailUrl,
        lastModified = coverLastModified,
    )
}
