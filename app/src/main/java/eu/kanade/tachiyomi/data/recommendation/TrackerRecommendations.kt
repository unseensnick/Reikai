package eu.kanade.tachiyomi.data.recommendation

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Base contract for a tracker-backed recommendation fetcher. Mirrors Komikku's
 * `TrackerRecommendationPagingSource.requestNextPage` dispatch: prefer an id-based lookup when
 * the user has a track entry for the current manga, otherwise resolve the id via a title search.
 *
 * Concrete subclasses target a single public endpoint (AniList GraphQL, Jikan, MangaUpdates v1)
 * and don't use the tracker's authenticated client — only the shared [okhttp3.OkHttpClient].
 */
abstract class TrackerRecommendations {
    abstract val trackerName: String

    abstract suspend fun getRecsById(remoteId: Long): List<SManga>

    abstract suspend fun getRecsBySearch(title: String): List<SManga>

    suspend fun fetch(remoteId: Long?, title: String): List<SManga> =
        if (remoteId != null) getRecsById(remoteId) else getRecsBySearch(title)
}
