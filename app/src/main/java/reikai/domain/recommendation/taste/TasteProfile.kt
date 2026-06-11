package reikai.domain.recommendation.taste

/**
 * Per-tag affinity derived from the user's tracked manga, built by [ComputeTasteProfile].
 *
 * Each score is in `[-1.0, +1.0]`: positive means the user enjoys the tag (completed + rated high),
 * negative means they dislike it (dropped).
 *
 * @property tagScores tag to affinity score.
 * @property tagEntryCounts tag to how many tracked entries carry it (exposure breadth, for novelty).
 * @property totalEntries number of tracked entries the profile was built from.
 */
data class TasteProfile(
    val tagScores: Map<String, Double>,
    val tagEntryCounts: Map<String, Int>,
    val totalEntries: Int,
) {
    fun topTags(n: Int): List<String> = tagScores.entries
        .sortedByDescending { it.value }
        .take(n)
        .map { it.key }

    companion object {
        val EMPTY = TasteProfile(emptyMap(), emptyMap(), 0)
    }
}
