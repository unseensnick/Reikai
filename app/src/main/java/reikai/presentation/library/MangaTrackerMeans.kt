package reikai.presentation.library

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.track.model.Track

/**
 * Mean 0-10 tracker score per manga row, keyed by the row's own id, unscored rows absent (callers
 * default to -1.0). Scored over the row's whole merged group (relatedMangaIds), deduped by tracker and
 * dropping unrated (<= 0) scores, so a tracker on any grouped source contributes once, matching the
 * novel library. Guarding on the mapped scores (not the raw track list) fixes the upstream bug where an
 * all-logged-out track list averaged to NaN and sorted above every real score.
 *
 * The one computation behind both the manga model's sort and the provider seam ([LibraryProvider.trackerMeans]),
 * so the scoring rule cannot drift between them. [trackers] is the logged-in trackers keyed by id.
 */
fun mangaTrackerMeans(
    items: Collection<LibraryItem>,
    trackMap: Map<Long, List<Track>>,
    trackers: Map<Long, Tracker>,
): Map<Long, Double> = buildMap {
    items.forEach { item ->
        val ids = item.relatedMangaIds.ifEmpty { listOf(item.id) }
        val scores = ids.flatMap { trackMap[it].orEmpty() }
            .distinctBy { it.trackerId }
            .mapNotNull { trackers[it.trackerId]?.get10PointScore(it)?.takeIf { s -> s > 0.0 } }
        if (scores.isNotEmpty()) put(item.id, scores.average())
    }
}
