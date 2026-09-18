package reikai.domain.merge

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.system.logcat

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

    private val rebuildLock = Mutex()

    /** Rebuild every stale group of every content type. */
    suspend fun await() {
        stitchers.forEach { stitcher ->
            val rankings = stitcher.rankings()
            val stored = repository.getRankings()
            // Only this type's groups are compared: the stored stamps cover both types, and a group
            // checked against the other type's stitcher would be cleared by it.
            val reranked = rankings.filter { (groupId, ranking) -> stored[groupId] != ranking }.keys
            (repository.getStaleGroups(stitcher.contentType) + reranked).distinct().forEach { groupId ->
                rebuildIfStale(stitcher, groupId, rankings[groupId])
            }
        }
    }

    /**
     * Runs [pass], a run of chapter writes, then [await]s however it ended. A renumbered or renamed
     * chapter leaves a stitch as stale as a new one does, and a pass that failed or was cancelled
     * midway has already written everything before that point. A failed reconcile is logged rather
     * than thrown, so it neither fails a pass whose writes landed nor hides the pass's own error.
     */
    suspend fun <T> afterPass(pass: suspend () -> T): T =
        try {
            pass()
        } finally {
            withContext(NonCancellable) {
                try {
                    await()
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Merged chapters were not reconciled after a pass" }
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
        rebuildIfStale(stitcher, groupId, stitcher.rankings()[groupId])
    }

    /**
     * Checked and rebuilt under one lock, so each rebuild starts after the last one committed and
     * re-checks what it left. Unlocked, a rebuild that read its chapters before a new one arrived could
     * commit over one that read it, dropping the chapter until something else ran a pass.
     * The stamp stored is the one read before stitching, so a ranking that changes mid-rebuild leaves
     * the group reading as stale rather than recorded under a ranking it was not built with.
     */
    private suspend fun rebuildIfStale(stitcher: MergedGroupStitcher, groupId: Long, ranking: String?) =
        rebuildLock.withLock {
            val contentType = stitcher.contentType
            if (repository.isStale(contentType, groupId) || repository.getRankings()[groupId] != ranking) {
                repository.replaceGroup(contentType, groupId, stitcher.stitch(groupId), ranking)
            }
        }
}
