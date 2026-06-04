package yokai.domain.novel

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelInCategory
import kotlinx.coroutines.flow.Flow
import yokai.domain.novel.models.Novel

/**
 * Parallel of [yokai.domain.manga.MangaRepository] but holding novels in a separate table.
 * Lives in the app module (not the domain module) because the library-view and category-junction
 * methods reference [LibraryNovel] and [NovelInCategory], which are app-side. Mirrors how
 * [yokai.domain.manga.MangaRepository] itself sits in the app module despite the
 * `yokai.domain.manga` package name.
 */
interface NovelRepository {
    suspend fun getAll(): List<Novel>
    suspend fun getById(id: Long): Novel?
    suspend fun getByUrlAndSource(url: String, source: String): Novel?
    suspend fun getFavorites(): List<Novel>
    fun getAllAsFlow(): Flow<List<Novel>>
    fun getByUrlAndSourceAsFlow(url: String, source: String): Flow<Novel?>
    suspend fun insert(novel: Novel): Long?

    /** Get-or-insert by (url, source): return the stored row if one exists, else insert [novel] and
     *  return it. The single funnel that prevents duplicate library rows (mirrors the manga side's
     *  `networkToLocalManga`). Callers must route through this with a FRESH call rather than deciding
     *  insert-vs-update from a cached/Compose value, which is what let duplicates slip in. */
    suspend fun insertOrGet(novel: Novel): Novel?
    suspend fun update(novel: Novel): Boolean
    suspend fun getLibraryNovel(): List<LibraryNovel>
    fun getLibraryNovelAsFlow(): Flow<List<LibraryNovel>>
    suspend fun setCategories(novelId: Long, categoryIds: List<Long>)
    suspend fun setMultipleNovelCategories(novelIds: List<Long>, novelCategories: List<NovelInCategory>)
}
