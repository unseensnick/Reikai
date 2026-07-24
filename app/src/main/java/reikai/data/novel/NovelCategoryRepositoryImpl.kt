package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelCategoryUpdate
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

/**
 * Novel categories are rows in the shared `categories` table (content_type 2) since the schema
 * unification, so this reads and writes `categoriesQueries` filtered to the novel-visible content types
 * (novel plus universal). The distinct [NovelCategory] type stays only while the novel category stack is
 * collapsed onto the shared one.
 */
class NovelCategoryRepositoryImpl(
    private val database: Database,
) : NovelCategoryRepository {

    override suspend fun getAll(): List<NovelCategory> =
        database.categoriesQueries.getNovelCategories(::mapNovelCategory).awaitAsList()

    override suspend fun getAllByNovelId(novelId: Long): List<NovelCategory> =
        database.categoriesQueries.getNovelCategoriesByNovelId(novelId, ::mapNovelCategory).awaitAsList()

    override fun getAllAsFlow(): Flow<List<NovelCategory>> =
        database.categoriesQueries.getNovelCategories(::mapNovelCategory).subscribeToList()

    override suspend fun insert(category: NovelCategory): Long? = database.transactionWithResult {
        database.categoriesQueries.insertNovelCategory(
            name = category.name,
            order = category.order,
            flags = category.flags,
        )
        database.categoriesQueries.selectLastInsertedRowId().awaitAsOne()
    }

    override suspend fun insertBulk(categories: List<NovelCategory>) {
        database.transaction {
            categories.forEach { category ->
                database.categoriesQueries.insertNovelCategory(
                    name = category.name,
                    order = category.order,
                    flags = category.flags,
                )
            }
        }
    }

    override suspend fun update(update: NovelCategoryUpdate): Boolean = try {
        partialUpdate(update)
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to update novel category id=${update.id}" }
        false
    }

    override suspend fun updateAll(updates: List<NovelCategoryUpdate>): Boolean = try {
        partialUpdate(*updates.toTypedArray())
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to bulk update novel categories" }
        false
    }

    override suspend fun delete(id: Long) {
        database.categoriesQueries.delete(id)
    }

    private suspend fun partialUpdate(vararg updates: NovelCategoryUpdate) {
        database.transaction {
            updates.forEach { update ->
                database.categoriesQueries.update(
                    name = update.name,
                    order = update.order,
                    flags = update.flags,
                    categoryId = update.id,
                )
            }
        }
    }
}
