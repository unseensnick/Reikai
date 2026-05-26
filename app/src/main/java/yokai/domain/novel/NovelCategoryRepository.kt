package yokai.domain.novel

import eu.kanade.tachiyomi.data.database.models.NovelCategory
import kotlinx.coroutines.flow.Flow
import yokai.domain.novel.models.NovelCategoryUpdate

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
