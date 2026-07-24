package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.category.CategoryActions
import reikai.presentation.category.CategorySelection
import reikai.presentation.library.reikaiSortCategories
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// RK: one model for both content types. The per-type write path is injected as [CategoryActions] so the
// Manga and Novels tabs share this model's multi-select + deferred-delete logic instead of duplicating it.
class CategoryScreenModel(
    private val actions: CategoryActions,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
) : StateScreenModel<CategoryScreenState>(CategoryScreenState.Loading) {

    private val _events: Channel<CategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    // RK --> multi-select + deferred-delete. `selectedIds` drives the action-mode UI; `pendingDeleteIds`
    // is the deferred-delete buffer: rows in it are hidden immediately but only committed to the DB once
    // the undo snackbar resolves without an undo. Both fold into the live category flow so a DB re-emission
    // can't clobber them mid-undo.
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    // RK <--

    init {
        screenModelScope.launch {
            // RK --> show the manage list in the same order as every other category surface.
            // Drag-reorder is only offered in Manual (off) mode; the screen hides the drag handle
            // when sorted A->Z / Z->A, since those override the manual order anyway.
            combine(
                actions.subscribe(),
                reikaiLibraryPreferences.categorySortOrder.changes(),
                selectedIds,
                pendingDeleteIds,
            ) { categories, sortOrder, selected, pending ->
                val visible = categories
                    .filterNot(Category::isSystemCategory)
                    .filterNot { it.id in pending }
                CategoryScreenState.Success(
                    categories = reikaiSortCategories(visible, sortOrder),
                    categorySortOrder = sortOrder,
                    selection = selected.intersect(visible.mapTo(HashSet()) { it.id }),
                )
            }
                .collectLatest { newState ->
                    mutableState.update { current ->
                        newState.copy(dialog = (current as? CategoryScreenState.Success)?.dialog)
                    }
                }
            // RK <--
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launch {
            if (!actions.create(name)) _events.send(CategoryEvent.InternalError)
        }
    }

    // RK: a single row delete defers like the bulk path so it's undoable too; commit is shared.
    fun deleteCategory(categoryId: Long) {
        pendingDeleteIds.update { it + categoryId }
        screenModelScope.launch { _events.send(CategoryEvent.ShowUndoSnackbar(1)) }
    }

    // RK --> multi-select + deferred bulk delete
    fun toggleSelection(categoryId: Long) {
        selectedIds.update { CategorySelection.toggle(it, categoryId) }
    }

    fun selectAll() {
        val ids = (state.value as? CategoryScreenState.Success)?.categories?.map { it.id } ?: return
        selectedIds.update { CategorySelection.selectAll(it, ids) }
    }

    fun invertSelection() {
        val ids = (state.value as? CategoryScreenState.Success)?.categories?.map { it.id } ?: return
        selectedIds.update { CategorySelection.invert(it, ids) }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    /** Hide the selected categories and arm the undo snackbar; the DB delete waits for [commitPendingDelete]. */
    fun deleteSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        pendingDeleteIds.update { it + ids }
        selectedIds.value = emptySet()
        screenModelScope.launch { _events.send(CategoryEvent.ShowUndoSnackbar(ids.size)) }
    }

    /** Undo a pending bulk delete: the rows return and the DB was never touched. */
    fun undoPendingDelete() {
        pendingDeleteIds.value = emptySet()
    }

    /** Commit a pending bulk delete to the DB. Per-id so each delete keeps its reorder + preference cleanup. */
    fun commitPendingDelete() {
        val ids = pendingDeleteIds.value
        if (ids.isEmpty()) return
        screenModelScope.launch {
            // RK: non-cancellable so leaving the screen (tab switch / back) still finishes the delete
            withNonCancellableContext {
                ids.forEach { id ->
                    if (!actions.delete(id)) _events.trySend(CategoryEvent.InternalError)
                }
                pendingDeleteIds.value = emptySet()
            }
        }
    }
    // RK <--

    fun changeOrder(category: Category, newIndex: Int) {
        screenModelScope.launch {
            if (!actions.reorder(category, newIndex)) _events.send(CategoryEvent.InternalError)
        }
    }

    fun renameCategory(category: Category, name: String) {
        screenModelScope.launch {
            if (!actions.rename(category, name)) _events.send(CategoryEvent.InternalError)
        }
    }

    // RK: flip the hidden flag bit so the category drops out of (or returns to) the library
    fun toggleHidden(category: Category) {
        screenModelScope.launch {
            if (!actions.toggleHidden(category)) _events.send(CategoryEvent.InternalError)
        }
    }

    fun showDialog(dialog: CategoryDialog) {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed interface CategoryDialog {
    data object Create : CategoryDialog
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

sealed interface CategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : CategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)

    // RK: a bulk delete was armed; the screen shows the undo snackbar and resolves commit/undo.
    data class ShowUndoSnackbar(val count: Int) : CategoryEvent
}

sealed interface CategoryScreenState {

    @Immutable
    data object Loading : CategoryScreenState

    @Immutable
    data class Success(
        val categories: List<Category>,
        val dialog: CategoryDialog? = null,
        // RK: 0 = manual (drag to reorder); 1/2 = A->Z / Z->A (drag disabled, sorted to match)
        val categorySortOrder: Int = 0,
        // RK: ids selected in multi-select mode; non-empty means the action-mode toolbar is showing
        val selection: Set<Long> = emptySet(),
    ) : CategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()

        // RK: in multi-select mode when at least one category is selected
        val selectionMode: Boolean
            get() = selection.isNotEmpty()
    }
}
