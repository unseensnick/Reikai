package reikai.domain.merge

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
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
 * When nothing changed it reads the pending list, which triggers fill on every stitch input, and every
 * group's member rows for the ranking check, so any path that may have written can call it.
 */
@Inject
@SingleIn(AppScope::class)
class ReconcileMergedChapters(
    private val repository: MergedChapterUnitRepository,
    private val stitchers: Set<MergedGroupStitcher>,
) {

    private val rebuildLock = Mutex()

    /**
     * Rebuild every stale group of every content type. A group that fails is logged and keeps its claim
     * for the next run, so one bad group holds up neither the rest of its type nor the other type.
     */
    suspend fun await() {
        stitchers.forEach { stitcher ->
            loggingFailure({ "${stitcher.contentType} merged groups were not reconciled" }) {
                val rankings = stitcher.rankings()
                val stored = repository.getRankings()
                // Only this type's groups are compared: the stored stamps cover both types, and a group
                // checked against the other type's stitcher would be cleared by it.
                val reranked = rankings.filter { (groupId, ranking) -> stored[groupId] != ranking }.keys
                (repository.claimPendingGroups(stitcher.contentType) + reranked).distinct().forEach { groupId ->
                    loggingFailure({ "Merged group $groupId was not restitched" }) {
                        rebuildIfStale(stitcher, groupId, rankings[groupId])
                    }
                }
            }
        }
    }

    private suspend inline fun loggingFailure(message: () -> String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e, message)
        }
    }

    /**
     * Runs [pass], a run of chapter writes, then [await]s however it ended. A renumbered or renamed
     * chapter leaves a stitch as stale as a new one does, and a pass that failed or was cancelled
     * midway has already written everything before that point. [await] logs a failed reconcile rather
     * than throwing it, so it neither fails a pass whose writes landed nor hides the pass's own error.
     */
    suspend fun <T> afterPass(pass: suspend () -> T): T =
        try {
            pass()
        } finally {
            withContext(NonCancellable) { await() }
        }

    /**
     * [groupId]'s stored stitch, rebuilt first if it is stale, for a screen about to render it. A group
     * whose chapters just arrived cannot wait for the next library update to be stitched, and a reader
     * that stitched for itself instead is how the surfaces came to disagree in the first place.
     */
    suspend fun currentStitch(contentType: ContentType, groupId: Long): List<ChapterUnit> {
        stitchers.firstOrNull { it.contentType == contentType }?.let { stitcher ->
            rebuildIfStale(stitcher, groupId, stitcher.rankings()[groupId])
        }
        return repository.getStitch(contentType, groupId)
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
            repository.releaseClaim(groupId)
        }
}
