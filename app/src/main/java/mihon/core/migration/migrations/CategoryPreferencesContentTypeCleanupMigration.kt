package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.category.CategoryContentType
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.category.DEAD_LAST_USED_NOVEL_CATEGORY_KEY
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * One-time scrub of stale category ids in preferences after the category schema unification: the fold-in
 * migration missed the novel library-filter and Updates-tab prefs, and a backup restore mints fresh
 * category ids without remapping any category-id pref.
 *
 * Every pref in [CategoryIdPreferences] keeps only its own content type's ids or universal ones, and a
 * single-value default resets to -1. Universal row 0 is valid for both, so "uncategorized" survives.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class CategoryPreferencesContentTypeCleanupMigration(
    private val categoryRepository: CategoryRepository,
    private val categoryIdPreferences: CategoryIdPreferences,
    private val preferenceStore: PreferenceStore,
) : Migration {
    // Fires once when the shipped versionCode crosses 188 (the version this cleanup ships in).
    override val version: Float = 188f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        runCatching {
            val mangaIds = categoryRepository.getAll(CategoryContentType.MANGA).mapTo(HashSet()) { it.id.toString() }
            val novelIds = categoryRepository.getAll(CategoryContentType.NOVEL).mapTo(HashSet()) { it.id.toString() }

            resetDefaultIfInvalid(categoryIdPreferences.mangaDefault, mangaIds)
            resetDefaultIfInvalid(categoryIdPreferences.novelDefault, novelIds)
            categoryIdPreferences.mangaSets.forEach { scrubSet(it, mangaIds) }
            categoryIdPreferences.novelSets.forEach { scrubSet(it, novelIds) }
            // The shared library filter can legitimately hold ids of either type, so it scrubs
            // against the union rather than one side's list.
            categoryIdPreferences.sharedSets.forEach { scrubSet(it, mangaIds + novelIds) }

            preferenceStore.getInt(DEAD_LAST_USED_NOVEL_CATEGORY_KEY, 0).delete()
        }.onFailure { logcat(LogPriority.ERROR, it) { "Category preference cleanup migration failed" } }

        true
    }

    private fun resetDefaultIfInvalid(preference: Preference<Int>, validIds: Set<String>) {
        if (preference.get().toString() !in validIds) preference.delete()
    }

    private fun scrubSet(preference: Preference<Set<String>>, validIds: Set<String>) {
        val ids = preference.get()
        val garbage = ids.minus(validIds)
        if (garbage.isNotEmpty()) preference.set(ids.minus(garbage))
    }
}
