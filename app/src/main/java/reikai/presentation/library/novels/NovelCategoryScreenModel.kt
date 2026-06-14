package reikai.presentation.library.novels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.ui.category.CategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryEvent
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import kotlinx.coroutines.channels.Channel
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
import reikai.presentation.library.reikaiSortCategories
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The Novels side of the category manager (P5 S6 slice 4). Mirrors [eu.kanade.tachiyomi.ui.category.CategoryScreenModel]
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

    init {
        screenModelScope.launch {
            combine(
                getNovelCategories.subscribe(),
                reikaiLibraryPreferences.categorySortOrder.changes(),
            ) { categories, sortOrder ->
                categories.filterNot { it.isSystemCategory }.map { it.toCategory() } to sortOrder
            }.collectLatest { (categories, sortOrder) ->
                mutableState.update {
                    CategoryScreenState.Success(
                        categories = reikaiSortCategories(categories, sortOrder),
                        categorySortOrder = sortOrder,
                    )
                }
            }
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launch {
            val order = (state.value as? CategoryScreenState.Success)?.categories?.size?.toLong() ?: 0L
            insertNovelCategories.awaitOne(
                NovelCategory(id = 0L, name = name, order = order, flags = 0L, novelOrder = ""),
            )
        }
    }

    fun deleteCategory(categoryId: Long) {
        screenModelScope.launch { deleteNovelCategories.awaitOne(categoryId) }
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
