package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.Source

/**
 * A source that can return a random entry from its catalogue (MangaDex's `/manga/random`). Backs the
 * "Random" button on the MangaDex Browse filter sheet.
 */
interface RandomMangaSource : Source {
    /** Identifier of a random entry (for MangaDex, the manga id), used to open its details. */
    suspend fun fetchRandomMangaUrl(): String
}
