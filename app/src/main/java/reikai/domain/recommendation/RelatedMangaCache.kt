package reikai.domain.recommendation

import java.util.concurrent.ConcurrentHashMap

/**
 * App-scoped, in-memory cache of the related-mangas carousel pool, keyed by manga id.
 *
 * Lets reopening a manga show its carousel instantly instead of re-querying the source plus the
 * tracker endpoints every time. Each entry carries its fetch timestamp so the caller can serve a
 * fresh entry untouched and refresh a stale one in the background ("cache, then refresh").
 *
 * In-memory only: cleared on process death (a longer-lived disk cache for the raw tracker responses
 * lands in R2). A stale entry is still served immediately while a background fetch updates it,
 * bounding how out-of-date the carousel can look to [FRESH_MS].
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

    private val entries = ConcurrentHashMap<Long, Entry>()

    // Unified merged-group pools, keyed by anchor manga id. Namespaced separately so a group's
    // pooled carousel doesn't collide with that same anchor manga's own single-source entry.
    private val pooledEntries = ConcurrentHashMap<Long, Entry>()

    // Per-source chip-view pools for a merged title, keyed by that source's manga id. Namespaced
    // apart from `entries` (which fetches trackers live) because a chip view replays the unified
    // view's tracker seeds rather than refetching; caching here stops every chip switch from
    // re-running the source's rate-limited related-mangas fetch.
    private val sourceEntries = ConcurrentHashMap<Long, Entry>()

    fun get(mangaId: Long): Entry? = entries[mangaId]

    fun put(
        mangaId: Long,
        carousel: List<RelatedMangaCandidate>,
        fullPool: List<RelatedMangaCandidate>,
        isComplete: Boolean = true,
    ) {
        entries[mangaId] = Entry(carousel, fullPool, System.currentTimeMillis(), isComplete)
    }

    fun getPooled(anchorId: Long): Entry? = pooledEntries[anchorId]

    fun putPooled(anchorId: Long, carousel: List<RelatedMangaCandidate>, fullPool: List<RelatedMangaCandidate>) {
        pooledEntries[anchorId] = Entry(carousel, fullPool, System.currentTimeMillis())
    }

    fun getSource(mangaId: Long): Entry? = sourceEntries[mangaId]

    fun putSource(mangaId: Long, carousel: List<RelatedMangaCandidate>, fullPool: List<RelatedMangaCandidate>) {
        sourceEntries[mangaId] = Entry(carousel, fullPool, System.currentTimeMillis())
    }

    /** True while [entry] is within the freshness window and can be served without a refresh. */
    fun isFresh(entry: Entry, now: Long = System.currentTimeMillis()): Boolean =
        now - entry.fetchedAt < FRESH_MS

    companion object {
        /** Serve cached results untouched within this window; past it, refresh in the background. */
        const val FRESH_MS = 30 * 60 * 1000L
    }
}
