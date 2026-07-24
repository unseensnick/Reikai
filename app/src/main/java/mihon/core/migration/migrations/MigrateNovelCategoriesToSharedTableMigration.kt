package mihon.core.migration.migrations

import app.cash.sqldelight.async.coroutines.awaitAsList
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.library.novelCategoryFlagsToMangaLayout
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database

/**
 * Second half of the category schema unification (companion to migration 33.sqm). The .sqm moved novel
 * categories into the shared `categories` table with a fixed id offset but left two things for here,
 * because neither can be done in the SQL migration: the moved rows still carry the novel sort-flag layout,
 * and the novel category-id preferences still name the pre-move ids.
 *
 * Fixes the moved rows' flags to the manga layout (the two content types stored Downloaded and TrackerMean
 * on swapped bits) using the pinned [novelCategoryFlagsToMangaLayout], and shifts the novel category-id
 * preferences by the same offset the .sqm applied to the ids.
 */
class MigrateNovelCategoriesToSharedTableMigration : Migration {
    // RK: fires once when the shipped versionCode crosses 187 (the version this unification ships in).
    override val version: Float = 187f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing to migrate
        val database = migrationContext.get<Database>() ?: return@withIOContext false
        val novelPreferences = migrationContext.get<NovelPreferences>() ?: return@withIOContext false

        runCatching {
            // 1. Translate the moved novel categories' sort flags from the novel layout to the manga one.
            val novelCategories = database.categoriesQueries
                .getCategoriesByContentType(NOVEL_CONTENT_TYPE) { id, flags -> id to flags }
                .awaitAsList()
            database.transaction {
                novelCategories.forEach { (id, flags) ->
                    val fixed = novelCategoryFlagsToMangaLayout(flags)
                    if (fixed != flags) {
                        database.categoriesQueries.update(name = null, order = null, flags = fixed, categoryId = id)
                    }
                }
            }

            // 2. Shift the novel category-id preferences by the offset the .sqm applied to the ids.
            shiftIntPref(novelPreferences.defaultNovelCategory())
            listOf(
                novelPreferences.removeExcludeCategories(),
                novelPreferences.downloadNewChapterCategories(),
                novelPreferences.downloadNewChapterCategoriesExclude(),
                novelPreferences.novelUpdateCategories(),
                novelPreferences.novelUpdateCategoriesExclude(),
            ).forEach(::shiftStringSetPref)
        }.onFailure { logcat(LogPriority.ERROR, it) { "Novel category fold-in migration failed" } }

        true
    }

    // -1 (prompt) and 0 (universal/uncategorized) stay put; only real moved ids (>= 1) shift.
    private fun shiftIntPref(pref: Preference<Int>) {
        val value = pref.get()
        if (value >= 1) pref.set(value + NOVEL_CATEGORY_ID_MIGRATION_OFFSET.toInt())
    }

    private fun shiftStringSetPref(pref: Preference<Set<String>>) {
        val ids = pref.get()
        if (ids.isEmpty()) return
        pref.set(
            ids.mapTo(mutableSetOf()) { raw ->
                val id = raw.toLongOrNull()
                if (id != null && id >= 1) (id + NOVEL_CATEGORY_ID_MIGRATION_OFFSET).toString() else raw
            },
        )
    }

    companion object {
        // content_type 2 = novel (see categories.sq).
        private const val NOVEL_CONTENT_TYPE = 2L

        // Keep in sync with the offset literal in 33.sqm.
        const val NOVEL_CATEGORY_ID_MIGRATION_OFFSET = 100_000_000L
    }
}
