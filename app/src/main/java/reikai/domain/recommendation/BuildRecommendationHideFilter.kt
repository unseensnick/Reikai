package reikai.domain.recommendation

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.first
import reikai.domain.recommendation.taste.LocalTrackStatusMapper
import reikai.domain.recommendation.taste.TasteLibraryRepository
import reikai.domain.recommendation.taste.TrackStatus
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracksPerManga

/**
 * Assembles a [RecommendationHideFilter] from the user's library, its tracks and the taste-library
 * cache. The library index is always built, since it marks a card when the opt-in filter is not hiding
 * it. The status index draws from **both** the library's own tracks and the cached tracker lists, so
 * a title tracked-but-not-in-library (the common gap) is suppressed too.
 */
@Inject
class BuildRecommendationHideFilter(
    private val getFavorites: GetFavorites,
    private val getTracksPerManga: GetTracksPerManga,
    private val repository: TasteLibraryRepository,
    private val preferences: ReikaiRecommendationPreferences,
    private val localTrackStatusMapper: LocalTrackStatusMapper,
    private val trackerManager: TrackerManager,
) {

    suspend fun await(): RecommendationHideFilter {
        val anilistTrackerId = trackerManager.aniList.id
        val malTrackerId = trackerManager.myAnimeList.id

        val hideInLibrary = preferences.hideInLibraryRecommendations.get()
        val hiddenStatuses = buildSet {
            if (preferences.hideTrackedReadingCompleted.get()) {
                add(TrackStatus.READING)
                add(TrackStatus.COMPLETED)
            }
            if (preferences.hideTrackedDropped.get()) add(TrackStatus.DROPPED)
            if (preferences.hideTrackedOnHold.get()) add(TrackStatus.ON_HOLD)
            if (preferences.hideTrackedPlanToRead.get()) add(TrackStatus.PLAN_TO_READ)
        }
        val favorites = getFavorites.await()
        val tracksByManga = getTracksPerManga.subscribe().first()

        val inLibrary = IndexBuilder(anilistTrackerId, malTrackerId)
        val status = IndexBuilder(anilistTrackerId, malTrackerId)

        for (manga in favorites) {
            inLibrary.addManga(manga)
            for (track in tracksByManga[manga.id].orEmpty()) {
                inLibrary.addTrack(track.trackerId, track.remoteId)
            }
        }

        if (hiddenStatuses.isNotEmpty()) {
            for (manga in favorites) {
                for (track in tracksByManga[manga.id].orEmpty()) {
                    if (localTrackStatusMapper.map(track) in hiddenStatuses) {
                        status.addTrack(track.trackerId, track.remoteId)
                        status.addManga(manga)
                    }
                }
            }
            for (entry in repository.getAll()) {
                if (entry.status in hiddenStatuses) {
                    status.addPair(entry.trackerId, entry.remoteId)
                    entry.anilistId?.let(status::addAnilistId)
                    entry.malId?.let(status::addMalId)
                    status.addTitle(entry.title)
                }
            }
        }

        return RecommendationHideFilter(
            inLibrary.build(),
            hideInLibrary,
            status.build(),
            anilistTrackerId,
            malTrackerId,
        )
    }

    private class IndexBuilder(private val anilistTrackerId: Long, private val malTrackerId: Long) {
        private val sourceKeys = HashSet<Pair<String, Long>>()
        private val pairs = HashSet<Pair<Long, Long>>()
        private val anilistIds = HashSet<Long>()
        private val malIds = HashSet<Long>()
        private val titles = HashSet<String>()

        fun addPair(trackerId: Long, remoteId: Long) {
            pairs += trackerId to remoteId
        }
        fun addAnilistId(id: Long) {
            anilistIds += id
        }
        fun addMalId(id: Long) {
            malIds += id
        }

        fun addTitle(title: String) {
            TitleNormalizer.normalize(title).takeIf { it.isNotEmpty() }?.let { titles += it }
        }

        fun addManga(manga: Manga) {
            sourceKeys += manga.url to manga.source
            addTitle(manga.title)
        }

        fun addTrack(trackerId: Long, remoteId: Long) {
            pairs += trackerId to remoteId
            if (trackerId == anilistTrackerId) anilistIds += remoteId
            if (trackerId == malTrackerId) malIds += remoteId
        }

        fun build() = RecommendationHideFilter.Index(sourceKeys, pairs, anilistIds, malIds, titles)
    }
}
