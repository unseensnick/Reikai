package reikai.presentation.category

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import reikai.domain.category.CategoryContentType
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.category.deleteCategoryAndCleanup
import reikai.domain.category.flagsWithHidden
import reikai.domain.category.isHidden
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Category management for the shared edit-categories screen, which is one list spanning both libraries.
 *
 * Rows carry their own content type, so there is no per-type adapter any more: rename and hide are
 * type-agnostic and go through Mihon's interactors unchanged, while create, reorder and delete are owned
 * here because each of them has to reason about the whole table. Mihon's own create/reorder/delete scope
 * themselves to the manga-visible rows, which overlap the novel-visible rows on universal categories, so
 * letting either library renumber alone is what made the two fight over a shared row's order.
 */
@Inject
class CategoryActions(
    private val categoryRepository: CategoryRepository,
    private val categoryIdPreferences: CategoryIdPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val renameCategory: RenameCategory,
) {

    fun subscribe(): Flow<List<Category>> = categoryRepository.getUnfilteredAsFlow()

    suspend fun create(name: String, contentType: Long): Boolean = try {
        val nextOrder = nonSystemCategories().maxOfOrNull { it.order }?.plus(1) ?: 0L
        categoryRepository.insert(
            // Seeded with the global sort like Mihon's CreateCategoryWithName. The bits only take effect
            // once the override marker is set, so this is the same as starting blank until the user sets
            // a per-category sort.
            Category(id = 0L, name = name, order = nextOrder, flags = initialFlags(), contentType = contentType),
            contentType,
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to create category" }
        false
    }

    suspend fun rename(category: Category, newName: String) =
        renameCategory.await(category, newName) !is RenameCategory.Result.InternalError

    suspend fun delete(category: Category): Boolean = try {
        deleteCategoryAndCleanup(
            categoryRepository = categoryRepository,
            categoryId = category.id,
            defaultCategoryPreferences = categoryIdPreferences.defaultsFor(category.contentType),
            categorySetPreferences = categoryIdPreferences.setsFor(category.contentType),
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to delete category id=${category.id}" }
        false
    }

    // Serializes the read-renumber-write like upstream's ReorderCategory: two rapid drags may
    // otherwise interleave and scramble the order column.
    private val reorderMutex = Mutex()

    /** Move a category and renumber the whole list, so one ordering serves both libraries. */
    suspend fun reorder(category: Category, newIndex: Int): Boolean = withNonCancellableContext {
        reorderMutex.withLock {
            val categories = nonSystemCategories().toMutableList()
            val from = categories.indexOfFirst { it.id == category.id }
            if (from < 0) return@withNonCancellableContext true
            val moved = categories.removeAt(from)
            categories.add(newIndex.coerceIn(0, categories.size), moved)
            write { categoryRepository.updateAllOrders(orderedIds = categories.map { it.id }) }
        }
    }

    suspend fun toggleHidden(category: Category) = write {
        categoryRepository.updateFlags(
            categoryId = category.id,
            flags = category.flagsWithHidden(!category.isHidden),
        )
    }

    private suspend fun nonSystemCategories(): List<Category> =
        categoryRepository.getUnfiltered().filterNot(Category::isSystemCategory)

    private fun initialFlags(): Long {
        val sort = libraryPreferences.sortingMode.get()
        return sort.type.flag or sort.direction.flag
    }

    private suspend fun write(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to update categories" }
        false
    }
}

/** The default-category preferences a row of this content type is referenced by. */
private fun CategoryIdPreferences.defaultsFor(contentType: Long) = when (contentType) {
    CategoryContentType.MANGA -> listOf(mangaDefault)
    CategoryContentType.NOVEL -> listOf(novelDefault)
    else -> listOf(mangaDefault, novelDefault)
}

/** The category-id set preferences a row of this content type is referenced by. The shared sets can
 *  hold ids of either type, so every arm scrubs them. */
private fun CategoryIdPreferences.setsFor(contentType: Long) = when (contentType) {
    CategoryContentType.MANGA -> mangaSets + sharedSets
    CategoryContentType.NOVEL -> novelSets + sharedSets
    else -> mangaSets + novelSets + sharedSets
}
