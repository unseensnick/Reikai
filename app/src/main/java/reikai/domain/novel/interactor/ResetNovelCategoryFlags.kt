package reikai.domain.novel.interactor

import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.model.NovelCategoryUpdate

/**
 * Clears every novel category's per-category sort so they all follow the library default sort again, the
 * novel twin of [tachiyomi.domain.category.interactor.ResetCategoryFlags]. Only the override marker is
 * cleared (so the shared `sortForCategory` falls back to the default); the stored sort bits and non-sort
 * bits such as the hidden-category flag are preserved, matching the manga side.
 */
class ResetNovelCategoryFlags(
    private val novelCategoryRepository: NovelCategoryRepository,
) {

    suspend fun await() {
        val updates = novelCategoryRepository.getAll().map { category ->
            NovelCategoryUpdate(
                id = category.id,
                flags = category.flags and CATEGORY_SORT_CUSTOMIZED.inv(),
            )
        }
        novelCategoryRepository.updateAll(updates)
    }
}
