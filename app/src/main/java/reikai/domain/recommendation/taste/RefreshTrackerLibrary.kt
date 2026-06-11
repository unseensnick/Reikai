package reikai.domain.recommendation.taste

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Pulls the enabled trackers' libraries and writes them into the taste cache. Each tracker is
 * fetched in parallel and replaces only its own rows, so one tracker failing leaves the others'
 * cached data intact. The pull is the only thing that hits the network; the profile itself is
 * recomputed locally from the cache ([GetTasteProfile]).
 *
 * Registered as a singleton so the [mutex] and the staleness check coalesce concurrent triggers
 * (e.g. several details screens opening at once) into a single pull. R4c layers the user-facing
 * schedule (never / 7d / 30d) and a manual "refresh now" with cooldown on top of [await].
 */
class RefreshTrackerLibrary(
    private val fetchers: List<TrackerLibraryFetcher>,
    private val repository: TasteLibraryRepository,
) {
    private val mutex = Mutex()

    /** Pull every enabled tracker now, unconditionally. */
    suspend fun await() {
        mutex.withLock { runPull(fetchers.filter { it.isEnabled() }) }
    }

    /** Pull only if an enabled tracker has never been pulled or its cache is older than [maxAgeMs].
     *  Used to bootstrap the profile lazily on first use without re-pulling on every details open. */
    suspend fun refreshIfStale(maxAgeMs: Long = DEFAULT_STALE_MS) {
        mutex.withLock {
            val enabled = fetchers.filter { it.isEnabled() }
            if (enabled.isEmpty()) return
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val stale = enabled.filter { (repository.lastFetch(it.trackerId) ?: 0L) < cutoff }
            if (stale.isNotEmpty()) runPull(stale)
        }
    }

    private suspend fun runPull(targets: List<TrackerLibraryFetcher>) {
        if (targets.isEmpty()) return
        val now = System.currentTimeMillis()
        coroutineScope {
            targets.map { fetcher ->
                async {
                    runCatching { fetcher.fetchLibrary() }
                        .onSuccess { repository.replaceTracker(fetcher.trackerId, it, now) }
                        .onFailure { e ->
                            logcat(LogPriority.WARN, e) { "Tracker library pull failed (${fetcher.trackerId})" }
                        }
                }
            }.awaitAll()
        }
    }

    companion object {
        private const val DEFAULT_STALE_MS = 6L * 60 * 60 * 1000 // 6h
    }
}
