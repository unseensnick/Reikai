package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import reikai.domain.category.categoriesForContentType
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.category.CategoryActions
import reikai.presentation.library.reikaiSortCategories
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

// RK: one model over one list spanning both libraries. Rows carry their own content type, so there is no
// longer a per-type model or write path; [CategoryActions] dispatches on the row where it has to.
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CategoryViewModel(
    private val actions: CategoryActions,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) : ViewModel() {

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

    // RK: which library's categories the list is narrowed to. Per visit, not persisted: this is a
    // settings destination rather than somewhere the user lives.
    private val chipContentType = MutableStateFlow(ContentType.ALL)

    private val dialog = MutableStateFlow<CategoryDialog?>(null)
    // RK <--

    // RK --> show the manage list in the same order as every other category surface.
    // Drag-reorder is only offered in Manual (off) mode; the screen hides the drag handle
    // when sorted A->Z / Z->A, since those override the manual order anyway.
    private val content = combine(
        actions.subscribe(),
        reikaiLibraryPreferences.categorySortOrder.changes(),
        selectedIds,
        pendingDelete,
        chipContentType,
    ) { categories, sortOrder, selected, pending, contentType ->
        val pendingIds = pending.mapTo(HashSet()) { it.id }
        val visible = categories
            .filterNot(Category::isSystemCategory)
            .filterNot { it.id in pendingIds }
        val shown = categoriesForContentType(visible, contentType)
        CategoryScreenState.Success(
            categories = reikaiSortCategories(shown, sortOrder),
            // RK: every name, not just the shown ones, so the chip cannot let a duplicate
            // name through the create and rename dialogs' check.
            allNames = visible.map { it.name },
            categorySortOrder = sortOrder,
            selection = selected.intersect(shown.mapTo(HashSet()) { it.id }),
            contentType = contentType,
        )
    }
    // RK <--

    // The dialog is combined in rather than folded into the list flow, so a re-emission from the DB
    // cannot drop an open dialog and the list flow stays a pure derivation.
    val state: StateFlow<CategoryScreenState> = combine(content, dialog) { content, dialog ->
        content.copy(dialog = dialog)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), CategoryScreenState.Loading)

    fun setContentType(contentType: ContentType) {
        chipContentType.value = contentType
    }

    fun createCategory(name: String, contentType: Long) {
        viewModelScope.launch {
            if (!actions.create(name, contentType)) _events.emit(CategoryEvent.InternalError)
        }
    }

    // RK: a single row delete defers like the bulk path so it's undoable too; commit is shared.
    fun deleteCategory(category: Category) {
        pendingDelete.update { it + category }
        viewModelScope.launch { _events.emit(CategoryEvent.ShowUndoSnackbar(1)) }
    }

    // RK --> multi-select + deferred bulk delete, over the shared selection kernel
    private var categorySelection = SelectionState<Long>()

    fun toggleSelection(categoryId: Long) {
        categorySelection = EntrySelection.toggle(categorySelection, categoryId)
        selectedIds.value = categorySelection.selection
    }

    /** Long press: sweep from the last row touched to this one, or drop it if it is already picked. */
    fun toggleRangeSelection(categoryId: Long) {
        val ids = visibleCategoryIds() ?: return
        categorySelection = EntrySelection.rangeOrToggle(categorySelection, categoryId, ids)
        selectedIds.value = categorySelection.selection
    }

    private fun visibleCategoryIds(): List<Long>? =
        (state.value as? CategoryScreenState.Success)?.categories?.map { it.id }

    fun selectAll() {
        val ids = (state.value as? CategoryScreenState.Success)?.categories?.map { it.id } ?: return
        categorySelection = EntrySelection.selectAll(categorySelection, ids)
        selectedIds.value = categorySelection.selection
    }

    fun invertSelection() {
        val ids = (state.value as? CategoryScreenState.Success)?.categories?.map { it.id } ?: return
        categorySelection = EntrySelection.invert(categorySelection, ids)
        selectedIds.value = categorySelection.selection
    }

    fun clearSelection() {
        categorySelection = EntrySelection.clear()
        selectedIds.value = categorySelection.selection
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
        viewModelScope.launch { _events.emit(CategoryEvent.ShowUndoSnackbar(categories.size)) }
    }

    /** Undo a pending bulk delete: the rows return and the DB was never touched. */
    fun undoPendingDelete() {
        pendingDelete.value = emptySet()
    }

    /** Commit a pending bulk delete to the DB. Per-row so each delete keeps its reorder + preference cleanup. */
    fun commitPendingDelete() {
        if (pendingDelete.value.isEmpty()) return
        viewModelScope.launch { commitPendingDeleteNow() }
    }

    private suspend fun commitPendingDeleteNow() {
        val categories = pendingDelete.value
        if (categories.isEmpty()) return
        // RK: non-cancellable so leaving the screen still finishes the delete
        withNonCancellableContext {
            categories.forEach { category ->
                if (!actions.delete(category)) _events.tryEmit(CategoryEvent.InternalError)
            }
            pendingDelete.value = emptySet()
        }
    }
    // RK <--

    fun changeOrder(category: Category, newIndex: Int) {
        viewModelScope.launch {
            // The drag index comes from the visible list, which hides rows pending delete, while
            // the reorder renumbers the full table; flush the pending delete first so the two lists
            // agree (dragging while the undo snackbar is up also reads as moving on from the undo).
            commitPendingDeleteNow()
            if (!actions.reorder(category, newIndex)) _events.emit(CategoryEvent.InternalError)
        }
    }

    fun renameCategory(category: Category, name: String) {
        viewModelScope.launch {
            if (!actions.rename(category, name)) _events.emit(CategoryEvent.InternalError)
        }
    }

    // RK: flip the hidden flag bit so the category drops out of (or returns to) the library
    fun toggleHidden(category: Category) {
        viewModelScope.launch {
            if (!actions.toggleHidden(category)) _events.emit(CategoryEvent.InternalError)
        }
    }

    fun showDialog(dialog: CategoryDialog) {
        this.dialog.update { dialog }
    }

    fun dismissDialog() {
        dialog.update { null }
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
        // RK: every category's name, including the ones the chip is hiding; the duplicate-name check
        // is about the table, not about what is on screen.
        val allNames: List<String> = emptyList(),
        // RK: 0 = manual (drag to reorder); 1/2 = A->Z / Z->A (drag disabled, sorted to match)
        val categorySortOrder: Int = 0,
        // RK: ids selected in multi-select mode; non-empty means the action-mode toolbar is showing
        val selection: Set<Long> = emptySet(),
        // RK: the library the list is narrowed to; All lists every category
        val contentType: ContentType = ContentType.ALL,
    ) : CategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()

        // RK: in multi-select mode when at least one category is selected
        val selectionMode: Boolean
            get() = selection.isNotEmpty()
    }
}
