package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class CategoriesRestorer(
    private val getCategories: GetCategories,
    private val categoryRepository: CategoryRepository,
    private val libraryPreferences: LibraryPreferences,
) {

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getCategories.await()
            // RK: grouped, not associateBy: one name can now belong to several rows (a universal
            // category and a manga one can share it), and associateBy silently kept only the last.
            val dbCategoriesByName = dbCategories.groupBy { it.name }
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

            val categories = backupCategories
                .sortedBy { it.order }
                .map { backupCategory ->
                    val sameName = dbCategoriesByName[backupCategory.name].orEmpty()
                    // RK: prefer a row of the same content type, then settle for any row with that
                    // name. The fallback is what keeps a backup made before the content type existed
                    // (every entry reads as manga) matching a category the user has since made
                    // universal, instead of inserting a duplicate next to it.
                    val dbCategory = sameName.firstOrNull { it.contentType == backupCategory.contentType }
                        ?: sameName.firstOrNull()
                    if (dbCategory != null) return@map dbCategory
                    // RK: insert through the repository. It writes the backup's content type, so a
                    // category spanning both libraries no longer lands as manga-only and gets re-created
                    // by the novel list's copy, and it returns the new row id; the raw query returns
                    // rows affected, which every restored Category was using as its id.
                    val category = backupCategory.toCategory(id = 0L).copy(order = nextOrder++)
                    category.copy(id = categoryRepository.insert(category, category.contentType))
                }

            libraryPreferences.categorizedDisplaySettings.set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}
