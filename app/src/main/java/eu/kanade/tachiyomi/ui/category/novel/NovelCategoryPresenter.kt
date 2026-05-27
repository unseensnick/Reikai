package eu.kanade.tachiyomi.ui.category.novel

import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.LibrarySort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.interactor.DeleteNovelCategories
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.interactor.InsertNovelCategories
import yokai.domain.novel.interactor.ReorderNovelCategories
import yokai.domain.novel.models.NovelCategoryUpdate
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Presenter of [NovelCategoryController]. Mirrors [eu.kanade.tachiyomi.ui.category.CategoryPresenter]
 * one-for-one with novel-side interactors. The `CREATE_CATEGORY_ORDER` sentinel and "create new"
 * row pattern are identical to the manga side.
 */
class NovelCategoryPresenter(
    private val controller: NovelCategoryController,
) {
    private val deleteNovelCategories: DeleteNovelCategories by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val insertNovelCategories: InsertNovelCategories by injectLazy()
    private val reorderNovelCategories: ReorderNovelCategories by injectLazy()

    private var scope = CoroutineScope(Job() + Dispatchers.Default)

    private var categories: MutableList<NovelCategory> = mutableListOf()

    fun getCategories() {
        if (categories.isNotEmpty()) {
            controller.setCategories(categories.map(::NovelCategoryItem))
        }
        scope.launch(Dispatchers.IO) {
            categories.clear()
            categories.add(newCategory())
            categories.addAll(getNovelCategories.await())
            val catItems = categories.map(::NovelCategoryItem)
            withContext(Dispatchers.Main) {
                controller.setCategories(catItems)
            }
        }
    }

    private fun newCategory(): NovelCategory {
        val default = NovelCategory.create(
            controller.view?.context?.getString(MR.strings.create_new_category) ?: "",
        )
        default.order = CREATE_CATEGORY_ORDER
        default.id = Int.MIN_VALUE
        return default
    }

    fun createCategory(name: String): Boolean {
        // Trim so trailing whitespace doesn't bypass the duplicate check ("foo" vs "foo ").
        // Matches the manga presenter's normalisation; both write entry points (create,
        // rename) trim before duplicate check + persist.
        val trimmed = name.trim()
        if (categoryExists(trimmed, null)) {
            controller.onCategoryExistsError()
            return false
        }

        val cat = NovelCategory.create(trimmed)
        cat.order = (categories.maxOfOrNull { it.order } ?: 0) + 1
        cat.novelSort = LibrarySort.Title.categoryValue
        // FIXME: Don't do blocking
        runBlocking { insertNovelCategories.awaitOne(cat) }
        val cats = runBlocking { getNovelCategories.await() }
        val newCat = cats.find { it.name == trimmed } ?: return false
        categories.add(1, newCat)
        reorderCategories(categories)
        return true
    }

    fun deleteCategory(category: NovelCategory?) {
        val safeCategory = category?.id ?: return
        scope.launch {
            deleteNovelCategories.awaitOne(safeCategory.toLong())
            categories.remove(category)
            withContext(Dispatchers.Main) {
                controller.setCategories(categories.map(::NovelCategoryItem))
            }
        }
    }

    fun deleteCategories(toDelete: List<NovelCategory>) {
        if (toDelete.isEmpty()) return
        scope.launch {
            deleteNovelCategories.awaitAll(toDelete.mapNotNull { it.id?.toLong() })
            categories.removeAll(toDelete.toSet())
            withContext(Dispatchers.Main) {
                controller.setCategories(categories.map(::NovelCategoryItem))
            }
        }
    }

    fun reorderCategories(categories: List<NovelCategory>) {
        scope.launch {
            val updates: MutableList<NovelCategoryUpdate> = mutableListOf()
            categories
                .filter { it.order != CREATE_CATEGORY_ORDER }
                .forEachIndexed { i, category ->
                    category.order = i - 1
                    updates.add(
                        NovelCategoryUpdate(
                            id = category.id!!.toLong(),
                            order = category.order.toLong(),
                        ),
                    )
                }
            reorderNovelCategories.await(updates)
            this@NovelCategoryPresenter.categories = categories.sortedBy { it.order }.toMutableList()
            withContext(Dispatchers.Main) {
                controller.setCategories(this@NovelCategoryPresenter.categories.map(::NovelCategoryItem))
            }
        }
    }

    fun renameCategory(category: NovelCategory, name: String): Boolean {
        val trimmed = name.trim()
        if (categoryExists(trimmed, category.id)) {
            controller.onCategoryExistsError()
            return false
        }
        if (trimmed.isBlank()) {
            return false
        }

        category.name = trimmed
        runBlocking {
            reorderNovelCategories.awaitOne(
                NovelCategoryUpdate(
                    id = category.id!!.toLong(),
                    name = category.name,
                ),
            )
        }
        categories.find { it.id == category.id }?.name = trimmed
        controller.setCategories(categories.map(::NovelCategoryItem))
        return true
    }

    private fun categoryExists(name: String, id: Int?): Boolean {
        // Trim the stored name too so existing rows with trailing whitespace (from before this
        // fix, or from another entry point) still collide with the user's new input.
        return categories.any { it.name.trim().equals(name.trim(), true) && id != it.id }
    }

    companion object {
        const val CREATE_CATEGORY_ORDER = -2
    }
}
