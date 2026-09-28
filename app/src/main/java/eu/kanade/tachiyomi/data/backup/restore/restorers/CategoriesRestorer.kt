package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import reikai.domain.category.preferring
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class CategoriesRestorer(
    private val categoryRepository: CategoryRepository,
    private val getCategories: GetCategories,
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
                // RK: a row of the same content type matches first, then any row with that name. The
                // fallback keeps a backup made before the content type existed (every entry reads as
                // manga) matching a category the user has since made universal, instead of adding a
                // duplicate beside it. Each new row keeps the backup's content type.
                .filter { dbCategoriesByName[it.name].orEmpty().preferring(it.contentType) == null }
                .map { it.toCategory(id = 0).copy(order = nextOrder++) }
            categoryRepository.insertAll(categories)

            libraryPreferences.categorizedDisplaySettings.set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}
