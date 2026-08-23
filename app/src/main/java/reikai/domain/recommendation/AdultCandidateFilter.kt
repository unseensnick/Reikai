package reikai.domain.recommendation

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.service.TrackPreferences
import reikai.domain.recommendation.taste.AdultContent
import reikai.domain.recommendation.taste.AdultTagOverrides
import reikai.domain.recommendation.taste.areTagsSexuallyExplicit
import reikai.domain.recommendation.taste.toTagKey

/**
 * Keeps sexually explicit suggestions off the carousel, the companion to the taste-profile filter:
 * one stops adult titles shaping recommendations, this stops them being recommended.
 *
 * Applied where [RecommendationHideFilter] is, on read rather than on accumulate, so the cached pool
 * stays neutral and flipping the setting takes effect on the next open instead of waiting out the
 * cache. [isNoOp] is true when the user has opted into adult content, which is the common path.
 */
class AdultCandidateFilter(
    private val enabled: Boolean,
    private val overrides: AdultTagOverrides,
) {

    val isNoOp: Boolean get() = !enabled

    fun shouldHide(candidate: RelatedMangaCandidate): Boolean {
        if (!enabled) return false
        return when (candidate.adult) {
            AdultContent.ADULT -> true
            AdultContent.CLEAN -> false
            AdultContent.UNKNOWN -> areTagsSexuallyExplicit(candidate.tagKeys(), overrides)
        }
    }

    private fun RelatedMangaCandidate.tagKeys(): List<String> =
        manga.getGenres().orEmpty().map { it.toTagKey() }.filter { it.isNotEmpty() }
}

/**
 * Builds the filter once per carousel open, matching how the hide filter is built. Cheap: two
 * preference reads and no I/O.
 */
@Inject
class BuildAdultCandidateFilter(
    private val trackPreferences: TrackPreferences,
) {
    fun build(): AdultCandidateFilter = AdultCandidateFilter(
        enabled = !trackPreferences.showAdultTrackerContent.get(),
        overrides = AdultTagOverrides(
            alwaysAdult = trackPreferences.alwaysAdultTags.get(),
            neverAdult = trackPreferences.neverAdultTags.get(),
        ),
    )
}
