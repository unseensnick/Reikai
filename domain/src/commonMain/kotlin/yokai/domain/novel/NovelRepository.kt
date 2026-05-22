package yokai.domain.novel

import kotlinx.coroutines.flow.Flow
import yokai.domain.novel.models.Novel

/**
 * Parallel of [yokai.domain.manga.MangaRepository] but holding novels in a separate table.
 * Slice E1 covers the core CRUD surface; library-view / track-join / history-recents queries
 * are deferred until the library UI consumes them.
 */
interface NovelRepository {
    suspend fun getAll(): List<Novel>
    suspend fun getById(id: Long): Novel?
    suspend fun getByUrlAndSource(url: String, source: String): Novel?
    suspend fun getFavorites(): List<Novel>
    fun getAllAsFlow(): Flow<List<Novel>>
    fun getByUrlAndSourceAsFlow(url: String, source: String): Flow<Novel?>
    suspend fun insert(novel: Novel): Long?
    suspend fun update(novel: Novel): Boolean
}
