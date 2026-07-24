package reikai.presentation.library.novels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.ui.category.CategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryEvent
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import reikai.domain.category.flagsWithHidden
import reikai.domain.category.isHidden
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.interactor.DeleteNovelCategories
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.InsertNovelCategories
import reikai.domain.novel.interactor.ReorderNovelCategories
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelCategoryUpdate
import reikai.domain.novel.model.toCategory
import reikai.presentation.category.CategorySelection
import reikai.presentation.library.reikaiSortCategories
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The Novels side of the category manager. Mirrors [eu.kanade.tachiyomi.ui.category.CategoryScreenModel]
 * but over the novel category interactors, and reuses Mihon's [CategoryScreenState] / [CategoryDialog]
 * so the same presentation screen + create/rename/delete dialogs render for both content types behind
 * the Manga/Novels chip. The synthesized Default category (id 0) is filtered out, like the manga side.
 */
class NovelCategoryScreenModel(
    private val getNovelCategories: GetNovelCategories = Injekt.get(),
    private val insertNovelCategories: InsertNovelCategories = Injekt.get(),
    private val deleteNovelCategories: DeleteNovelCategories = Injekt.get(),
    private val reorderNovelCategories: ReorderNovelCategories = Injekt.get(),
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
) : StateScreenModel<CategoryScreenState>(CategoryScreenState.Loading) {

    private val _events: Channel<CategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    // Multi-select + deferred-delete, mirroring the manga CategoryScreenModel: see its doc for the
    // selectedIds / pendingDeleteIds split and why both fold into the live category flow.
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingDeleteIds = MutableStateFlow<Set<Long>>(emptySet())

    init {
        screenModelScope.launch {
            combine(
                getNovelCategories.subscribe(),
                reikaiLibraryPreferences.categorySortOrder.changes(),
                selectedIds,
                pendingDeleteIds,
            ) { categories, sortOrder, selected, pending ->
                val visible = categories
                    .filterNot { it.isSystemCategory }
                    .map { it.toCategory() }
                    .filterNot { it.id in pending }
                CategoryScreenState.Success(
                    categories = reikaiSortCategories(visible, sortOrder),
                    categorySortOrder = sortOrder,
                    selection = selected.intersect(visible.mapTo(HashSet()) { it.id }),
                )
            }.collectLatest { newState ->
                mutableState.update { current ->
                    newState.copy(dialog = (current as? CategoryScreenState.Success)?.dialog)
                }
            }
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launch {
            val order = (state.value as? CategoryScreenState.Success)?.categories?.size?.toLong() ?: 0L
            insertNovelCategories.awaitOne(
                NovelCategory(id = 0L, name = name, order = order, flags = 0L),
            )
        }
    }

    // A single row delete defers like the bulk path so it's undoable too; commit is shared.
    fun deleteCategory(categoryId: Long) {
        pendingDeleteIds.update { it + categoryId }
        screenModelScope.launch { _events.send(CategoryEvent.ShowUndoSnackbar(1)) }
    }

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

    /** Commit a pending bulk delete to the DB. */
    fun commitPendingDelete() {
        val ids = pendingDeleteIds.value
        if (ids.isEmpty()) return
        screenModelScope.launch {
            // Non-cancellable so leaving the screen (tab switch / back) still finishes the delete.
            withNonCancellableContext {
                deleteNovelCategories.awaitAll(ids.toList())
                pendingDeleteIds.value = emptySet()
            }
        }
    }

    fun renameCategory(category: Category, name: String) {
        screenModelScope.launch {
            novelCategoryRepository.update(NovelCategoryUpdate(id = category.id, name = name))
        }
    }

    fun changeOrder(category: Category, newIndex: Int) {
        screenModelScope.launch {
            val current = (state.value as? CategoryScreenState.Success)?.categories?.toMutableList() ?: return@launch
            val from = current.indexOfFirst { it.id == category.id }
            if (from < 0) return@launch
            val item = current.removeAt(from)
            current.add(newIndex.coerceIn(0, current.size), item)
            reorderNovelCategories.await(
                current.mapIndexed { index, cat -> NovelCategoryUpdate(id = cat.id, order = index.toLong()) },
            )
        }
    }

    fun toggleHidden(category: Category) {
        screenModelScope.launch {
            novelCategoryRepository.update(
                NovelCategoryUpdate(id = category.id, flags = category.flagsWithHidden(!category.isHidden)),
            )
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
