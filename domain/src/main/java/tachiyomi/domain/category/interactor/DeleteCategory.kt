package tachiyomi.domain.category.interactor

import logcat.LogPriority
import reikai.domain.category.CategoryContentType
import reikai.domain.category.deleteCategoryAndCleanup
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class DeleteCategory(
    private val categoryRepository: CategoryRepository,
    private val libraryPreferences: LibraryPreferences,
    private val downloadPreferences: DownloadPreferences,
) {

    suspend fun await(categoryId: Long) = withNonCancellableContext {
        try {
            // RK: delete + renumber + preference scrub shared with the novel category delete.
            deleteCategoryAndCleanup(
                categoryRepository = categoryRepository,
                categoryId = categoryId,
                contentType = CategoryContentType.MANGA,
                defaultCategoryPreference = libraryPreferences.defaultCategory,
                categorySetPreferences = listOf(
                    libraryPreferences.updateCategories,
                    libraryPreferences.updateCategoriesExclude,
                    downloadPreferences.removeExcludeCategories,
                    downloadPreferences.downloadNewChapterCategories,
                    downloadPreferences.downloadNewChapterCategoriesExclude,
                ),
            )
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
