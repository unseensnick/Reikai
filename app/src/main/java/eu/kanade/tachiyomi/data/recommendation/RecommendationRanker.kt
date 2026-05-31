package eu.kanade.tachiyomi.data.recommendation

import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.min
import yokai.domain.library.taste.model.TasteProfile

/**
 * Phase 6 — reorders the merged carousel pool against the user's taste profile.
 *
 * Operates on the output of `MangaDetailsPresenter.mergeForDisplay`, so the 12-slot
 * tracker reserve + round-robin fairness is already baked into the input. Library
 * suppression (anti-echo) is applied by the caller before ranking, so this class only
 * reorders. The ranker:
 *
 * 1. **Partition.** Source-origin vs tracker-origin (same split `mergeForDisplay` does).
 *    The tracker slice is passed through untouched — round-robin fairness isn't taste
 *    business and tracker entries rarely carry parseable tags anyway.
 * 2. **Score the source slice** by `final = (1 − w_personal) × popularity + w_personal ×
 *    (taste + novelty_boost) + agreement_boost`. Untagged candidates score 0 on the taste
 *    axis; the agreement boost (ln of how many sources surfaced the title) floats up
 *    cross-source agreement independent of taste (and applies even with an empty profile).
 * 3. **Exploration reservation.** `⌈sourceSize × w_serendipity⌉` slots stay in their
 *    original popularity order (top of the unsorted pool), guaranteeing a baseline of
 *    "what the source thinks is broadly relevant" no matter how strong the taste signal.
 * 4. **Diversity cap.** No more than [maxPerDominantTag] kept candidates may share the
 *    same dominant tag. Walks the kept list top-down and demotes offenders past the next
 *    non-conflicting candidate. Single pass, no re-sort.
 *
 * **Empty profile path.** When [TasteProfile.totalEntries] is 0 or the score map is
 * empty, the ranker returns the input with only anti-echo applied — matching the
 * Phase 5 "feature degrades silently when no profile" pattern.
 *
 * Pure compute (no I/O, not suspend). Safe to invoke on every push from
 * `MangaDetailsPresenter.fetchRelatedMangasFromSource` since the input is capped at 30.
 */
class RecommendationRanker(
    private val wPersonal: Double = DEFAULT_W_PERSONAL,
    private val wSerendipity: Double = DEFAULT_W_SERENDIPITY,
    private val maxPerDominantTag: Int = DEFAULT_MAX_PER_DOMINANT_TAG,
) {

    /**
     * @param pool merged, already library-filtered carousel list from
     *   `MangaDetailsPresenter.mergeForDisplay`
     * @param taste user's taste profile from `ComputeTasteProfile`; empty profile bypasses scoring
     * @param agreementByUrl per-candidate count of how many streams surfaced its title; higher
     *   counts are boosted (a count of 1 adds nothing)
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
                .map { it.lowercase().trim() }
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
            val popularityRank = 1.0 - index.toDouble() / source.size  // earlier index → higher rank
            val agreement = agreementByUrl[candidate.manga.url] ?: 1
            val agreementBoost = AGREEMENT_WEIGHT * ln(agreement.toDouble())  // ln(1) = 0 → no boost
            val finalScore = (1.0 - wPersonal) * popularityRank +
                wPersonal * (tasteScore + noveltyBoost) +
                agreementBoost
            // Dominant tag for the diversity cap = the candidate's highest-affinity tag.
            // Tags missing from the affinity map score NEGATIVE_INFINITY, so a candidate
            // with at least one known tag always picks that one; if none of its tags are
            // known, the cap still operates on the candidate's first tag (the order kotlin
            // iterates picks the first encountered maximum).
            val dominantTag = tags.maxByOrNull { taste.tagScores[it] ?: Double.NEGATIVE_INFINITY }
            Scored(candidate, dominantTag, finalScore)
        }

        val sourceSize = source.size
        val exploreCount = ceil(sourceSize * wSerendipity).toInt().coerceIn(0, sourceSize)
        // Exploration slots come from the top of the popularity-ordered slice (original
        // arrival order), so users always see "what the source ranked highest" regardless
        // of taste-axis preferences. The taste-ranked picks fill the remaining slots,
        // skipping anything already claimed by exploration.
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
     * Walk [sorted] top-down. Keep up to [limit] candidates total; for each, count its
     * dominant tag (highest-affinity tag among the candidate's tags). If that tag has
     * already filled [maxPerDominantTag] slots, set it aside and continue; the deferred
     * candidates fill any remaining capacity at the end.
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

        // Pull deferred entries back in once the cap-stretching ones have settled,
        // preserving their original taste-score order. They lose their seat only if
        // a non-conflicting candidate took it first, which is the whole point.
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

        /** Weight of the cross-source agreement boost: AGREEMENT_WEIGHT * ln(sourceCount). */
        private const val AGREEMENT_WEIGHT = 0.5
    }
}
