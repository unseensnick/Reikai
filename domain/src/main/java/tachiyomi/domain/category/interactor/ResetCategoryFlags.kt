package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
class ResetCategoryFlags(
    private val categoryRepository: CategoryRepository,
) {

    // RK: turning off "Per-category setting for sort" clears every category's sort override so they all
    // follow the global sort again. (Was updateAllFlags(global), which also wiped the hidden bit.)
    suspend fun await() {
        categoryRepository.clearSortOverrides()
    }
}
