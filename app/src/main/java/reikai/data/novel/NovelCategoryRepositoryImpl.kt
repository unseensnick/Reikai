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

class NovelCategoryRepositoryImpl(
    private val database: Database,
) : NovelCategoryRepository {

    override suspend fun getAll(): List<NovelCategory> =
        database.novel_categoriesQueries.findAll(::mapNovelCategory).awaitAsList()

    override suspend fun getAllByNovelId(novelId: Long): List<NovelCategory> =
        database.novel_categoriesQueries.findAllByNovelId(novelId, ::mapNovelCategory).awaitAsList()

    override fun getAllAsFlow(): Flow<List<NovelCategory>> =
        database.novel_categoriesQueries.findAll(::mapNovelCategory).subscribeToList()

    override suspend fun insert(category: NovelCategory): Long? = database.transactionWithResult {
        database.novel_categoriesQueries.insert(
            name = category.name,
            sort = category.order,
            flags = category.flags,
            novelOrder = category.novelOrder,
        )
        database.novel_categoriesQueries.selectLastInsertedRowId().awaitAsOne()
    }

    override suspend fun insertBulk(categories: List<NovelCategory>) {
        database.transaction {
            categories.forEach { category ->
                database.novel_categoriesQueries.insert(
                    name = category.name,
                    sort = category.order,
                    flags = category.flags,
                    novelOrder = category.novelOrder,
                )
            }
        }
    }

    override suspend fun update(update: NovelCategoryUpdate): Boolean = try {
        partialUpdate(update)
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to update novel_category id=${update.id}" }
        false
    }

    override suspend fun updateAll(updates: List<NovelCategoryUpdate>): Boolean = try {
        partialUpdate(*updates.toTypedArray())
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to bulk update novel_categories" }
        false
    }

    override suspend fun delete(id: Long) {
        database.novel_categoriesQueries.delete(id)
    }

    private suspend fun partialUpdate(vararg updates: NovelCategoryUpdate) {
        database.transaction {
            updates.forEach { update ->
                database.novel_categoriesQueries.update(
                    name = update.name,
                    sort = update.order,
                    flags = update.flags,
                    novelOrder = update.novelOrder,
                    id = update.id,
                )
            }
        }
    }
}
