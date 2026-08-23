package reikai.domain.recommendation.taste

/**
 * One implementation per tracker that can return the user's full tracked library, normalized into
 * [TrackedEntry] (genres/tags inline, status mapped to [TrackStatus], score to 0..1). Feeds the
 * recommendation taste profile.
 *
 * [isEnabled] gates the pull on both the per-tracker "pull library" preference and the user being
 * logged in, so [RefreshTrackerLibrary] only hits trackers that can actually answer. MangaUpdates
 * has no usable library-list endpoint, so it has a recs provider but no fetcher here.
 */
interface TrackerLibraryFetcher {

    val trackerId: Long

    /**
     * The user's per-tracker "pull library" preference on its own, ignoring whether the tracker can
     * answer right now. Purging cached rows is keyed to this and never to [isEnabled], so a tracker
     * that logs itself out keeps its cache instead of losing it to a transient auth failure.
     */
    fun isPullRequested(): Boolean

    fun isEnabled(): Boolean

    suspend fun fetchLibrary(): List<TrackedEntry>
}
