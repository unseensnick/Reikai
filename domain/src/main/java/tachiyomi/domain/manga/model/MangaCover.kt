package tachiyomi.domain.manga.model

import java.util.Collections
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
        get() = vibrantCoverColorMap[mangaId]
        set(value) {
            vibrantCoverColorMap[mangaId] = value
        }

    companion object {
        /** The most entries [vibrantCoverColorMap] keeps; the least recently used go first. */
        const val VIBRANT_COLOR_CAP = 2000

        // Populated as covers are displayed, browse covers included, and restored at startup from prefs by
        // MangaCoverMetadata, so it is capped. Written from IO coroutines and read on Main, so it is
        // synchronized, and access-ordered, which makes a get a write: iterate it only under its own lock.
        val vibrantCoverColorMap: MutableMap<Long, Int?> = Collections.synchronizedMap(
            object : LinkedHashMap<Long, Int?>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int?>?) = size > VIBRANT_COLOR_CAP
            },
        )
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
