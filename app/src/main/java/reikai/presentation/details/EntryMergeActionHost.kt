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
 * snackbar-with-undo logic that the manga and novel details models both run lives in one place and can't
 * drift. The details model supplies its own [group] host, [anchorId] getter, [MergeManager] and favorite
 * writer; the one per-type difference left is the [setFavorite] lambda (novels write favorite-only so the
 * merge-undo restores the original dateAdded). Handing each member its own tracker copy is not here:
 * [MergeManager] does it on every path that breaks a group up.
 *
 * [anchorId] is a getter, not a captured value, because the novel model resolves its anchor id after
 * construction. selectSource and showManageSourcesDialog are deliberately not here: those bodies genuinely
 * diverge (novel pagination + selection reset, and different chapter-fetch and ranking signatures).
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
            group.setRelated(group.relatedIds.copyOf())
        }
    }

    /** Clear the per-group source-order override (back to the global ranking) and re-aggregate live. */
    fun resetSourceOrder() {
        dismissDialog()
        scope.launchIO {
            mergeManager.clearSourceOrder(anchorId())
            group.setRelated(group.relatedIds.copyOf())
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
            val newIds = mergeManager.removeFromGroup(prevRelated, targetIds)
            group.setRelated(if (newIds.isEmpty()) longArrayOf(anchorId()) else newIds)
            if (undoRequested(MR.strings.merge_sources_split)) {
                mergeManager.restoreGroup(snapshot)
                group.setRelated(snapshot.orderedMemberIds.toLongArray())
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
            val newIds = mergeManager.removeFromGroup(prevRelated, targetIds)
            // Unfavorited only AFTER the split, and never in parallel with it: the split hands each
            // member its own copy of the group's shared tracker binding, and that hand-out skips
            // non-favorites, so unfavoriting first left everyone without one. Non-cancellable because
            // a half-done removal is worse than a slow one.
            withContext(NonCancellable) { setFavorite(targetIds, false) }
            // Removing every source empties the group; the anchor still stands alone in the library,
            // and reporting an empty array contradicts "this entry plus its grouped siblings".
            group.setRelated(if (newIds.isEmpty()) longArrayOf(anchorId()) else newIds)
            if (undoRequested(MR.strings.merge_sources_removed)) {
                // Undo puts the group back as it was and re-favorites the removed sources.
                mergeManager.restoreGroup(snapshot)
                group.setRelated(snapshot.orderedMemberIds.toLongArray())
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
