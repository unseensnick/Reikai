package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.util.Log
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
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
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.source.sourcePreferences
import exh.eh.EHentaiUpdateWorker
import reikai.data.backup.AppPreferenceCarry
import reikai.data.novel.update.NovelUpdateWorker
import reikai.data.recommendation.taste.TrackerLibraryRefreshWorker
import reikai.domain.category.CategoryContentType
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.category.GetNovelCategories
import reikai.domain.category.backupCategoryIdToName
import reikai.domain.category.byNamePreferring
import reikai.domain.category.translateCategoryId
import reikai.novel.source.pluginStorageScope
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category

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

        LibraryUpdateWorker.setupTask(context)
        BackupCreateWorker.setupTask(context)
        // RK --> Reikai's periodic jobs read their restored interval only when set up
        NovelUpdateWorker.setupTask(context)
        TrackerLibraryRefreshWorker.setupTask(context)
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
        val manga = categoryIdTranslation(
            backupCategories.orEmpty().map { it.id to it.name },
            allCategories,
            CategoryContentType.MANGA,
        )
        val novelCategories = if (backupNovelCategories != null) getNovelCategories.await() else emptyList()
        val novel = categoryIdTranslation(
            backupNovelCategories.orEmpty().map { it.id to it.name },
            novelCategories,
            CategoryContentType.NOVEL,
        )
        // A shared set holds ids of both types, which never collide since both lists come from one table.
        // Per id rather than one merged name map, since a manga and a novel category may share a name.
        val shared = CategoryIdTranslation { manga.translate(it) ?: novel.translate(it) }
        val translations by lazy {
            categoryIdPreferences.mangaSets.associate { it.key() to manga } +
                categoryIdPreferences.mangaLists.associate { it.key() to manga } +
                categoryIdPreferences.sharedSets.associate { it.key() to shared } +
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
                            // RK: a category-id list translates like the category sets below
                            val restored = restoreCategoryList(key, value.value, preferenceStore, translations)
                            if (!restored) preferenceStore.getString(key).set(value.value)
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

    // RK: the remapped key list comes from the shared CategoryIdPreferences registry, so it covers both
    // content types' settings and the shared library and recents filters, not just Mihon's update/download
    // prefs. Both category lists are restored before the settings (BackupRestorer).
    private fun restoreCategoriesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        // RK: per key, so each set translates against its own content type's categories
        translations: Map<String, CategoryIdTranslation>,
    ): Boolean {
        val translation = translations[key] ?: return false

        val ids = value.mapNotNullTo(mutableSetOf(), translation::translate) // RK

        if (ids.isNotEmpty()) {
            preferenceStore.getStringSet(key) += ids
        }
        return true
    }

    // RK: Mihon's comma-joined category-id lists (getLongArray), merged into the live list like the sets
    private fun restoreCategoryList(
        key: String,
        value: String,
        preferenceStore: PreferenceStore,
        translations: Map<String, CategoryIdTranslation>,
    ): Boolean {
        val translation = translations[key] ?: return false
        val ids = value.split(",").mapNotNull { translation.translate(it)?.toLong() }
        if (ids.isNotEmpty()) {
            val preference = preferenceStore.getLongArray(key, emptyList())
            preference.set((preference.get() + ids).distinct())
        }
        return true
    }
}

// RK: a backup category id to its local id, for the category-id settings.
private fun interface CategoryIdTranslation {
    fun translate(id: String): String?

    /** A negative default is a sentinel (prompt on favourite) rather than a category, so it is kept. */
    fun defaultCategory(id: Int): Int? = if (id < 0) id else translate(id.toString())?.toInt()
}

// RK: one content type's translation, from the backup's categories to the ones restored here by name.
private fun categoryIdTranslation(
    backupIdsAndNames: List<Pair<Long, String>>,
    localCategories: List<Category>,
    contentType: Long,
): CategoryIdTranslation {
    val backupIdToName = backupCategoryIdToName(backupIdsAndNames)
    val nameToNewId = localCategories.byNamePreferring(contentType).mapValues { it.value.id.toString() }
    return CategoryIdTranslation { translateCategoryId(it, backupIdToName, nameToNewId) }
}
