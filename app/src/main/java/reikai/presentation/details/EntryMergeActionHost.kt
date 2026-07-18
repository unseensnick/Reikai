package reikai.presentation.details

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.merge.MergeManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.i18n.MR

/**
 * The shared source split / remove / reorder actions for a merged entry's details screen, so the
 * snackbar-with-undo logic that the manga and novel details models both run lives in one place and can't
 * drift. The details model supplies its own [relatedIds] flow, [anchorId] getter, [MergeManager], favorite
 * writer, and selected-source reset; the two per-type differences are the [onBeforeSplit] hook (novels
 * propagate tracker links onto each member before a split) and the [setFavorite] lambda (novels write
 * favorite-only so the merge-undo restores the original dateAdded).
 *
 * [anchorId] is a getter, not a captured value, because the novel model resolves its anchor id after
 * construction. selectSource and showManageSourcesDialog are deliberately not here: those bodies genuinely
 * diverge (novel pagination + selection reset, and different chapter-fetch and ranking signatures).
 */
class EntryMergeActionHost(
    private val scope: CoroutineScope,
    private val snackbarHostState: SnackbarHostState,
    private val context: Context,
    private val relatedIds: MutableStateFlow<LongArray>,
    private val anchorId: () -> Long,
    private val mergeManager: MergeManager,
    private val onClearSelectedSource: () -> Unit,
    private val dismissDialog: () -> Unit,
    private val setFavorite: suspend (ids: List<Long>, favorite: Boolean) -> Unit,
    private val onBeforeSplit: (suspend (ids: List<Long>) -> Unit)? = null,
) {

    /** Persist a manage-sources drag as the group's source order, then re-aggregate live (a fresh array
     *  re-emits the flow so the new trunk leads the list). */
    fun reorderSources(orderedIds: List<Long>) {
        scope.launchIO {
            mergeManager.setSourceOrder(orderedIds)
            relatedIds.value = relatedIds.value.copyOf()
        }
    }

    /** Clear the per-group source-order override (back to the global ranking) and re-aggregate live. */
    fun resetSourceOrder() {
        dismissDialog()
        scope.launchIO {
            mergeManager.clearSourceOrder(anchorId())
            relatedIds.value = relatedIds.value.copyOf()
        }
    }

    /**
     * Split [targetIds] out of the merge group, with an Undo that re-merges the prior group. Selecting
     * every source dissolves the whole group (each entry becomes standalone, still in the library). The
     * split sources stay favorited.
     */
    fun splitSources(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        val prevRelated = relatedIds.value
        onBeforeSplit?.let { hook -> scope.launchIO { hook(prevRelated.toList()) } }
        onClearSelectedSource()
        dismissDialog()
        scope.launchIO {
            val newIds = mergeManager.removeFromGroup(prevRelated, targetIds)
            relatedIds.value = if (newIds.isEmpty()) longArrayOf(anchorId()) else newIds
            if (undoRequested(MR.strings.merge_sources_split)) {
                // Undo re-merges the original group; the split wrote to the group tables, not prefs.
                mergeManager.merge(prevRelated.toList())
                relatedIds.value = prevRelated
            }
        }
    }

    /** Split [targetIds] out and unfavorite them, with an Undo that re-favorites and re-groups. */
    fun removeSourcesFromLibrary(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        val prevRelated = relatedIds.value
        onClearSelectedSource()
        dismissDialog()
        scope.launchNonCancellable { setFavorite(targetIds, false) }
        scope.launchIO {
            relatedIds.value = mergeManager.removeFromGroup(prevRelated, targetIds)
            if (undoRequested(MR.strings.merge_sources_removed)) {
                // Undo re-merges the original group and re-favorites the removed sources.
                mergeManager.merge(prevRelated.toList())
                relatedIds.value = prevRelated
                scope.launchNonCancellable { setFavorite(targetIds, true) }
            }
        }
    }

    /** Remove the whole merge group from the library at once (Manage Sources "Remove all"). */
    fun removeAllSourcesFromLibrary() = removeSourcesFromLibrary(relatedIds.value.toList())

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
