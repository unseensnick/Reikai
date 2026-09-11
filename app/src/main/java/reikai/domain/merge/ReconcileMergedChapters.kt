package reikai.domain.merge

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType

/**
 * Brings the stored cross-source stitch back in line with what it was built from: its chapters and
 * its source ranking. Written once over both content types; how a group's chapters are loaded and
 * stitched is each [MergedGroupStitcher]'s.
 *
 * Three indexed queries per content type when nothing changed, so any path that may have written can
 * call it. [awaitGroup] reads one group's chapters; its ranking check reads every group's member rows.
 */
@Inject
@SingleIn(AppScope::class)
class ReconcileMergedChapters(
    private val repository: MergedChapterUnitRepository,
    private val stitchers: Set<MergedGroupStitcher>,
) {

    /** Rebuild every stale group of every content type. */
    suspend fun await() {
        stitchers.forEach { stitcher ->
            val rankings = stitcher.rankings()
            val stored = repository.getRankings()
            // Only this type's groups are compared: the stored stamps cover both types, and a group
            // checked against the other type's stitcher would be cleared by it.
            val reranked = rankings.filter { (groupId, ranking) -> stored[groupId] != ranking }.keys
            (repository.getStaleGroups(stitcher.contentType) + reranked).distinct().forEach { groupId ->
                rebuild(stitcher, groupId, rankings[groupId])
            }
        }
    }

    /**
     * Rebuild [groupId] if it is stale, for a screen about to render it. A group whose chapters just
     * arrived cannot wait for the next library update to be stitched, and a reader that stitched for
     * itself instead is how the surfaces came to disagree in the first place.
     */
    suspend fun awaitGroup(contentType: ContentType, groupId: Long) {
        val stitcher = stitchers.firstOrNull { it.contentType == contentType } ?: return
        val ranking = stitcher.rankings()[groupId]
        if (repository.isStale(contentType, groupId) || repository.getRankings()[groupId] != ranking) {
            rebuild(stitcher, groupId, ranking)
        }
    }

    /**
     * The stamp stored is the one read before stitching, so a ranking that changes mid-rebuild leaves
     * the group reading as stale rather than recorded under a ranking it was not built with.
     */
    private suspend fun rebuild(stitcher: MergedGroupStitcher, groupId: Long, ranking: String?) {
        repository.replaceGroup(stitcher.contentType, groupId, stitcher.stitch(groupId), ranking)
    }
}
