package reikai.domain.recommendation.taste

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import reikai.util.DisjointSet
import kotlin.math.abs

/**
 * Pure reduction of `List<TrackedEntry>` to a `TasteProfile`. Per tag, the score is the sum of
 * `rating * status_weight` over entries carrying it, divided by the sum of `|status_weight|`, clamped
 * to [-1, +1]. Status weights run COMPLETED +1.0, READING +0.7, ON_HOLD +0.3, DROPPED -1.0, with
 * PLAN_TO_READ and UNKNOWN signal-free at 0. An unrated entry substitutes a rating of 0.5, so status
 * still signals direction at neutral magnitude. The denominator takes the ABSOLUTE weight so a tag
 * with equal completed and dropped counts cannot divide by zero.
 */
@Inject
class ComputeTasteProfile(trackerManager: TrackerManager) {

    // AniList carries the richest tags, so its row wins over MyAnimeList's, and MyAnimeList's over Kitsu's.
    private val trackerPriority = mapOf(
        trackerManager.aniList.id to 0,
        trackerManager.myAnimeList.id to 1,
        trackerManager.kitsu.id to 2,
    )

    operator fun invoke(entries: List<TrackedEntry>): TasteProfile {
        val unique = entries.dedupedAcrossTrackers()
        if (unique.isEmpty()) return TasteProfile.EMPTY

        val numerators = HashMap<String, Double>()
        val denominators = HashMap<String, Double>()
        val tagCounts = HashMap<String, Int>()

        for (entry in unique) {
            // Novelty wants exposure breadth across the whole library, so count every entry's tags
            // regardless of status weight; PLAN_TO_READ / UNKNOWN are skipped for scoring but still
            // register as "I've seen this tag."
            for (tag in entry.tags) {
                tagCounts.merge(tag, 1) { a, b -> a + b }
            }
            val statusWeight = STATUS_WEIGHTS[entry.status] ?: 0.0
            if (statusWeight == 0.0) continue
            val rating = if (entry.score >= 0.0) entry.score else 0.5
            val contribution = rating * statusWeight
            val absWeight = abs(statusWeight)
            for (tag in entry.tags) {
                numerators.merge(tag, contribution) { a, b -> a + b }
                denominators.merge(tag, absWeight) { a, b -> a + b }
            }
        }

        val scores = numerators.mapValues { (tag, num) ->
            val denom = denominators[tag] ?: return@mapValues 0.0
            (num / denom).coerceIn(-1.0, 1.0)
        }
        return TasteProfile(
            tagScores = scores,
            tagEntryCounts = tagCounts,
            totalEntries = unique.size,
        )
    }

    /**
     * One series tracked on several services counts once: rows sharing a malId or an anilistId are one
     * series, transitively (a Kitsu row carrying both can tie an AniList row lacking idMal to a MAL row),
     * and the AniList row is kept over MAL's, MAL's over Kitsu's. Rows with neither id stand alone.
     */
    private fun List<TrackedEntry>.dedupedAcrossTrackers(): List<TrackedEntry> {
        val sets = DisjointSet<Int>()
        val firstByMal = HashMap<Long, Int>()
        val firstByAnilist = HashMap<Long, Int>()
        forEachIndexed { index, entry ->
            entry.malId?.let { sets.union(firstByMal.getOrPut(it) { index }, index) }
            entry.anilistId?.let { sets.union(firstByAnilist.getOrPut(it) { index }, index) }
        }
        return indices.groupBy(sets::find).values.map { series ->
            this[series.minBy { trackerPriority[this[it].trackerId] ?: Int.MAX_VALUE }]
        }
    }

    companion object {
        val STATUS_WEIGHTS: Map<TrackStatus, Double> = mapOf(
            TrackStatus.COMPLETED to 1.0,
            TrackStatus.READING to 0.7,
            TrackStatus.ON_HOLD to 0.3,
            TrackStatus.PLAN_TO_READ to 0.0,
            TrackStatus.DROPPED to -1.0,
            TrackStatus.UNKNOWN to 0.0,
        )
    }
}
