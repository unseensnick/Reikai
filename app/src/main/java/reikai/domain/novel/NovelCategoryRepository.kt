package reikai.domain.novel

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelCategoryUpdate

interface NovelCategoryRepository {
    suspend fun getAll(): List<NovelCategory>
    suspend fun getAllByNovelId(novelId: Long): List<NovelCategory>
    fun getAllAsFlow(): Flow<List<NovelCategory>>
    suspend fun insert(category: NovelCategory): Long?
    suspend fun insertBulk(categories: List<NovelCategory>)
    suspend fun update(update: NovelCategoryUpdate): Boolean
    suspend fun updateAll(updates: List<NovelCategoryUpdate>): Boolean
    suspend fun delete(id: Long)
}
