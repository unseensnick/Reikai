package reikai.domain.novel.interactor

import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.model.NovelCategoryUpdate
import reikai.domain.novel.model.NovelLibrarySort

/**
 * Clears every novel category's per-category sort so they all follow the library default sort again,
 * the novel twin of [tachiyomi.domain.category.interactor.ResetCategoryFlags]. Only the sort bits are
 * cleared (including the CUSTOMIZED sentinel, so `NovelLibrarySort.forCategory` falls back to the
 * default); non-sort bits such as the hidden-category flag are preserved.
 */
class ResetNovelCategoryFlags(
    private val novelCategoryRepository: NovelCategoryRepository,
) {

    suspend fun await() {
        val updates = novelCategoryRepository.getAll().map { category ->
            NovelCategoryUpdate(
                id = category.id,
                flags = category.flags and NovelLibrarySort.FLAGS_MASK.inv(),
            )
        }
        novelCategoryRepository.updateAll(updates)
    }
}
