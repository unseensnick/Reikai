package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import reikai.domain.category.CategoryContentType
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
        if (backupCategories.isEmpty()) return

        val dbCategories = getCategories.await()
        // RK: grouped, not associateBy: one name can belong to several rows (a universal category and a
        // manga one can share it), and associateBy silently kept only the last.
        val dbCategoriesByName = dbCategories.groupBy { it.name }

        val newCategories = backupCategories
            // RK: a row of the same content type matches first, then any row with that name. The
            // fallback keeps a backup made before the content type existed (every entry reads as
            // manga) matching a category the user has since made universal, instead of adding a
            // duplicate beside it. Each new row keeps the backup's content type.
            .filter { dbCategoriesByName[it.name].orEmpty().preferring(it.contentType) == null }
            .sortedBy { it.order }
        // RK --> a category spanning both libraries whose name a novel-only row already holds brings only
        // its manga half: a universal row beside it would list the name twice in the novel library, and
        // the novel restore matches the existing novel row instead.
        val novelOnlyNames = categoryRepository.getAll(CategoryContentType.NOVEL)
            .filter { it.contentType == CategoryContentType.NOVEL }
            .mapTo(HashSet()) { it.name }
        categoryRepository.insertAll(
            newCategories.map { backup ->
                backup.toNewCategory().let {
                    // keeps the hidden bit
                    if (it.contentType == CategoryContentType.UNIVERSAL && it.name in novelOnlyNames) {
                        it.copy(contentType = CategoryContentType.MANGA)
                    } else {
                        it
                    }
                }
            },
        )
        // RK <--

        val flags = buildSet {
            dbCategories.mapTo(this) { it.flags }
            newCategories.mapTo(this) { it.flags }
        }
        libraryPreferences.categorizedDisplaySettings.set(flags.size > 1)
    }
}
