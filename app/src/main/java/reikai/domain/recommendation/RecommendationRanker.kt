package reikai.domain.recommendation

import reikai.domain.recommendation.taste.TasteProfile
import reikai.domain.recommendation.taste.toTagKey
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.min

/**
 * Reorders the merged carousel pool against the user's taste profile: it only reorders, never adds or
 * drops candidates. The tracker slice passes through untouched. The source slice scores as
 * `(1 - w_personal) * popularity + w_personal * (taste + novelty) + agreement`, then a serendipity
 * reservation keeps `ceil(sourceSize * w_serendipity)` slots in popularity order, and a diversity cap
 * demotes any candidate past [maxPerDominantTag] sharing a dominant tag. With an empty profile only
 * the agreement boost applies, so the feature degrades silently. Pure compute, safe on every push.
 */
class RecommendationRanker(
    private val wPersonal: Double = DEFAULT_W_PERSONAL,
    private val wSerendipity: Double = DEFAULT_W_SERENDIPITY,
    private val maxPerDominantTag: Int = DEFAULT_MAX_PER_DOMINANT_TAG,
) {

    /**
     * @param pool merged, already library-filtered carousel list.
     * @param taste user's taste profile; an empty profile bypasses scoring.
     * @param agreementByUrl per-candidate count of how many streams surfaced its title; higher
     *   counts are boosted (a count of 1 adds nothing).
     */
    fun rank(
        pool: List<RelatedMangaCandidate>,
        taste: TasteProfile,
        agreementByUrl: Map<String, Int>,
    ): List<RelatedMangaCandidate> {
        val source = pool.filterNot { it.sourceId == RECOMMENDS_SOURCE }
        val tracker = pool.filter { it.sourceId == RECOMMENDS_SOURCE }

        if (taste.totalEntries == 0 || taste.tagScores.isEmpty() || source.size <= 1) {
            // No taste signal (or nothing to reorder): keep popularity order, but still float up
            // titles several sources agree on. Stable sort preserves popularity order within ties.
            return source.sortedByDescending { agreementByUrl[it.manga.url] ?: 1 } + tracker
        }

        val totalEntries = taste.totalEntries.toDouble()
        val scored = source.mapIndexed { index, candidate ->
            val tags = candidate.manga.getGenres()
                .orEmpty()
                .map { it.toTagKey() }
                .filter { it.isNotEmpty() }
            val tasteScore = if (tags.isEmpty()) {
                0.0
            } else {
                tags.sumOf { taste.tagScores[it] ?: 0.0 } / tags.size
            }
            val tagExposure = if (tags.isEmpty()) {
                totalEntries
            } else {
                tags.sumOf { (taste.tagEntryCounts[it] ?: 0).toDouble() }.coerceAtLeast(1.0)
            }
            val noveltyBoost = wSerendipity * min(NOVELTY_CAP, ln(1.0 + totalEntries / tagExposure))
            val popularityRank = 1.0 - index.toDouble() / source.size // earlier index → higher rank
            val agreement = agreementByUrl[candidate.manga.url] ?: 1
            val agreementBoost = AGREEMENT_WEIGHT * ln(agreement.toDouble()) // ln(1) = 0 → no boost
            val finalScore = (1.0 - wPersonal) * popularityRank +
                wPersonal * (tasteScore + noveltyBoost) +
                agreementBoost
            // Dominant tag for the diversity cap = the candidate's highest-affinity tag. Tags missing
            // from the affinity map score NEGATIVE_INFINITY, so a candidate with at least one known
            // tag always picks that one; if none are known, the first tag is used.
            val dominantTag = tags.maxByOrNull { taste.tagScores[it] ?: Double.NEGATIVE_INFINITY }
            Scored(candidate, dominantTag, finalScore)
        }

        val sourceSize = source.size
        val exploreCount = ceil(sourceSize * wSerendipity).toInt().coerceIn(0, sourceSize)
        // Exploration slots come from the top of the popularity-ordered slice (original arrival
        // order), so users always see "what the source ranked highest" regardless of taste. The
        // taste-ranked picks fill the remaining slots, skipping anything exploration already claimed.
        val explorationPicks = source.take(exploreCount)
        val explorationUrls = explorationPicks.mapTo(HashSet()) { it.manga.url }

        val tasteSorted = scored
            .sortedByDescending { it.finalScore }
            .filter { it.candidate.manga.url !in explorationUrls }

        val tasteKept = applyDiversityCap(tasteSorted, sourceSize - exploreCount)
        val reorderedSource = explorationPicks + tasteKept.map { it.candidate }

        return reorderedSource + tracker
    }

    /**
     * Walk [sorted] top-down. Keep up to [limit] candidates; for each, count its dominant tag. If
     * that tag has already filled [maxPerDominantTag] slots, set it aside; the deferred candidates
     * fill any remaining capacity at the end (preserving their taste-score order).
     */
    private fun applyDiversityCap(sorted: List<Scored>, limit: Int): List<Scored> {
        if (limit <= 0) return emptyList()
        val kept = ArrayList<Scored>(limit)
        val deferred = ArrayList<Scored>()
        val tagOccupancy = HashMap<String, Int>()

        for (entry in sorted) {
            if (kept.size >= limit) break
            val dominant = entry.dominantTag
            if (dominant != null && (tagOccupancy[dominant] ?: 0) >= maxPerDominantTag) {
                deferred += entry
                continue
            }
            kept += entry
            if (dominant != null) tagOccupancy.merge(dominant, 1) { a, _ -> a + 1 }
        }

        if (kept.size < limit) {
            for (entry in deferred) {
                if (kept.size >= limit) break
                kept += entry
            }
        }
        return kept
    }

    private class Scored(
        val candidate: RelatedMangaCandidate,
        val dominantTag: String?,
        val finalScore: Double,
    )

    companion object {
        const val DEFAULT_W_PERSONAL = 0.3
        const val DEFAULT_W_SERENDIPITY = 0.2
        const val DEFAULT_MAX_PER_DOMINANT_TAG = 2

        /** Cap on the per-candidate novelty boost so extremely rare tags don't dominate. */
        private const val NOVELTY_CAP = 2.0

        /** Weight of the cross-source agreement boost: AGREEMENT_WEIGHT * ln(streamCount). */
        private const val AGREEMENT_WEIGHT = 0.5
    }
}
