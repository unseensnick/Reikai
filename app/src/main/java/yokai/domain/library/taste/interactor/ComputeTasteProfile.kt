package yokai.domain.library.taste.interactor

import kotlin.math.abs
import yokai.domain.library.taste.model.TasteProfile
import yokai.domain.library.taste.model.TrackStatus
import yokai.domain.library.taste.model.TrackedEntry

/**
 * Pure (no I/O) reduction: `List<TrackedEntry>` → `TasteProfile`.
 *
 * **Status weights** (from `docs/suggestions-plan.md` §"Taste profile"):
 *
 * | Status | Weight |
 * |---|---|
 * | COMPLETED | +1.0 |
 * | READING | +0.7 |
 * | ON_HOLD | +0.3 |
 * | PLAN_TO_READ | 0.0 (signal-free) |
 * | DROPPED | -1.0 |
 * | UNKNOWN | 0.0 (signal-free) |
 *
 * **Formula** (per tag `t`):
 * ```
 *   score(t) = Σ (rating × status_weight)  /  Σ |status_weight|
 *               over manga tagged with t and with non-zero status_weight
 * ```
 *
 * - `rating` is the normalized 0..1 score from the tracker. Unrated entries (`-1.0`)
 *   substitute `0.5` so status still signals direction with neutral magnitude.
 * - The denominator uses `|status_weight|` — diverges from the doc's literal
 *   `Σ status_weight` because that form divides by zero whenever a tag has equal
 *   COMPLETED and DROPPED counts. `|·|` preserves the intended weighting (completed
 *   counts more than reading) without the divide-by-zero pathology.
 * - Result is clamped to `[-1.0, +1.0]`.
 */
class ComputeTasteProfile {

    operator fun invoke(entries: List<TrackedEntry>): TasteProfile {
        if (entries.isEmpty()) return TasteProfile.EMPTY

        val numerators = HashMap<String, Double>()
        val denominators = HashMap<String, Double>()

        for (entry in entries) {
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
        return TasteProfile(tagScores = scores, totalEntries = entries.size)
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
