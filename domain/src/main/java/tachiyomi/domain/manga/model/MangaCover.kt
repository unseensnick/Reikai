package tachiyomi.domain.manga.model

/**
 * Contains the required data for MangaCoverFetcher
 */
data class MangaCover(
    val mangaId: Long,
    val sourceId: Long,
    val isMangaFavorite: Boolean,
    val url: String?,
    val lastModified: Long,
) {
    // RK --> vibrant color extracted from the cover, used to seed the reader/details theme (Y11).
    var vibrantCoverColor: Int?
        get() = vibrantCoverColorMap[mangaId]
        set(value) {
            vibrantCoverColorMap[mangaId] = value
        }

    companion object {
        // Populated as covers are displayed; restored at startup from prefs by MangaCoverMetadata.
        val vibrantCoverColorMap: HashMap<Long, Int?> = hashMapOf()
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
