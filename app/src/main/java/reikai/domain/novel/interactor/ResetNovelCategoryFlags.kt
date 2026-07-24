package reikai.domain.novel.interactor

import reikai.domain.category.CategoryContentType
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Clears every novel category's per-category sort so they all follow the library default sort again, the
 * novel twin of [tachiyomi.domain.category.interactor.ResetCategoryFlags]. Only the override marker is
 * cleared (so the shared `sortForCategory` falls back to the default); the stored sort bits and non-sort
 * bits such as the hidden-category flag are preserved, matching the manga side.
 */
class ResetNovelCategoryFlags(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await() {
        val updates = categoryRepository.getAll(CategoryContentType.NOVEL).map { category ->
            CategoryUpdate(
                id = category.id,
                flags = category.flags and CATEGORY_SORT_CUSTOMIZED.inv(),
            )
        }
        categoryRepository.updatePartial(updates)
    }
}
