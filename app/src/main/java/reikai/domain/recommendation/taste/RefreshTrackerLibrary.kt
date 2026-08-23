package reikai.domain.recommendation.taste

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Pulls the enabled trackers' libraries and writes them into the taste cache. Each tracker is fetched
 * in parallel and replaces only its own rows, so one failing leaves the others' cached data intact.
 * The pull is the only thing that hits the network; the profile is recomputed locally from the cache.
 * Registered as a singleton so the [mutex] and the staleness check coalesce concurrent triggers into
 * one pull. A user schedule and a cooldown-guarded manual [refreshNow] sit on top of [await].
 */
@Inject
@SingleIn(AppScope::class)
class RefreshTrackerLibrary(
    private val fetchers: List<TrackerLibraryFetcher>,
    private val repository: TasteLibraryRepository,
) {
    private val mutex = Mutex()
    private var lastManualRefresh = 0L

    /** Unconditional, unlike [refreshIfStale] and [refreshNow]. */
    suspend fun await() {
        mutex.withLock {
            dropUnrequestedTrackers()
            runPull(fetchers.filter { it.isEnabled() })
        }
    }

    /** Returns false and does nothing when pressed again within [cooldownMs]. The check-and-set runs
     *  under the [mutex] with the pull, so two near-simultaneous taps can't both slip past. */
    suspend fun refreshNow(cooldownMs: Long = MANUAL_COOLDOWN_MS): Boolean =
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastManualRefresh < cooldownMs) return@withLock false
            lastManualRefresh = now
            dropUnrequestedTrackers()
            runPull(fetchers.filter { it.isEnabled() })
            true
        }

    /** Bootstraps the profile lazily on first use without re-pulling on every details open. */
    suspend fun refreshIfStale(maxAgeMs: Long = DEFAULT_STALE_MS) {
        mutex.withLock {
            dropUnrequestedTrackers()
            val enabled = fetchers.filter { it.isEnabled() }
            if (enabled.isEmpty()) return
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val stale = enabled.filter { (repository.lastFetch(it.trackerId) ?: 0L) < cutoff }
            if (stale.isNotEmpty()) runPull(stale)
        }
    }

    /**
     * Forget the cached rows of any tracker the user has turned the pull off for. Without this they
     * keep shaping the profile forever, since the read path takes every cached row and only the pull
     * path looks at whether a tracker is still feeding it. Keyed to the preference rather than
     * [TrackerLibraryFetcher.isEnabled], so a tracker that logs itself out keeps its cache.
     */
    private suspend fun dropUnrequestedTrackers() {
        fetchers.filterNot { it.isPullRequested() }
            .forEach { repository.deleteTracker(it.trackerId) }
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
                            if (e is CancellationException) throw e
                            logcat(LogPriority.WARN, e) { "Tracker library pull failed (${fetcher.trackerId})" }
                        }
                }
            }.awaitAll()
        }
    }

    companion object {
        private const val DEFAULT_STALE_MS = 6L * 60 * 60 * 1000 // 6h
        private const val MANUAL_COOLDOWN_MS = 60L * 1000 // 60s
    }
}
