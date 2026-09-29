package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.Tracker
import tachiyomi.core.common.preference.Preference

/**
 * One implementation per tracker that can return the user's full tracked library, normalized into
 * [TrackedEntry] (genres/tags inline, status mapped to [TrackStatus], score to 0..1), which feeds
 * the recommendation taste profile. [isEnabled] gates the pull on both the per-tracker "pull
 * library" preference and the user being logged in, so [RefreshTrackerLibrary] only hits trackers
 * that can answer. MangaUpdates has no usable library-list endpoint, so it has a recs provider but
 * no fetcher here.
 */
interface TrackerLibraryFetcher {

    val tracker: Tracker

    /** The user's "pull library" switch for [tracker], which the settings screen draws from here. */
    val pullPreference: Preference<Boolean>

    val trackerId: Long get() = tracker.id

    /**
     * The per-tracker "pull library" preference on its own, ignoring whether the tracker can
     * answer right now. Purging cached rows is keyed to this and never to [isEnabled], so a tracker
     * that logs itself out keeps its cache instead of losing it to a transient auth failure.
     */
    fun isPullRequested(): Boolean = pullPreference.get()

    fun isEnabled(): Boolean = isPullRequested() && tracker.isLoggedIn

    suspend fun fetchLibrary(): List<TrackedEntry>
}
