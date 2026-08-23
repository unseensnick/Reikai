package reikai.domain.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory cache of the related-mangas carousel pool, keyed by manga id, so reopening a manga shows
 * its carousel instantly instead of re-querying the source and every tracker endpoint. A stale entry
 * is still served immediately while a background fetch updates it, so the carousel can be up to
 * [FRESH_MS] out of date. Cleared on process death.
 */
@Inject
@SingleIn(AppScope::class)
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

    /** The "See all" grid renders live off this, so it fills to the full pool even when opened
     *  mid-load, which the menu placement does (it opens before the background load finishes). */
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

    fun isFresh(entry: Entry, now: Long = System.currentTimeMillis()): Boolean =
        now - entry.fetchedAt < FRESH_MS

    companion object {
        /** Serve cached results untouched within this window; past it, refresh in the background. */
        const val FRESH_MS = 30 * 60 * 1000L
    }
}
