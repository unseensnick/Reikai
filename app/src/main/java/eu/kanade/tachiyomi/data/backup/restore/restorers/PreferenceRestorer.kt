package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.util.Log
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
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
import exh.eh.EHentaiUpdateWorker
import reikai.data.backup.AppPreferenceCarry
import reikai.data.novel.update.NovelUpdateJob
import reikai.data.recommendation.taste.TrackerLibraryRefreshJob
import reikai.domain.category.CategoryContentType
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.category.GetNovelCategories
import reikai.domain.category.backupCategoryIdToName
import reikai.domain.category.byNamePreferring
import reikai.domain.category.translateCategoryId
import reikai.domain.category.translateCategoryIds
import reikai.novel.source.pluginStorageScope
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.interactor.GetCategories

@Inject
class PreferenceRestorer(
    private val context: Context,
    private val getCategories: GetCategories,
    private val preferenceStore: PreferenceStore,
    // RK: the registry of category-id settings, which a restore translates by category name
    private val categoryIdPreferences: CategoryIdPreferences,
    // RK: the novel category-id settings translate here too, by the same rule as manga's.
    private val getNovelCategories: GetNovelCategories,
    // RK: Reikai's carries and skips for retired and untrusted app keys
    private val appPreferenceCarry: AppPreferenceCarry,
) {
    suspend fun restoreApp(
        preferences: List<BackupPreference>,
        backupCategories: List<BackupCategory>?,
        // RK: restored before the settings, like the manga categories
        backupNovelCategories: List<BackupNovelCategory>? = null,
    ) {
        // RK --> on the App path only, so a Source settings entry never reaches an app-wide setting
        appPreferenceCarry.restore(preferences) { toWrite ->
            restorePreferences(
                toWrite,
                preferenceStore,
                backupCategories,
                backupNovelCategories,
            )
        }
        // RK <--

        LibraryUpdateJob.setupTask(context)
        BackupCreateJob.setupTask(context)
        // RK --> Reikai's periodic jobs read their restored interval only when set up
        NovelUpdateJob.setupTask(context)
        TrackerLibraryRefreshJob.setupTask(context)
        EHentaiUpdateWorker.setupTask(context)
        // RK <--
    }

    suspend fun restoreSource(preferences: List<BackupSourcePreferences>) {
        preferences.forEach {
            // RK --> a plugin's settings go back to the app store, and only the keys in its own scope,
            // so a backup cannot reach any other app setting through Source settings.
            if (pluginStorageScope(it.sourceKey) == it.sourceKey) {
                restorePreferences(
                    it.prefs.filter { pref ->
                        pluginStorageScope(pref.key) == it.sourceKey
                    },
                    preferenceStore,
                )
                return@forEach
            }
            // RK <--
            val sourcePrefs = AndroidPreferenceStore(sourcePreferences(it.sourceKey))
            restorePreferences(it.prefs, sourcePrefs)
        }
    }

    private suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        backupCategories: List<BackupCategory>? = null,
        backupNovelCategories: List<BackupNovelCategory>? = null, // RK
    ) {
        val allCategories = if (backupCategories != null) getCategories.await() else emptyList()
        // RK -->
        // Through the shared translation, which keeps the Default category and trusts no id a backup
        // repeats (Yōkai writes none, so all of its categories decode as 0). Only keys the backup
        // carries are translated, so a setting it left out keeps its live on-device value.
        val manga = CategoryIdTranslation(
            backupIdToName = backupCategoryIdToName(backupCategories.orEmpty().map { it.id to it.name }),
            nameToNewId = allCategories.byNamePreferring(CategoryContentType.MANGA).mapValues {
                it.value.id.toString()
            },
        )
        val novelCategories = if (backupNovelCategories != null) getNovelCategories.await() else emptyList()
        val novel = CategoryIdTranslation(
            backupIdToName = backupCategoryIdToName(backupNovelCategories.orEmpty().map { it.id to it.name }),
            nameToNewId = novelCategories.byNamePreferring(CategoryContentType.NOVEL).mapValues {
                it.value.id.toString()
            },
        )
        val translations by lazy {
            (categoryIdPreferences.mangaSets + categoryIdPreferences.sharedSets).associate { it.key() to manga } +
                categoryIdPreferences.novelSets.associate { it.key() to novel } +
                mapOf(
                    categoryIdPreferences.mangaDefault.key() to manga,
                    categoryIdPreferences.novelDefault.key() to novel,
                )
        }
        // RK <--
        val prefs = preferenceStore.getAll()
        toRestore.forEach { (key, value) ->
            try {
                when (value) {
                    is IntPreferenceValue -> {
                        if (prefs[key] is Int?) {
                            // RK: the default category of either content type; was a lookup by id, which a
                            // Yōkai backup sent to its last category
                            val translation = translations[key]
                            val newValue = if (translation !=
                                null
                            ) {
                                translation.defaultCategory(value.value)
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
                                translations, // RK
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
        // RK: per key, so the novel sets translate against novel categories; the shared sets go through
        // the manga pass, so a backup's novel ids in them drop (see CategoryIdPreferences).
        translations: Map<String, CategoryIdTranslation>,
    ): Boolean {
        val translation = translations[key] ?: return false

        val ids = translateCategoryIds(
            ids = value,
            backupIdToName = translation.backupIdToName,
            nameToNewId = translation.nameToNewId,
        )

        if (ids.isNotEmpty()) {
            preferenceStore.getStringSet(key) += ids
        }
        return true
    }
}

// RK: one content type's backup-id to local-id translation for the category-id settings.
private class CategoryIdTranslation(
    val backupIdToName: Map<String, String>,
    val nameToNewId: Map<String, String>,
) {
    /** A negative default is a sentinel (prompt on favourite) rather than a category, so it is kept. */
    fun defaultCategory(id: Int): Int? = if (id < 0) {
        id
    } else {
        translateCategoryId(id.toString(), backupIdToName, nameToNewId)?.toInt()
    }
}
