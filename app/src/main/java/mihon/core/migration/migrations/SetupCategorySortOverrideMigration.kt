package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * RK: "Per-category sort" gained a CUSTOMIZED override bit (a category keeps its own sort only when the
 * bit is set, else it follows the global sort). Existing per-category manga sorts are concrete flags
 * with no bit, so without this they'd read as "follow global" and be lost on upgrade. Mark the ones a
 * categorized-display user had explicitly sorted (their decoded sort differs from the global) as
 * overrides so they survive. Categorized-display-OFF users already follow the global, so nothing to do.
 *
 * Gated at 183f (versionCode 183) so it fires for everyone upgrading from <=182.
 */
class SetupCategorySortOverrideMigration : Migration {
    override val version: Float = 183f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext false
        val libraryPreferences = migrationContext.get<LibraryPreferences>() ?: return@withIOContext false
        if (!libraryPreferences.categorizedDisplaySettings.get()) return@withIOContext true
        val categoryRepository = migrationContext.get<CategoryRepository>() ?: return@withIOContext false

        val global = libraryPreferences.sortingMode.get()
        categoryRepository.getAll()
            .filter {
                LibrarySort.valueOf(it.flags) != global && (it.flags and CATEGORY_SORT_CUSTOMIZED) == 0L
            }
            .forEach { category ->
                categoryRepository.updatePartial(
                    CategoryUpdate(id = category.id, flags = category.flags or CATEGORY_SORT_CUSTOMIZED),
                )
            }
        return@withIOContext true
    }
}
