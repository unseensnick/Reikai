package tachiyomi.domain.category.repository

import kotlinx.coroutines.flow.Flow
import reikai.domain.category.CategoryContentType
import tachiyomi.domain.category.model.Category

interface CategoryRepository {

    suspend fun get(id: Long): Category?

    // RK: contentType filters the shared table by content_type (manga rows + universal by default, or
    // novel rows + universal). Manga callers pass nothing and are unaffected.
    suspend fun getAll(contentType: Long = CategoryContentType.MANGA): List<Category>

    fun getAllAsFlow(contentType: Long = CategoryContentType.MANGA): Flow<List<Category>>

    // RK: every row, unfiltered by content type, for the edit-categories screen and the ordering it owns.
    // The per-library reads above overlap (both include universal rows), so neither can renumber safely.
    suspend fun getUnfiltered(): List<Category>

    fun getUnfilteredAsFlow(): Flow<List<Category>>

    suspend fun getCategoriesByMangaId(mangaId: Long): List<Category>

    fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>>

    // RK: novel-side per-entry read over the shared table (the novel twin of getCategoriesByMangaId).
    suspend fun getCategoriesByNovelId(novelId: Long): List<Category>

    // RK: contentType picks the manga (default) or novel insert; returns the new row id for the novel
    // create/restore paths that need it. Manga callers ignore the returned id.
    suspend fun insert(category: Category, contentType: Long = CategoryContentType.MANGA): Long

    suspend fun updateName(categoryId: Long, name: String)

    suspend fun updateFlags(categoryId: Long, flags: Long)

    // RK: per-category flag writes in one transaction. The manga twin clears every row through
    // updateAllFlags, which the shared table cannot do when only one content type is meant to change.
    suspend fun updateFlags(flagsById: Map<Long, Long>)

    suspend fun updateAllFlags(flags: Long?)

    suspend fun updateAllOrders(orderedIds: List<Long>)

    // RK: clear the per-category sort-override marker on every category (see reikai CATEGORY_SORT_CUSTOMIZED).
    suspend fun clearSortOverrides()

    suspend fun delete(categoryId: Long)
}
