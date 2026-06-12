package reikai.domain.novel

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.Novel

/**
 * Parallel of [tachiyomi.domain.manga.repository.MangaRepository] holding novels in a separate
 * table. Library-view reads (`getLibraryNovel*`) land with the Novels library stage; S1 is CRUD
 * plus the category junction write.
 */
interface NovelRepository {
    suspend fun getAll(): List<Novel>
    suspend fun getById(id: Long): Novel?
    suspend fun getByUrlAndSource(url: String, source: String): Novel?
    suspend fun getFavorites(): List<Novel>
    fun getAllAsFlow(): Flow<List<Novel>>
    fun getByUrlAndSourceAsFlow(url: String, source: String): Flow<Novel?>
    suspend fun insert(novel: Novel): Long?

    /**
     * Get-or-insert by (url, source): return the stored row if one exists, else insert [novel] and
     * return it. The single funnel that prevents duplicate library rows (mirrors the manga side's
     * `networkToLocalManga`). Callers must route through this with a fresh call rather than deciding
     * insert-vs-update from a cached value.
     */
    suspend fun insertOrGet(novel: Novel): Novel?
    suspend fun update(novel: Novel): Boolean
    suspend fun setCategories(novelId: Long, categoryIds: List<Long>)
}
