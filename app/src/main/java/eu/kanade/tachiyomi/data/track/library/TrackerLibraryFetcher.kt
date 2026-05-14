package eu.kanade.tachiyomi.data.track.library

import eu.kanade.tachiyomi.data.track.TrackService
import yokai.domain.library.taste.model.TrackedEntry

/**
 * One implementation per tracker — fetches the user's full library from the tracker's
 * server and converts each entry to a normalized [TrackedEntry] (status mapped to
 * [yokai.domain.library.taste.model.TrackStatus], score normalized to 0..1).
 *
 * Phase 4 *core* ships with zero implementations. Phase 4.1+ adds one per tracker
 * (AniList → MAL → Kitsu → MangaUpdates → Shikimori → Bangumi), each registered as a
 * Koin `factory<TrackerLibraryFetcher>` so [yokai.domain.library.taste.interactor.RefreshTrackerLibrary]
 * picks them up via `getAll()` automatically.
 *
 * Implementations should:
 * - Use the tracker's existing authed API client (sub-classes of OAuth-flavored OkHttp).
 * - Bail out cleanly (return `emptyList()`) when the user is not logged in or the
 *   per-tracker "pull library from <tracker>" preference is off — the orchestrator
 *   filters first but defensive checks keep the contract obvious.
 * - Honor `Retry-After` on HTTP 429 by throwing a typed exception the orchestrator
 *   can pattern-match on.
 */
interface TrackerLibraryFetcher {
    /** The tracker this fetcher pulls from. Used by the orchestrator for the login + pref check. */
    val tracker: TrackService

    /** Pull the user's full tracked-library from the tracker. */
    suspend fun fetchLibrary(): List<TrackedEntry>
}
