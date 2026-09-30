package reikai.domain.debug

import tachiyomi.domain.manga.model.Manga

/** The whole-table reads and bulk rewrites only the debug menu runs; nothing else should reach for these. */
interface DebugDatabaseRepository {

    suspend fun getAllManga(): List<Manga>

    /**
     * Moves every manga, saved search and feed row from source [from] to [to]. A manga the target
     * already stores under the same URL stays on [from], since a source holds one row per URL.
     */
    suspend fun migrateSource(from: Long, to: Long)

    /** Clears reader flags stored as -1, every bit set at once, which names no reading mode or orientation. */
    suspend fun resetBrokenReaderFlags()

    /** Every saved search of both content types, with the feed rows built on them. */
    suspend fun deleteAllSavedSearches()
}
