package reikai.domain.recommendation

import dev.zacsweers.metro.Inject
import reikai.domain.recommendation.taste.GetTasteProfile
import reikai.domain.recommendation.taste.TasteProfile

/** A related pool as the loader gathers it: deduped, unranked, with how many streams agreed on each url. */
data class RelatedPool(
    val candidates: List<RelatedMangaCandidate>,
    val agreementByUrl: Map<String, Int>,
) {
    companion object {
        val EMPTY = RelatedPool(emptyList(), emptyMap())
    }
}

/**
 * The one read-time assembly of a related pool, for the details carousel and the See-all grid alike:
 * drop what the hide filter hides, rank the rest, and for a capped list keep up to [TRACKER_RESERVE]
 * slots for tracker recommendations, round-robin across trackers. Filtering first sizes the ranker's
 * popularity picks against what will show; the reserve stops a source that fills the cap alone from
 * starving the trackers, and either side cedes room it cannot fill. Running on read keeps a filter
 * change out of the cached pool.
 */
class RecommendationAssembly(
    val hideFilter: RecommendationHideFilter,
    private val ranker: RecommendationRanker,
    private val taste: TasteProfile,
) {

    fun assemble(pool: RelatedPool, cap: Int? = null): List<RelatedMangaCandidate> {
        val ranked = ranker.rank(pool.candidates.filterNot(hideFilter::shouldHide), taste, pool.agreementByUrl)
        if (cap == null) return ranked
        val (tracker, source) = ranked.partition { it.sourceId == RECOMMENDS_SOURCE }
        val sourceTake = minOf(source.size, cap - minOf(tracker.size, TRACKER_RESERVE))
        return source.take(sourceTake) + roundRobin(tracker.groupBy { it.trackerId }.values, cap - sourceTake)
    }
}

private const val TRACKER_RESERVE = 12

/** Interleaves [lists] one element at a time until [limit]; a longer list drains once the others run out. */
private fun <T> roundRobin(lists: Collection<List<T>>, limit: Int): List<T> {
    val iterators = lists.map { it.iterator() }.filter { it.hasNext() }.toMutableList()
    val out = ArrayList<T>()
    while (out.size < limit && iterators.isNotEmpty()) {
        val each = iterators.iterator()
        while (each.hasNext() && out.size < limit) {
            val list = each.next()
            out += list.next()
            if (!list.hasNext()) each.remove()
        }
    }
    return out
}

/** Builds the assembly both screens use, from the same preferences, hide filter and taste profile. */
@Inject
class PrepareRecommendationAssembly(
    private val preferences: ReikaiRecommendationPreferences,
    private val getTasteProfile: GetTasteProfile,
    private val buildHideFilter: BuildRecommendationHideFilter,
) {
    suspend fun await() = RecommendationAssembly(
        hideFilter = buildHideFilter.await(),
        ranker = preferences.buildRanker(),
        // Rerank off gives an empty profile, which collapses the ranker to popularity order.
        taste = if (preferences.enableRecommendationRerank.get()) getTasteProfile.await() else TasteProfile.EMPTY,
    )
}
