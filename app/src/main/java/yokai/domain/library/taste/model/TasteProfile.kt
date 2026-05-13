package yokai.domain.library.taste.model

/**
 * Per-tag affinity derived from the user's tracked manga.
 *
 * Each score is in `[-1.0, +1.0]`:
 * - positive → user enjoys this tag (completed + rated high)
 * - negative → user dislikes this tag (dropped)
 *
 * Built by [yokai.domain.library.taste.interactor.ComputeTasteProfile].
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
