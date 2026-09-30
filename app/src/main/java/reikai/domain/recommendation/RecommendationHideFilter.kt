package reikai.domain.recommendation

/**
 * Says whether a related-manga candidate is already in the library, and whether to hide it because
 * the user has or tracks it. Pure, built once per open. Matching is identity-first, title-fallback: a
 * library row by url and source, a tracker pick by `(trackerId, remoteId)` or a recorded AniList or
 * MAL id, then normalized titles, since a source can list one series under several urls. [inLibrary]
 * is always filled and hides only when [hidesInLibrary]; [hiddenStatus] is empty with its filters off.
 */
class RecommendationHideFilter(
    private val inLibrary: Index,
    private val hidesInLibrary: Boolean,
    private val hiddenStatus: Index,
    private val anilistTrackerId: Long,
    private val malTrackerId: Long,
) {

    /** The one rule for "already in my library": the hide filter hides exactly what this marks. */
    fun isInLibrary(candidate: RelatedMangaCandidate): Boolean = matches(candidate, inLibrary)

    fun shouldHide(candidate: RelatedMangaCandidate): Boolean =
        (hidesInLibrary && isInLibrary(candidate)) || matches(candidate, hiddenStatus)

    private fun matches(candidate: RelatedMangaCandidate, index: Index): Boolean {
        if (index.isEmpty) return false
        if (candidate.manga.url to candidate.sourceId in index.sourceKeys) return true
        val remoteId = candidate.remoteId
        if (remoteId != null) {
            if (candidate.trackerId to remoteId in index.pairs) return true
            if (candidate.trackerId == anilistTrackerId && remoteId in index.anilistIds) return true
            if (candidate.trackerId == malTrackerId && remoteId in index.malIds) return true
        }
        return candidate.titleKeys().any { it in index.titles }
    }

    /** [sourceKeys] are library rows as `(url, sourceId)`; [pairs] are tracker `(trackerId, remoteId)`. */
    data class Index(
        val sourceKeys: Set<Pair<String, Long>>,
        val pairs: Set<Pair<Long, Long>>,
        val anilistIds: Set<Long>,
        val malIds: Set<Long>,
        val titles: Set<String>,
    ) {
        val isEmpty: Boolean
            get() = sourceKeys.isEmpty() && pairs.isEmpty() && anilistIds.isEmpty() && malIds.isEmpty() &&
                titles.isEmpty()

        companion object {
            val EMPTY = Index(emptySet(), emptySet(), emptySet(), emptySet(), emptySet())
        }
    }
}
