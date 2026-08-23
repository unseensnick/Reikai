package reikai.presentation.details

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import reikai.domain.merge.MergeManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.i18n.MR

/**
 * The shared source split / remove / reorder actions for a merged entry's details screen, so the
 * snackbar-with-undo logic both details models run lives in one place. The one per-type difference left
 * is [setFavorite]: novels write favorite-only, so a merge-undo restores the original dateAdded.
 * Handing each member its own tracker copy is NOT here; [MergeManager] does it on every path that
 * breaks a group up. [anchorId] is a getter, not a captured value, because the novel model resolves its
 * anchor after construction. selectSource and showManageSourcesDialog stay out: those bodies diverge.
 */
class EntryMergeActionHost(
    private val scope: CoroutineScope,
    private val snackbarHostState: SnackbarHostState,
    private val context: Context,
    private val group: EntryMergeGroupHost,
    private val anchorId: () -> Long,
    private val mergeManager: MergeManager,
    private val dismissDialog: () -> Unit,
    private val setFavorite: suspend (ids: List<Long>, favorite: Boolean) -> Unit,
) {

    /** Persist a manage-sources drag as the group's source order, then re-aggregate live (a fresh array
     *  re-emits the flow so the new trunk leads the list). */
    fun reorderSources(orderedIds: List<Long>) {
        scope.launchIO {
            mergeManager.setSourceOrder(orderedIds)
            group.refresh(anchorId())
        }
    }

    /** Clear the per-group source-order override (back to the global ranking) and re-aggregate live. */
    fun resetSourceOrder() {
        dismissDialog()
        scope.launchIO {
            mergeManager.clearSourceOrder(anchorId())
            group.refresh(anchorId())
        }
    }

    /**
     * Split [targetIds] out of the merge group, with an Undo that re-merges the prior group. Selecting
     * every source dissolves the whole group (each entry becomes standalone, still in the library). The
     * split sources stay favorited.
     */
    fun splitSources(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        val prevRelated = group.relatedIds
        dismissDialog()
        scope.launchIO {
            // Read the group BEFORE splitting it: splitting a pair deletes the group row, and with it
            // the per-group ranking an undo has to put back. Re-merging can only recover the members.
            val snapshot = mergeManager.captureGroup(anchorId())
            mergeManager.removeFromGroup(prevRelated, targetIds)
            // Re-read rather than publishing what the split returned: it returns the SURVIVORS, which
            // do not include the anchor when the user splits the anchor's own source.
            group.refresh(anchorId())
            if (undoRequested(MR.strings.merge_sources_split)) {
                mergeManager.restoreGroup(snapshot)
                group.refresh(anchorId())
            }
        }
    }

    /** Split [targetIds] out and unfavorite them, with an Undo that re-favorites and re-groups. */
    fun removeSourcesFromLibrary(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        val prevRelated = group.relatedIds
        dismissDialog()
        scope.launchIO {
            // Captured before the split, for the same reason as splitSources.
            val snapshot = mergeManager.captureGroup(anchorId())
            mergeManager.removeFromGroup(prevRelated, targetIds)
            // Unfavorited only AFTER the split, and never in parallel with it: the split hands each
            // member its own copy of the group's shared tracker binding, and that hand-out skips
            // non-favorites, so unfavoriting first left everyone without one. Non-cancellable because
            // a half-done removal is worse than a slow one.
            withContext(NonCancellable) { setFavorite(targetIds, false) }
            // Re-read, for the same reason as splitSources: removing the anchor's own source leaves it
            // ungrouped, and the survivors are the entries the user is NOT looking at.
            group.refresh(anchorId())
            if (undoRequested(MR.strings.merge_sources_removed)) {
                // Undo puts the group back as it was and re-favorites the removed sources.
                mergeManager.restoreGroup(snapshot)
                group.refresh(anchorId())
                scope.launchNonCancellable { setFavorite(targetIds, true) }
            }
        }
    }

    /** Remove the whole merge group from the library at once (Manage Sources "Remove all"). */
    fun removeAllSourcesFromLibrary() = removeSourcesFromLibrary(group.relatedIds.toList())

    private suspend fun undoRequested(message: StringResource): Boolean {
        val result = snackbarHostState.showSnackbar(
            message = context.stringResource(message),
            actionLabel = context.stringResource(MR.strings.action_undo),
            duration = SnackbarDuration.Short,
            withDismissAction = true,
        )
        return result == SnackbarResult.ActionPerformed
    }
}
