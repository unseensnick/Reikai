package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.source.sourcePreferences
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.category.DEAD_LAST_USED_NOVEL_CATEGORY_KEY
import reikai.domain.category.translateCategoryIds
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PreferenceRestorer(
    private val context: Context,
    private val getCategories: GetCategories = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
    private val categoryIdPreferences: CategoryIdPreferences = Injekt.get(),
) {
    suspend fun restoreApp(
        preferences: List<BackupPreference>,
        backupCategories: List<BackupCategory>?,
    ) {
        restorePreferences(
            preferences,
            preferenceStore,
            backupCategories,
        )

        LibraryUpdateJob.setupTask(context)
        BackupCreateJob.setupTask(context)
    }

    suspend fun restoreSource(preferences: List<BackupSourcePreferences>) {
        preferences.forEach {
            val sourcePrefs = AndroidPreferenceStore(context, sourcePreferences(it.sourceKey))
            restorePreferences(it.prefs, sourcePrefs)
        }
    }

    private suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        backupCategories: List<BackupCategory>? = null,
    ) {
        val allCategories = if (backupCategories != null) getCategories.await() else emptyList()
        val categoriesByName = allCategories.associateBy { it.name }
        val backupCategoriesById = backupCategories?.associateBy { it.id.toString() }.orEmpty()
        val prefs = preferenceStore.getAll()
        toRestore.forEach { (key, value) ->
            // RK: the merge prefs store entry IDs, which change on restore. MangaRestorer / NovelRestorer
            // rebuild them from the backup's {url, source} refs, so skip the raw values here to avoid
            // stale IDs (manga + novel).
            if (key == ReikaiLibraryPreferences.MANGA_MANUAL_MERGES_KEY ||
                key == ReikaiLibraryPreferences.MANGA_MANUAL_UNMERGES_KEY ||
                key == ReikaiLibraryPreferences.NOVEL_MANUAL_MERGES_KEY ||
                key == ReikaiLibraryPreferences.NOVEL_MANUAL_UNMERGES_KEY
            ) {
                return@forEach
            }
            // RK: dead Yōkai-era key that nothing reads; skip so an old backup can't resurrect it after the
            // cleanup migration removed it (see DEAD_LAST_USED_NOVEL_CATEGORY_KEY).
            if (key == DEAD_LAST_USED_NOVEL_CATEGORY_KEY) {
                return@forEach
            }
            // RK: a restored ln_installed_plugin_urls set can auto-load arbitrary plugin .js URLs that
            // the QuickJS host evaluates. Flag it so LnPluginInstaller validates the restored URLs
            // against the restored repos before loading any; the value itself is still restored below.
            if (key == NovelPreferences.INSTALLED_PLUGIN_URLS_KEY) {
                preferenceStore.getBoolean(NovelPreferences.PLUGINS_NEED_REVALIDATION_KEY).set(true)
            }
            try {
                when (value) {
                    is IntPreferenceValue -> {
                        if (prefs[key] is Int?) {
                            val newValue = if (key == LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY) {
                                backupCategoriesById[value.value.toString()]
                                    ?.let { categoriesByName[it.name]?.id?.toInt() }
                            } else {
                                value.value
                            }

                            newValue?.let { preferenceStore.getInt(key).set(it) }
                        }
                    }
                    is LongPreferenceValue -> {
                        if (prefs[key] is Long?) {
                            preferenceStore.getLong(key).set(value.value)
                        }
                    }
                    is FloatPreferenceValue -> {
                        if (prefs[key] is Float?) {
                            preferenceStore.getFloat(key).set(value.value)
                        }
                    }
                    is StringPreferenceValue -> {
                        if (prefs[key] is String?) {
                            preferenceStore.getString(key).set(value.value)
                        }
                    }
                    is BooleanPreferenceValue -> {
                        if (prefs[key] is Boolean?) {
                            preferenceStore.getBoolean(key).set(value.value)
                        }
                    }
                    is StringSetPreferenceValue -> {
                        if (prefs[key] is Set<*>?) {
                            val restored = restoreCategoriesPreference(
                                key,
                                value.value,
                                preferenceStore,
                                backupCategoriesById,
                                categoriesByName,
                            )
                            if (!restored) preferenceStore.getStringSet(key).set(value.value)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PreferenceRestorer", "Failed to restore preference <$key>", e)
            }
        }
    }

    // RK: the remapped key list comes from the shared CategoryIdPreferences registry (manga side), so
    // it also covers the Reikai library and Updates-tab category filters, not just Mihon's update/download
    // prefs. The novel prefs are remapped after the restore, in NovelRestorer, since novel categories are
    // not restored yet at this point.
    private fun restoreCategoriesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        backupCategoriesById: Map<String, BackupCategory>,
        categoriesByName: Map<String, Category>,
    ): Boolean {
        if (key !in categoryIdPreferences.mangaSets.mapTo(HashSet()) { it.key() }) return false

        val ids = translateCategoryIds(
            ids = value,
            backupIdToName = backupCategoriesById.mapValues { it.value.name },
            nameToNewId = categoriesByName.mapValues { it.value.id.toString() },
        )

        if (ids.isNotEmpty()) {
            preferenceStore.getStringSet(key) += ids
        }
        return true
    }
}
