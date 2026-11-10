package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.backupCategoryMapper
import kotlinx.coroutines.flow.first
import reikai.domain.library.LibraryManualOrderRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category

@Inject
class CategoriesBackupCreator(
    private val getCategories: GetCategories,
    private val manualOrder: LibraryManualOrderRepository, // RK
) {

    suspend operator fun invoke(): List<BackupCategory> {
        val manualBases = manualOrder.subscribeBases().first() // RK
        return getCategories.await()
            .filterNot(Category::isSystemCategory)
            .map { backupCategoryMapper(it).apply { manualBase = manualBases[it.id] } } // RK
    }
}
