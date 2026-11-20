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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import reikai.domain.category.categoriesForContentType
import reikai.domain.library.CategorySortOrder
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.within
import reikai.presentation.category.CategoryActions
import reikai.presentation.library.reikaiSortCategories
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionStore
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

// RK: one model over one list spanning both libraries. Rows carry their own content type, so there is no
// longer a per-type model or write path; [CategoryActions] dispatches on the row where it has to.
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CategoryViewModel(
    @AppCoroutineScope private val appScope: CoroutineScope,
    // RK: CategoryActions stands in for the per-type category interactors
    private val actions: CategoryActions,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) : ViewModel() {

    // RK: a SharedFlow rather than a receiveAsFlow Channel, which can only be collected once.
    private val _events = MutableSharedFlow<CategoryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CategoryEvent> = _events.asSharedFlow()

    // RK --> multi-select + deferred-delete. The selection store drives the action-mode UI and keeps the
    // kernel's anchor with it, so a verb cannot clear one and leave the other; `pendingDelete`
    // is the deferred-delete buffer: rows in it are hidden immediately but only committed to the DB once
    // the undo snackbar resolves without an undo. Both fold into the live category flow so a DB re-emission
    // can't clobber them mid-undo.
    private val selectionStore = SelectionStore<Long>()

    // Holds the rows, not just their ids: a delete has to know the category's content type to clean the
    // right side's preferences, and by commit time the row is gone from the live list. Keyed by the delete
    // that armed them: the screen shows one undo snackbar per delete, and each resolves only its own rows.
    private val pendingDelete = MutableStateFlow<Map<Long, PendingDelete>>(emptyMap())
    private var nextDeleteBatch = 0L

    // RK: which library's categories the list is narrowed to. Per visit, not persisted: this is a
    // settings destination rather than somewhere the user lives. Read through the Show setting live,
    // so hiding the chip's type while the screen is open cannot leave the list on it. One live value,
    // first the effective chip and then every type a chip flip can reach, read by the list and the drag.
    private val chipContentType = MutableStateFlow(ContentType.ALL)
    private val contentTypes: StateFlow<Pair<ContentType, ContentType>> =
        combine(chipContentType, reikaiLibraryPreferences.shownContent.changes(), ::contentTypesWithin)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                contentTypesWithin(chipContentType.value, reikaiLibraryPreferences.shownContent.get()),
            )

    private fun contentTypesWithin(chip: ContentType, shown: ContentType) =
        chip.within(shown) to ContentType.ALL.within(shown)

    private val dialog = MutableStateFlow<CategoryDialog?>(null)
    // RK <--

    // RK --> show the manage list in the same order as every other category surface.
    // Drag-reorder is only offered in Manual (off) mode; the screen hides the drag handle
    // when sorted A->Z / Z->A, since those override the manual order anyway.
    private val content = combine(
        actions.subscribe(),
        reikaiLibraryPreferences.categorySortOrder.changes(),
        selectionStore.selection,
        pendingDelete,
        contentTypes,
    ) { categories, sortOrder, selected, pending, (contentType, reachable) ->
        val pendingIds = pending.values.flatMap { it.categories }.mapTo(HashSet()) { it.id }
        val visible = categories
            .filterNot(Category::isSystemCategory)
            .filterNot { it.id in pendingIds }
        val shown = categoriesForContentType(visible, contentType)
        CategoryScreenState.Success(
            categories = reikaiSortCategories(shown, sortOrder),
            // RK: every name a flip of the chip can reach, so it cannot let a duplicate through the
            // create and rename dialogs' check. A name only Show hides stays free.
            allNames = categoriesForContentType(visible, reachable).map { it.name },
            categorySortOrder = sortOrder,
            selection = selected.intersect(shown.mapTo(HashSet()) { it.id }),
            contentType = contentType,
            undoableDeletes = pending
                .filterValues { it.commit == null }
                .map { (batch, delete) -> UndoableDelete(batch, delete.categories.size) },
        )
    }
    // RK <--

    // The dialog is combined in rather than folded into the list flow, so a re-emission from the DB
    // cannot drop an open dialog and the list flow stays a pure derivation.
    val state: StateFlow<CategoryScreenState> = combine(content, dialog) { content, dialog ->
        content.copy(dialog = dialog)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), CategoryScreenState.Loading)

    // RK: the content-type chip, which drops the selection on a flip
    fun setContentType(contentType: ContentType) {
        selectionStore.update { EntrySelection.afterChipFlip(it, chipContentType.value, contentType) }
        chipContentType.value = contentType
    }

    // RK: created in the chosen library, through CategoryActions
    fun createCategory(name: String, contentType: Long) {
        viewModelScope.launch {
            if (!actions.create(name, contentType)) _events.emit(CategoryEvent.InternalError)
        }
    }

    // RK: a single row delete defers like the bulk path so it's undoable too; commit is shared.
    fun deleteCategory(category: Category) {
        armDelete(setOf(category))
    }

    // RK --> multi-select + deferred bulk delete, over the shared selection kernel
    fun toggleSelection(categoryId: Long) {
        selectionStore.update { EntrySelection.toggle(it, categoryId) }
    }

    /** Long press: sweep from the last row touched to this one, or drop it if it is already picked. */
    fun toggleRangeSelection(categoryId: Long) {
        val ids = visibleCategoryIds() ?: return
        selectionStore.update { EntrySelection.rangeOrToggle(it, categoryId, ids) }
    }

    private fun visibleCategoryIds(): List<Long>? =
        (state.value as? CategoryScreenState.Success)?.categories?.map { it.id }

    fun selectAll() {
        val ids = visibleCategoryIds() ?: return
        selectionStore.update { EntrySelection.selectAll(it, ids) }
    }

    fun invertSelection() {
        val ids = visibleCategoryIds() ?: return
        selectionStore.update { EntrySelection.invert(it, ids) }
    }

    fun clearSelection() {
        selectionStore.update { EntrySelection.clear() }
    }

    /** Hide the selected categories and arm the undo snackbar; the DB delete waits for [commitPendingDelete]. */
    fun deleteSelected() {
        val ids = selectionStore.selection.value
        if (ids.isEmpty()) return
        val categories = (state.value as? CategoryScreenState.Success)
            ?.categories
            ?.filter { it.id in ids }
            .orEmpty()
        if (categories.isEmpty()) return
        armDelete(categories.toSet())
        clearSelection()
    }

    // The undo snackbar is drawn from state rather than sent as an event, so a screen recreated by a
    // rotation or fold shows it again for every delete still armed.
    private fun armDelete(categories: Set<Category>) {
        val batch = nextDeleteBatch++
        pendingDelete.update { it + (batch to PendingDelete(categories)) }
    }

    /** Undo one armed delete: its rows return and the DB was never touched. A committing one is past undoing. */
    fun undoPendingDelete(batch: Long) {
        pendingDelete.update { pending -> if (pending[batch]?.commit == null) pending - batch else pending }
    }

    /** Commit one armed delete to the DB. Per-row so each delete keeps its reorder + preference cleanup. */
    fun commitPendingDelete(batch: Long) {
        commitPendingDeletes(setOf(batch))
    }

    // Leaving the screen pops this model, and nothing is left to resolve its snackbars. A configuration
    // change keeps the model, so a rotation commits nothing.
    override fun onCleared() {
        commitPendingDeletes(pendingDelete.value.keys)
    }

    // Straight on the app scope, never through viewModelScope: onCleared runs after that scope is cancelled.
    // A batch keeps its rows hidden until the delete lands, and is claimed once, so it cannot run twice.
    private fun commitPendingDeletes(batches: Set<Long>) {
        val claimed = pendingDelete.value.filter { (batch, delete) -> batch in batches && delete.commit == null }
        if (claimed.isEmpty()) return
        val commit = appScope.launch(start = CoroutineStart.LAZY) {
            claimed.values.flatMap { it.categories }.forEach { category ->
                if (!actions.delete(category)) _events.tryEmit(CategoryEvent.InternalError)
            }
            pendingDelete.update { it - claimed.keys }
        }
        pendingDelete.update { pending -> pending + claimed.mapValues { (_, delete) -> delete.copy(commit = commit) } }
        commit.start()
    }

    private data class PendingDelete(val categories: Set<Category>, val commit: Job? = null)
    // RK <--

    // RK --> a drag's index is a slot in the list the chip shows; the move menu's 0 and past-the-end
    // mean the whole table's ends, which are also the ends of any narrowed list.
    fun changeOrder(category: Category, newIndex: Int) = reorder(category, newIndex, contentTypes.value.first)

    fun moveToEnd(category: Category, newIndex: Int) = reorder(category, newIndex, ContentType.ALL)

    private fun reorder(category: Category, newIndex: Int, contentType: ContentType) {
        viewModelScope.launch {
            // The drag index comes from the visible list, which hides rows pending delete, while the
            // reorder reads the stored table; flush the pending delete first so the two lists agree
            // (dragging while the undo snackbar is up also reads as moving on from the undo, and the flush
            // takes the undo off screen).
            commitPendingDeletes(pendingDelete.value.keys)
            pendingDelete.value.values.mapNotNull { it.commit }.joinAll()
            if (!actions.reorder(category, newIndex, contentType)) _events.emit(CategoryEvent.InternalError)
        }
    }
    // RK <--

    fun renameCategory(category: Category, name: String) {
        viewModelScope.launch {
            // RK: through CategoryActions, which dispatches on the row's content type
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

    // RK: the bulk-delete confirmation, held here so a recreated screen keeps it open
    data object DeleteSelected : CategoryDialog
}

sealed interface CategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : CategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

// RK: an armed delete the screen offers to undo; the screen resolves the batch's commit or undo.
@Immutable
data class UndoableDelete(val batch: Long, val count: Int)

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
        // RK: drag to reorder only under MANUAL; the alphabetical orders override it
        val categorySortOrder: CategorySortOrder = CategorySortOrder.MANUAL,
        // RK: ids selected in multi-select mode; non-empty means the action-mode toolbar is showing
        val selection: Set<Long> = emptySet(),
        // RK: the library the list is narrowed to; All lists every category
        val contentType: ContentType = ContentType.ALL,
        // RK: armed deletes in the order they were made, the first being the one whose undo is up
        val undoableDeletes: List<UndoableDelete> = emptyList(),
    ) : CategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()

        // RK: in multi-select mode when at least one category is selected
        val selectionMode: Boolean
            get() = selection.isNotEmpty()
    }
}
