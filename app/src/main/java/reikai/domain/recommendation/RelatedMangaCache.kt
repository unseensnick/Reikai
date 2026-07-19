package reikai.domain.recommendation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * App-scoped, in-memory cache of the related-mangas carousel pool, keyed by manga id.
 *
 * Lets reopening a manga show its carousel instantly instead of re-querying the source plus the
 * tracker endpoints every time. Each entry carries its fetch timestamp so the caller can serve a
 * fresh entry untouched and refresh a stale one in the background ("cache, then refresh").
 *
 * In-memory only: cleared on process death. A stale entry is still served immediately while a
 * background fetch updates it, bounding how out-of-date the carousel can look to [FRESH_MS].
 */
class RelatedMangaCache {
    data class Entry(
        val carousel: List<RelatedMangaCandidate>,
        val fullPool: List<RelatedMangaCandidate>,
        val fetchedAt: Long,
        // False while the load is still streaming; a partial entry is served (so "See all" isn't
        // empty mid-load) but never treated as fresh, so it still refreshes to completion.
        val isComplete: Boolean = true,
    )

    private val entries = MutableStateFlow<Map<Long, Entry>>(emptyMap())

    fun get(mangaId: Long): Entry? = entries.value[mangaId]

    /**
     * Observe a manga's cached pool as it streams in and completes. The "See all" grid renders live off
     * this, so it fills to the full pool even when opened mid-load (the menu placement opens it before the
     * background load finishes, unlike "See all" which is tapped after the carousel is already loaded).
     */
    fun observe(mangaId: Long): Flow<Entry?> = entries.map { it[mangaId] }.distinctUntilChanged()

    fun put(
        mangaId: Long,
        carousel: List<RelatedMangaCandidate>,
        fullPool: List<RelatedMangaCandidate>,
        isComplete: Boolean = true,
    ) {
        val entry = Entry(carousel, fullPool, System.currentTimeMillis(), isComplete)
        entries.update { it + (mangaId to entry) }
    }

    /** True while [entry] is within the freshness window and can be served without a refresh. */
    fun isFresh(entry: Entry, now: Long = System.currentTimeMillis()): Boolean =
        now - entry.fetchedAt < FRESH_MS

    companion object {
        /** Serve cached results untouched within this window; past it, refresh in the background. */
        const val FRESH_MS = 30 * 60 * 1000L
    }
}
