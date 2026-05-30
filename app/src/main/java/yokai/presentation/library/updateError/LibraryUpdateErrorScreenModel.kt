package yokai.presentation.library.updateError

import android.content.Context
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.domain.library.update.error.interactor.DeleteLibraryUpdateErrors
import yokai.domain.library.update.error.interactor.GetLibraryUpdateErrors
import yokai.domain.manga.models.MangaCover

class LibraryUpdateErrorScreenModel :
    StateScreenModel<LibraryUpdateErrorScreenModel.State>(State.Loading) {

    private val getLibraryUpdateErrors: GetLibraryUpdateErrors by injectLazy()
    private val deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors by injectLazy()
    private val sourceManager: SourceManager by injectLazy()

    // Anchor for range-select: the last row toggled by tap/long-press. A subsequent long-press
    // selects every row between the anchor and the target in the flattened (grouped) order.
    private var selectionAnchor: Long? = null

    init {
        screenModelScope.launchIO {
            getLibraryUpdateErrors.subscribeAll().collectLatest { errors ->
                val groups = errors
                    .groupBy { it.message }
                    .map { (message, entries) ->
                        ErrorGroup(
                            message = message,
                            items = entries.map { entry ->
                                ErrorItem(
                                    errorId = entry.errorId,
                                    mangaId = entry.mangaId,
                                    title = entry.mangaTitle,
                                    sourceName = sourceManager.getOrStub(entry.mangaSource).name,
                                    cover = MangaCover(
                                        mangaId = entry.mangaId,
                                        sourceId = entry.mangaSource,
                                        url = entry.thumbnailUrl.orEmpty(),
                                        lastModified = entry.coverLastModified,
                                        inLibrary = true,
                                    ),
                                )
                            },
                        )
                    }
                val validIds = errors.mapTo(mutableSetOf()) { it.errorId }
                mutableState.update { current ->
                    // Drop selections whose error rows were cleared (dismissed here or resolved by
                    // a later successful update) so the contextual bar count stays accurate.
                    val keptSelection = (current as? State.Loaded)?.selected?.intersect(validIds) ?: emptySet()
                    State.Loaded(groups = groups, selected = keptSelection)
                }
            }
        }
    }

    /**
     * Tap (single toggle) or long-press (range). On long-press with an existing anchor, every row
     * between the anchor and [errorId] in the flattened group order is added to the selection;
     * otherwise this toggles the single row. Either way [errorId] becomes the new anchor.
     */
    fun toggleSelection(errorId: Long, fromLongPress: Boolean = false) {
        val current = state.value as? State.Loaded ?: return
        val orderedIds = current.groups.flatMap { group -> group.items.map { it.errorId } }
        val anchor = selectionAnchor

        val newSelected = if (fromLongPress && anchor != null && anchor != errorId) {
            val start = orderedIds.indexOf(anchor)
            val end = orderedIds.indexOf(errorId)
            if (start != -1 && end != -1) {
                current.selected + orderedIds.subList(minOf(start, end), maxOf(start, end) + 1)
            } else {
                singleToggle(current.selected, errorId)
            }
        } else {
            singleToggle(current.selected, errorId)
        }

        selectionAnchor = errorId
        mutableState.update { if (it is State.Loaded) it.copy(selected = newSelected) else it }
    }

    private fun singleToggle(selected: Set<Long>, errorId: Long): Set<Long> =
        if (errorId in selected) selected - errorId else selected + errorId

    /** Select every row, or clear the selection if everything is already selected. */
    fun toggleSelectAll() {
        mutableState.update { current ->
            if (current !is State.Loaded) return@update current
            val allIds = current.groups.flatMapTo(mutableSetOf()) { group -> group.items.map { it.errorId } }
            val newSelected = if (allIds.isNotEmpty() && current.selected.containsAll(allIds)) emptySet() else allIds
            current.copy(selected = newSelected)
        }
    }

    fun clearSelection() {
        selectionAnchor = null
        mutableState.update { current ->
            if (current is State.Loaded) current.copy(selected = emptySet()) else current
        }
    }

    fun dismissSelected() {
        val selected = (state.value as? State.Loaded)?.selected?.toList().orEmpty()
        if (selected.isEmpty()) return
        // The subscription re-emits after the delete and rebuilds groups, so we don't mutate the
        // list here; clearing selection just resets the bar immediately.
        screenModelScope.launchIO { deleteLibraryUpdateErrors.awaitByErrorIds(selected) }
        clearSelection()
    }

    fun dismissAll() {
        val allIds = (state.value as? State.Loaded)
            ?.groups
            ?.flatMap { group -> group.items.map { it.errorId } }
            .orEmpty()
        if (allIds.isEmpty()) return
        screenModelScope.launchIO { deleteLibraryUpdateErrors.awaitByErrorIds(allIds) }
        clearSelection()
    }

    fun retryAll(context: Context) {
        // Re-run the whole library update; the previously-failed manga are included, and each one
        // that now succeeds clears its own error via the job's success hook.
        LibraryUpdateJob.startNow(context)
    }

    @Immutable
    data class ErrorItem(
        val errorId: Long,
        val mangaId: Long,
        val title: String,
        val sourceName: String,
        val cover: MangaCover,
    )

    @Immutable
    data class ErrorGroup(
        val message: String,
        val items: List<ErrorItem>,
    )

    sealed interface State {

        @Immutable
        data object Loading : State

        @Immutable
        data class Loaded(
            val groups: List<ErrorGroup>,
            val selected: Set<Long>,
        ) : State {
            val isEmpty: Boolean get() = groups.isEmpty()
        }
    }
}
