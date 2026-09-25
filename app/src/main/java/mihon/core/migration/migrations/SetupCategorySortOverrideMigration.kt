package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * "Per-category sort" gained a CUSTOMIZED override bit (a category keeps its own sort only when the
 * bit is set, else it follows the global sort). Existing per-category manga sorts are concrete flags
 * with no bit, so without this they'd read as "follow global" and be lost on upgrade. Mark the ones a
 * categorized-display user had explicitly sorted (their decoded sort differs from the global) as
 * overrides so they survive. Categorized-display-OFF users already follow the global, so nothing to do.
 *
 * Gated at 183f (versionCode 183) so it fires for everyone upgrading from <=182.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SetupCategorySortOverrideMigration(
    private val libraryPreferences: LibraryPreferences,
    private val categoryRepository: CategoryRepository,
    private val preferenceStore: PreferenceStore,
) : Migration {
    override val version: Float = 183f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext false
        // The migrator stamps its version only once the whole chain resolves, so a kill later in the
        // chain runs this again, and by then a category the user reset to the global sort looks exactly
        // like one this has not marked yet: only a marker tells them apart.
        val done = preferenceStore.getBoolean(Preference.appStateKey("category_sort_override_set_up"), false)
        if (done.get()) return@withIOContext true

        if (libraryPreferences.categorizedDisplaySettings.get()) {
            val global = libraryPreferences.sortingMode.get()
            categoryRepository.getAll()
                .filter {
                    LibrarySort.valueOf(it.flags) != global && (it.flags and CATEGORY_SORT_CUSTOMIZED) == 0L
                }
                .forEach { category ->
                    categoryRepository.updateFlags(
                        categoryId = category.id,
                        flags = category.flags or CATEGORY_SORT_CUSTOMIZED,
                    )
                }
        }
        done.set(true)
        return@withIOContext true
    }
}
