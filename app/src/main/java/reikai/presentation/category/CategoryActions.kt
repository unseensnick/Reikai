package reikai.presentation.category

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import reikai.domain.category.CategoryContentType
import reikai.domain.category.deleteCategoryAndCleanup
import reikai.domain.category.flagsWithHidden
import reikai.domain.category.isHidden
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.UpdateCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Content-type-neutral category management for the shared edit-categories screen, so one
 * [eu.kanade.tachiyomi.ui.category.CategoryScreenModel] drives both the Manga and Novels tabs instead of
 * two near-identical models. Each content type supplies an adapter: the manga one delegates to Mihon's
 * category interactors, the novel one to the shared category repository filtered to the novel content
 * type. Both return the shared [Category] type so the screen model and its multi-select / deferred-delete
 * logic stay single-sourced.
 */
interface CategoryActions {
    fun subscribe(): Flow<List<Category>>
    suspend fun create(name: String): Boolean
    suspend fun rename(category: Category, newName: String): Boolean
    suspend fun delete(categoryId: Long): Boolean
    suspend fun reorder(category: Category, newIndex: Int): Boolean
    suspend fun toggleHidden(category: Category): Boolean
}

class MangaCategoryActions(
    private val getCategories: GetCategories = Injekt.get(),
    private val createCategoryWithName: CreateCategoryWithName = Injekt.get(),
    private val renameCategory: RenameCategory = Injekt.get(),
    private val deleteCategory: DeleteCategory = Injekt.get(),
    private val reorderCategory: ReorderCategory = Injekt.get(),
    private val updateCategory: UpdateCategory = Injekt.get(),
) : CategoryActions {

    override fun subscribe(): Flow<List<Category>> = getCategories.subscribe()

    override suspend fun create(name: String) =
        createCategoryWithName.await(name) !is CreateCategoryWithName.Result.InternalError

    override suspend fun rename(category: Category, newName: String) =
        renameCategory.await(category, newName) !is RenameCategory.Result.InternalError

    override suspend fun delete(categoryId: Long) =
        deleteCategory.await(categoryId) !is DeleteCategory.Result.InternalError

    override suspend fun reorder(category: Category, newIndex: Int) =
        reorderCategory.await(category, newIndex) !is ReorderCategory.Result.InternalError

    override suspend fun toggleHidden(category: Category) =
        updateCategory.await(
            CategoryUpdate(id = category.id, flags = category.flagsWithHidden(!category.isHidden)),
        ) is UpdateCategory.Result.Success
}

class NovelCategoryActions(
    private val categoryRepository: CategoryRepository = Injekt.get(),
    private val novelPreferences: NovelPreferences = Injekt.get(),
) : CategoryActions {

    override fun subscribe(): Flow<List<Category>> =
        categoryRepository.getAllAsFlow(CategoryContentType.NOVEL)

    override suspend fun create(name: String): Boolean = try {
        val nextOrder = categoryRepository.getAll(CategoryContentType.NOVEL).maxOfOrNull { it.order }?.plus(1) ?: 0L
        categoryRepository.insert(
            Category(id = 0L, name = name, order = nextOrder, flags = 0L),
            CategoryContentType.NOVEL,
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to create novel category" }
        false
    }

    override suspend fun rename(category: Category, newName: String) =
        update(CategoryUpdate(id = category.id, name = newName))

    // Shared delete + renumber + preference scrub, so deleting a novel category also cleans the six novel
    // category-id prefs (default + the update/download include and exclude sets), the same as manga.
    override suspend fun delete(categoryId: Long): Boolean = try {
        deleteCategoryAndCleanup(
            categoryRepository = categoryRepository,
            categoryId = categoryId,
            contentType = CategoryContentType.NOVEL,
            defaultCategoryPreference = novelPreferences.defaultNovelCategory(),
            categorySetPreferences = listOf(
                novelPreferences.removeExcludeCategories(),
                novelPreferences.downloadNewChapterCategories(),
                novelPreferences.downloadNewChapterCategoriesExclude(),
                novelPreferences.novelUpdateCategories(),
                novelPreferences.novelUpdateCategoriesExclude(),
            ),
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to delete novel category id=$categoryId" }
        false
    }

    // Mirrors Mihon's ReorderCategory: move over the non-system list, then renumber every row's order.
    override suspend fun reorder(category: Category, newIndex: Int): Boolean {
        val categories = categoryRepository.getAll(CategoryContentType.NOVEL)
            .filterNot { it.isSystemCategory }
            .toMutableList()
        val from = categories.indexOfFirst { it.id == category.id }
        if (from < 0) return true
        val moved = categories.removeAt(from)
        categories.add(newIndex.coerceIn(0, categories.size), moved)
        return update(
            *categories.mapIndexed { index, cat -> CategoryUpdate(id = cat.id, order = index.toLong()) }
                .toTypedArray(),
        )
    }

    override suspend fun toggleHidden(category: Category) =
        update(CategoryUpdate(id = category.id, flags = category.flagsWithHidden(!category.isHidden)))

    // Apply partial updates over the shared table, reporting success so the screen can surface a failure.
    private suspend fun update(vararg updates: CategoryUpdate): Boolean = try {
        categoryRepository.updatePartial(updates.toList())
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to update novel categories" }
        false
    }
}
