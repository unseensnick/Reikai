package tachiyomi.domain.category.repository

import kotlinx.coroutines.flow.Flow
import reikai.domain.category.CategoryContentType
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate

interface CategoryRepository {

    suspend fun get(id: Long): Category?

    // RK: contentType filters the shared table by content_type (manga rows + universal by default, or
    // novel rows + universal). Manga callers pass nothing and are unaffected.
    suspend fun getAll(contentType: Long = CategoryContentType.MANGA): List<Category>

    fun getAllAsFlow(contentType: Long = CategoryContentType.MANGA): Flow<List<Category>>

    suspend fun getCategoriesByMangaId(mangaId: Long): List<Category>

    fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>>

    // RK: novel-side per-entry read over the shared table (the novel twin of getCategoriesByMangaId).
    suspend fun getCategoriesByNovelId(novelId: Long): List<Category>

    // RK: contentType picks the manga (default) or novel insert; returns the new row id for the novel
    // create/restore paths that need it. Manga callers ignore the returned id.
    suspend fun insert(category: Category, contentType: Long = CategoryContentType.MANGA): Long?

    suspend fun updatePartial(update: CategoryUpdate)

    suspend fun updatePartial(updates: List<CategoryUpdate>)

    suspend fun updateAllFlags(flags: Long?)

    // RK: clear the per-category sort-override marker on every category (see reikai CATEGORY_SORT_CUSTOMIZED).
    suspend fun clearSortOverrides()

    suspend fun delete(categoryId: Long)
}
