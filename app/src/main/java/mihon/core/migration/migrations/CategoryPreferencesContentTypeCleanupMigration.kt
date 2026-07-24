package mihon.core.migration.migrations

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
 * One-time scrub of stale category ids left in preferences after the category schema unification. Two
 * kinds of staleness accumulated: the fold-in migration remapped the novel update/download prefs but not
 * the novel library-filter or Updates-tab prefs, and a backup restore mints fresh category ids without
 * remapping any category-id pref, so a restored filter/default can point at an id that is now the wrong
 * content type (or gone).
 *
 * Every category-id preference (see [CategoryIdPreferences]) is filtered to the ids that are actually a
 * category of its content type: novel prefs keep only novel/universal ids, manga prefs only manga/universal
 * ids. Single-value default prefs reset to their -1 sentinel when invalid. The universal row 0 is valid for
 * both, so an "uncategorized" selection survives on either side.
 */
class CategoryPreferencesContentTypeCleanupMigration : Migration {
    // RK: fires once when the shipped versionCode crosses 188 (the version this cleanup ships in).
    override val version: Float = 188f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing to scrub
        val categoryRepository = migrationContext.get<CategoryRepository>() ?: return@withIOContext false
        val categoryIdPreferences = migrationContext.get<CategoryIdPreferences>() ?: return@withIOContext false
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return@withIOContext false

        runCatching {
            val mangaIds = categoryRepository.getAll(CategoryContentType.MANGA).mapTo(HashSet()) { it.id.toString() }
            val novelIds = categoryRepository.getAll(CategoryContentType.NOVEL).mapTo(HashSet()) { it.id.toString() }

            resetDefaultIfInvalid(categoryIdPreferences.mangaDefault, mangaIds)
            resetDefaultIfInvalid(categoryIdPreferences.novelDefault, novelIds)
            categoryIdPreferences.mangaSets.forEach { scrubSet(it, mangaIds) }
            categoryIdPreferences.novelSets.forEach { scrubSet(it, novelIds) }

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
