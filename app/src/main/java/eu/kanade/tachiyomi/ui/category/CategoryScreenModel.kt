package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

// RK: one model over one list spanning both libraries. Rows carry their own content type, so there is no
// longer a per-type model or write path; [CategoryActions] dispatches on the row where it has to.
class CategoryScreenModel(
    private val actions: CategoryActions = CategoryActions(),
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
) : StateScreenModel<CategoryScreenState>(CategoryScreenState.Loading) {

    // RK: a SharedFlow rather than a receiveAsFlow Channel, which can only be collected once.
    private val _events = MutableSharedFlow<CategoryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CategoryEvent> = _events.asSharedFlow()

    // RK --> multi-select + deferred-delete. `selectedIds` drives the action-mode UI; `pendingDelete`
    // is the deferred-delete buffer: rows in it are hidden immediately but only committed to the DB once
    // the undo snackbar resolves without an undo. Both fold into the live category flow so a DB re-emission
    // can't clobber them mid-undo.
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    // Holds the rows, not just their ids: a delete has to know the category's content type to clean the
    // right side's preferences, and by commit time the row is gone from the live list.
    private val pendingDelete = MutableStateFlow<Set<Category>>(emptySet())
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
                pendingDelete,
            ) { categories, sortOrder, selected, pending ->
                val pendingIds = pending.mapTo(HashSet()) { it.id }
                val visible = categories
                    .filterNot(Category::isSystemCategory)
                    .filterNot { it.id in pendingIds }
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

    fun createCategory(name: String, contentType: Long) {
        screenModelScope.launch {
            if (!actions.create(name, contentType)) _events.emit(CategoryEvent.InternalError)
        }
    }

    // RK: a single row delete defers like the bulk path so it's undoable too; commit is shared.
    fun deleteCategory(category: Category) {
        pendingDelete.update { it + category }
        screenModelScope.launch { _events.emit(CategoryEvent.ShowUndoSnackbar(1)) }
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
        val categories = (state.value as? CategoryScreenState.Success)
            ?.categories
            ?.filter { it.id in ids }
            .orEmpty()
        if (categories.isEmpty()) return
        pendingDelete.update { it + categories }
        selectedIds.value = emptySet()
        screenModelScope.launch { _events.emit(CategoryEvent.ShowUndoSnackbar(categories.size)) }
    }

    /** Undo a pending bulk delete: the rows return and the DB was never touched. */
    fun undoPendingDelete() {
        pendingDelete.value = emptySet()
    }

    /** Commit a pending bulk delete to the DB. Per-row so each delete keeps its reorder + preference cleanup. */
    fun commitPendingDelete() {
        val categories = pendingDelete.value
        if (categories.isEmpty()) return
        screenModelScope.launch {
            // RK: non-cancellable so leaving the screen still finishes the delete
            withNonCancellableContext {
                categories.forEach { category ->
                    if (!actions.delete(category)) _events.tryEmit(CategoryEvent.InternalError)
                }
                pendingDelete.value = emptySet()
            }
        }
    }
    // RK <--

    fun changeOrder(category: Category, newIndex: Int) {
        screenModelScope.launch {
            if (!actions.reorder(category, newIndex)) _events.emit(CategoryEvent.InternalError)
        }
    }

    fun renameCategory(category: Category, name: String) {
        screenModelScope.launch {
            if (!actions.rename(category, name)) _events.emit(CategoryEvent.InternalError)
        }
    }

    // RK: flip the hidden flag bit so the category drops out of (or returns to) the library
    fun toggleHidden(category: Category) {
        screenModelScope.launch {
            if (!actions.toggleHidden(category)) _events.emit(CategoryEvent.InternalError)
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
