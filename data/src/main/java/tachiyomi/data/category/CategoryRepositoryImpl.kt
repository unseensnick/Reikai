package tachiyomi.data.category

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import reikai.domain.category.CategoryContentType
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

class CategoryRepositoryImpl(
    private val database: Database,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return database.categoriesQueries
            .getCategory(id, ::mapCategory)
            .awaitAsOneOrNull()
    }

    // RK: branch on contentType so one repository serves both libraries (novel rows live in the shared
    // table at content_type 2). Manga callers pass the default and hit the unchanged manga query.
    override suspend fun getAll(contentType: Long): List<Category> {
        val query = if (contentType == CategoryContentType.NOVEL) {
            database.categoriesQueries.getNovelCategories(::mapCategory)
        } else {
            database.categoriesQueries.getCategories(::mapCategory)
        }
        return query.awaitAsList()
    }

    override fun getAllAsFlow(contentType: Long): Flow<List<Category>> {
        val query = if (contentType == CategoryContentType.NOVEL) {
            database.categoriesQueries.getNovelCategories(::mapCategory)
        } else {
            database.categoriesQueries.getCategories(::mapCategory)
        }
        return query.subscribeToList()
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return database.categoriesQueries
            .getCategoriesByMangaId(mangaId, ::mapCategory)
            .awaitAsList()
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return database.categoriesQueries
            .getCategoriesByMangaId(mangaId, ::mapCategory)
            .subscribeToList()
    }

    // RK: novel-side per-entry read over the shared table.
    override suspend fun getCategoriesByNovelId(novelId: Long): List<Category> {
        return database.categoriesQueries
            .getNovelCategoriesByNovelId(novelId, ::mapCategory)
            .awaitAsList()
    }

    // RK: contentType selects the novel insert (content_type 2) over the manga default; returns the new
    // row id so the novel create/restore paths can key off it.
    override suspend fun insert(category: Category, contentType: Long): Long {
        return database.transactionWithResult {
            if (contentType == CategoryContentType.NOVEL) {
                database.categoriesQueries.insertNovelCategory(
                    name = category.name,
                    order = category.order,
                    flags = category.flags,
                )
            } else {
                database.categoriesQueries.insert(
                    name = category.name,
                    order = category.order,
                    flags = category.flags,
                )
            }
            database.categoriesQueries.selectLastInsertedRowId().awaitAsOne()
        }
    }

    override suspend fun updatePartial(update: CategoryUpdate) {
        database.categoriesQueries.update(
            name = update.name,
            order = update.order,
            flags = update.flags,
            categoryId = update.id,
        )
    }

    override suspend fun updatePartial(updates: List<CategoryUpdate>) {
        database.transaction {
            updates.forEach { updatePartial(it) }
        }
    }

    override suspend fun updateAllFlags(flags: Long?) {
        database.categoriesQueries.updateAllFlags(flags)
    }

    // RK: clear the per-category sort-override marker on every category (they follow the global sort again).
    override suspend fun clearSortOverrides() {
        database.categoriesQueries.clearSortOverrides()
    }

    override suspend fun delete(categoryId: Long) {
        database.categoriesQueries.delete(categoryId = categoryId)
    }

    private fun mapCategory(
        id: Long,
        name: String,
        order: Long,
        flags: Long,
    ): Category {
        return Category(
            id = id,
            name = name,
            order = order,
            flags = flags,
        )
    }
}
