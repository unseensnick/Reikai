package reikai.domain.recommendation.taste

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.service.TrackPreferences

/**
 * The cached tracker libraries as the recommendation layer is allowed to see them, honouring the
 * adult-content setting.
 *
 * Deliberately not on the repository: [reikai.domain.recommendation.BuildRecommendationHideFilter]
 * reads the same cache to build its anti-echo index, and filtering that would make adult titles the
 * user already tracks start appearing as suggestions.
 */
@Inject
class GetTasteEntries(
    private val repository: TasteLibraryRepository,
    private val trackPreferences: TrackPreferences,
) {
    suspend fun await(): List<TrackedEntry> {
        val entries = repository.getAll()
        if (trackPreferences.showAdultTrackerContent.get()) return entries
        return entries.filterNot { it.isSexuallyExplicit() }
    }
}
