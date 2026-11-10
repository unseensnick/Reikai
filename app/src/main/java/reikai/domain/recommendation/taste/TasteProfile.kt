package reikai.domain.recommendation.taste

import reikai.domain.library.TagAliases

/**
 * Per-tag affinity derived from the user's tracked manga, built by [ComputeTasteProfile]. Each score
 * is in `[-1.0, +1.0]`: positive means the user enjoys the tag, negative means they dropped it.
 *
 * @property tagScores tag to affinity score.
 * @property tagEntryCounts tag to how many tracked entries carry it (exposure breadth, for novelty).
 * @property totalEntries number of tracked entries the profile was built from.
 * @property tagAliases the user's tag aliases the profile was keyed by, which a candidate's tags take too.
 */
data class TasteProfile(
    val tagScores: Map<String, Double>,
    val tagEntryCounts: Map<String, Int>,
    val totalEntries: Int,
    val tagAliases: TagAliases = TagAliases.NONE,
) {
    /** [tags] as this profile keys them: aliased, then [toTagKeys]. */
    fun keysOf(tags: List<String>): List<String> = tagAliases.resolveAll(tags).toTagKeys()

    companion object {
        val EMPTY = TasteProfile(emptyMap(), emptyMap(), 0)
    }
}
