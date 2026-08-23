package reikai.domain.track

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import reikai.domain.recommendation.taste.AdultTagOverrides
import reikai.domain.recommendation.taste.areTagsSexuallyExplicit
import reikai.domain.recommendation.taste.toTagKey
import tachiyomi.domain.track.model.Track

/**
 * "Fill from tracker" metadata, with sexually explicit genres dropped unless the user has opted in.
 *
 * Only [TrackMangaMetadata.genres] is screened: it is the one field that classifies content, and an
 * entry's genres are read back by the library's own lewd filter and by notification hiding, so an
 * autofill can reclassify an entry app-wide rather than just label it.
 *
 * Both details ViewModels call this, so the rule exists once for manga and novels.
 */
@Inject
class GetTrackerMetadata(
    private val trackPreferences: TrackPreferences,
) {
    suspend fun await(track: Track, tracker: Tracker): TrackMangaMetadata {
        val metadata = tracker.getMangaMetadata(track)
        if (trackPreferences.showAdultTrackerContent.get()) return metadata
        val overrides = AdultTagOverrides(
            alwaysAdult = trackPreferences.alwaysAdultTags.get(),
            neverAdult = trackPreferences.neverAdultTags.get(),
        )
        return metadata.copy(genres = metadata.genres?.filterExplicit(overrides))
    }

    /** Judged one genre at a time: this drops the explicit ones rather than the whole list. */
    private fun List<String>.filterExplicit(overrides: AdultTagOverrides): List<String>? =
        filterNot { areTagsSexuallyExplicit(listOf(it.toTagKey()), overrides) }
            .takeIf { it.isNotEmpty() }
}
