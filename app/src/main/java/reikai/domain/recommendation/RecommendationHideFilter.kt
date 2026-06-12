package reikai.domain.recommendation

/**
 * Decides whether a related-manga candidate should be hidden because the user already has or tracks
 * it. Pure: built once per details/browse open by [BuildRecommendationHideFilter] from the library's
 * tracks and the taste-library cache, then queried per candidate.
 *
 * Matching is identity-first, title-fallback: a candidate from a tracker recs endpoint carries a
 * `(trackerId, remoteId)`, matched exactly against the user's lists (and cross-tracker via the AniList
 * / MAL id a tracked entry recorded). Source-native candidates carry no id, so they fall back to
 * normalized-title matching ([RelatedMangaCandidate.titleKeys]).
 *
 * Two independent indexes back the two opt-in filter groups: [inLibrary] (hide manga already in the
 * library) and [hiddenStatus] (hide manga tracked with a status the user chose to suppress). Each is
 * empty when its filter is off, so [shouldHide] is a cheap no-op when nothing is enabled ([isNoOp]).
 */
class RecommendationHideFilter(
    private val inLibrary: Index,
    private val hiddenStatus: Index,
    private val anilistTrackerId: Long,
    private val malTrackerId: Long,
) {

    val isNoOp: Boolean get() = inLibrary.isEmpty && hiddenStatus.isEmpty

    fun shouldHide(candidate: RelatedMangaCandidate): Boolean =
        matches(candidate, inLibrary) || matches(candidate, hiddenStatus)

    private fun matches(candidate: RelatedMangaCandidate, index: Index): Boolean {
        if (index.isEmpty) return false
        val remoteId = candidate.remoteId
        if (remoteId != null) {
            if (candidate.trackerId to remoteId in index.pairs) return true
            if (candidate.trackerId == anilistTrackerId && remoteId in index.anilistIds) return true
            if (candidate.trackerId == malTrackerId && remoteId in index.malIds) return true
        }
        return candidate.titleKeys().any { it in index.titles }
    }

    /** Identity keys for one filter group: exact tracker ids, cross-tracker AniList/MAL ids, and
     *  normalized-title fallbacks. */
    data class Index(
        val pairs: Set<Pair<Long, Long>>,
        val anilistIds: Set<Long>,
        val malIds: Set<Long>,
        val titles: Set<String>,
    ) {
        val isEmpty: Boolean
            get() = pairs.isEmpty() && anilistIds.isEmpty() && malIds.isEmpty() && titles.isEmpty()

        companion object {
            val EMPTY = Index(emptySet(), emptySet(), emptySet(), emptySet())
        }
    }
}
