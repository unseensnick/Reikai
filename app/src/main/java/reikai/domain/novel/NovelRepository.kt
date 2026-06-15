package reikai.domain.novel

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.novel.model.NovelWithChapterCount

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

    /**
     * Favorited novels whose title contains [title] (case-insensitive), excluding novel [id], each
     * with its chapter count. Backs the browse "possible duplicates" dialog. Runs DB-side over the
     * favorite partial index (mirrors the manga duplicate check) so it scales to large libraries.
     */
    suspend fun getDuplicateLibraryNovel(id: Long, title: String): List<NovelWithChapterCount>

    /** Reactive library read: favorited novels with chapter/unread/download counts + categories. */
    fun getLibraryNovelAsFlow(): Flow<List<LibraryNovel>>

    /**
     * Reactive recent-updates feed: chapters of favorited novels fetched after the novel was added
     * ([date_fetch] > [date_added]), newest first. [after] is a lower bound on the chapter upload
     * date (the feed cutoff); [limit] caps the row count. Backs the novel side of the Updates tab.
     */
    fun getRecentNovelUpdatesAsFlow(after: Long, limit: Long): Flow<List<NovelUpdateWithRelations>>
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
