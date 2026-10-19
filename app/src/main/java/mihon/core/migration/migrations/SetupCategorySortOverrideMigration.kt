package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.library.markLegacySortOverride
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * "Per-category sort" gained a CUSTOMIZED override bit; without it a category follows the global sort.
 * Existing per-category manga sorts carry no bit and would read as "follow global" on upgrade, so for a
 * categorized-display user each category whose decoded sort differs from the global is marked as an
 * override. Categorized-display-OFF users already follow the global.
 *
 * Gated at 183f so it fires for everyone upgrading from <=182.
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
        // The migrator stamps its version only once the whole chain resolves, so a kill later in the
        // chain runs this again, and by then a category the user reset to the global sort looks exactly
        // like one this has not marked yet: only a marker tells them apart.
        val done = preferenceStore.getBoolean(Preference.appStateKey("category_sort_override_set_up"), false)
        if (done.get()) return@withIOContext true

        if (libraryPreferences.categorizedDisplaySettings.get()) {
            val global = libraryPreferences.sortingMode.get()
            categoryRepository.getAll().forEach { category ->
                val marked = markLegacySortOverride(category.flags, global)
                if (marked != category.flags) categoryRepository.updateFlags(categoryId = category.id, flags = marked)
            }
        }
        done.set(true)
        return@withIOContext true
    }
}
