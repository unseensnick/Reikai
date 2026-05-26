package yokai.data.novel

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.novel.NovelCategoryRepository
import yokai.domain.novel.models.NovelCategoryUpdate

class NovelCategoryRepositoryImpl(private val handler: DatabaseHandler) : NovelCategoryRepository {
    override suspend fun getAll(): List<NovelCategory> =
        handler.awaitList { novel_categoriesQueries.findAll(NovelCategory::mapper) }

    override suspend fun getAllByNovelId(novelId: Long): List<NovelCategory> =
        handler.awaitList { novel_categoriesQueries.findAllByNovelId(novelId, NovelCategory::mapper) }

    override fun getAllAsFlow(): Flow<List<NovelCategory>> =
        handler.subscribeToList { novel_categoriesQueries.findAll(NovelCategory::mapper) }

    override suspend fun insert(category: NovelCategory): Long? =
        handler.awaitOneOrNullExecutable {
            novel_categoriesQueries.insert(
                name = category.name,
                novelOrder = category.novelOrderToString(),
                sort = category.order.toLong(),
                flags = category.flags.toLong(),
            )
            novel_categoriesQueries.selectLastInsertedRowId()
        }

    override suspend fun insertBulk(categories: List<NovelCategory>) =
        handler.await(true) {
            categories.forEach { category ->
                novel_categoriesQueries.insert(
                    name = category.name,
                    novelOrder = category.novelOrderToString(),
                    sort = category.order.toLong(),
                    flags = category.flags.toLong(),
                )
            }
        }

    override suspend fun update(update: NovelCategoryUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            Logger.e { "Failed to update novel_category with id '${update.id}'" }
            false
        }
    }

    override suspend fun updateAll(updates: List<NovelCategoryUpdate>): Boolean {
        return try {
            partialUpdate(*updates.toTypedArray())
            true
        } catch (e: Exception) {
            Logger.e(e) { "Failed to bulk update novel_categories" }
            false
        }
    }

    private suspend fun partialUpdate(vararg updates: NovelCategoryUpdate) {
        handler.await(inTransaction = true) {
            updates.forEach { update ->
                novel_categoriesQueries.update(
                    id = update.id,
                    name = update.name,
                    novelOrder = update.novelOrder,
                    sort = update.order,
                    flags = update.flags,
                )
            }
        }
    }

    override suspend fun delete(id: Long) {
        handler.await { novel_categoriesQueries.delete(id) }
    }
}
