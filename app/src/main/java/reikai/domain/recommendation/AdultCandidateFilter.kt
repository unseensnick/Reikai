package reikai.domain.recommendation

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.service.TrackPreferences
import reikai.domain.recommendation.taste.AdultTagOverrides
import reikai.domain.recommendation.taste.resolveSexuallyExplicit
import reikai.domain.recommendation.taste.toTagKey

/**
 * Keeps sexually explicit suggestions off the carousel, the companion to the taste-profile filter:
 * one stops adult titles shaping recommendations, this stops them being recommended. Applied where
 * [RecommendationHideFilter] is, on read rather than on accumulate, so the cached pool stays neutral
 * and flipping the setting takes effect on the next open instead of waiting out the cache.
 */
class AdultCandidateFilter(
    private val enabled: Boolean,
    private val overrides: AdultTagOverrides,
) {

    fun shouldHide(candidate: RelatedMangaCandidate): Boolean =
        enabled && resolveSexuallyExplicit(candidate.adult, candidate.tagKeys(), overrides)

    private fun RelatedMangaCandidate.tagKeys(): List<String> =
        manga.getGenres().orEmpty().map { it.toTagKey() }.filter { it.isNotEmpty() }
}

/** Built once per carousel open, matching how the hide filter is built. */
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
