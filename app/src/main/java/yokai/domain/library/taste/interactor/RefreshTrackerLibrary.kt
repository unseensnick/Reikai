package yokai.domain.library.taste.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.library.TrackerLibraryFetcher
import yokai.domain.library.taste.TrackerLibraryRepository

/**
 * Sequentially refreshes each registered tracker's library cache.
 *
 * Per-tracker dispatch rules:
 * 1. Skip when the user is not logged in to that tracker.
 * 2. Skip when the per-tracker "pull library from <tracker>" preference is off.
 * 3. Otherwise fetch + persist atomically via [TrackerLibraryRepository.replaceCacheForTracker].
 *
 * Sequential (not parallel) by design — easier abort semantics and friendlier to per-tracker
 * rate limits. A throw from one fetcher is logged and swallowed; the orchestrator continues
 * with the remaining trackers. The atomic replace contract means a partial failure mid-fetch
 * leaves the previous cache for that tracker intact.
 *
 * **Phase 4 core**: no [TrackerLibraryFetcher] implementations are registered yet, so
 * [fetchers] arrives empty and the orchestrator becomes a no-op. Phase 4.1+ adds the
 * first fetcher (AniList) and this class starts doing useful work without further changes.
 */
class RefreshTrackerLibrary(
    private val repository: TrackerLibraryRepository,
    private val fetchers: List<TrackerLibraryFetcher>,
    private val preferences: PreferencesHelper,
    private val getTrackedEntries: GetTrackedEntries,
) {
    suspend fun await() {
        if (fetchers.isEmpty()) {
            Logger.d { "TrackerLibrary refresh: no fetchers registered, nothing to do" }
            return
        }
        for (fetcher in fetchers) {
            val tracker = fetcher.tracker
            if (!tracker.isLogged) {
                Logger.d { "TrackerLibrary refresh: tracker id=${tracker.id} skipped (not logged in)" }
                continue
            }
            if (!preferences.pullLibraryFromTracker(tracker.id).get()) {
                Logger.d { "TrackerLibrary refresh: tracker id=${tracker.id} skipped (pull-library toggle off)" }
                continue
            }
            val started = System.currentTimeMillis()
            try {
                val entries = fetcher.fetchLibrary()
                repository.replaceCacheForTracker(tracker.id, entries)
                val elapsedMs = System.currentTimeMillis() - started
                Logger.d {
                    "TrackerLibrary refresh: tracker id=${tracker.id} fetched ${entries.size} entries in ${elapsedMs}ms"
                }
            } catch (t: Throwable) {
                Logger.e(t) { "TrackerLibrary refresh: tracker id=${tracker.id} failed: ${t.message}" }
                // Continue with remaining trackers; previous cache for this tracker is untouched.
            }
        }
        // Post-refresh summary: total unique entries after Layer A ∪ Layer B + cross-tracker dedup.
        // Cheap end-to-end visibility of what downstream taste-profile consumers will see.
        val unique = getTrackedEntries.await()
        Logger.d { "TrackerLibrary refresh: post-refresh ${unique.size} unique entries across all trackers" }
    }
}
