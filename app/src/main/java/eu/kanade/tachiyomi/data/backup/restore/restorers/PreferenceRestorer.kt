package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.util.Log
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
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
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.FLARESOLVERR_URL_KEY
import eu.kanade.tachiyomi.network.interceptor.carryFlareSolverrUserInfo
import eu.kanade.tachiyomi.source.sourcePreferences
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.category.DEAD_LAST_USED_NOVEL_CATEGORY_KEY
import reikai.domain.category.backupCategoryIdToName
import reikai.domain.category.translateCategoryId
import reikai.domain.category.translateCategoryIds
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.DEAD_READER_PADDING_KEY
import reikai.domain.novel.DEAD_READER_TAP_TO_SCROLL_KEY
import reikai.domain.novel.DEAD_READER_TTS_BUTTON_KEYS
import reikai.domain.novel.DEAD_READER_TTS_ENABLED_KEY
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.carryShowNsfwSource
import reikai.novel.content.NovelSnippets
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class PreferenceRestorer(
    private val context: Context,
    private val getCategories: GetCategories,
    private val preferenceStore: PreferenceStore,
    private val categoryIdPreferences: CategoryIdPreferences,
    // RK: for the retired novel-reader padding key, which a restore has to carry over itself.
    private val novelPreferences: NovelPreferences,
    // RK: for upstream's retired extension NSFW switch, carried the same way.
    private val extensionSourcePreferences: SourcePreferences,
    // RK: for a bypass-server address that still carries credentials in it.
    private val networkPreferences: NetworkPreferences,
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
            val sourcePrefs = AndroidPreferenceStore(sourcePreferences(it.sourceKey))
            restorePreferences(it.prefs, sourcePrefs)
        }
    }

    private suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        backupCategories: List<BackupCategory>? = null,
    ) {
        val allCategories = if (backupCategories != null) getCategories.await() else emptyList()
        // RK -->
        // Through the shared translation, which keeps the Default category and trusts no id a backup
        // repeats (Yōkai writes none, so all of its categories decode as 0).
        val nameToNewId = allCategories.associate { it.name to it.id.toString() }
        val backupIdToName = backupCategoryIdToName(backupCategories.orEmpty().map { it.id to it.name })
        // RK <--
        val prefs = preferenceStore.getAll()
        // RK: carried once every key is back, since the bar the switch applies to may restore after it.
        var readAloudWasOn = false
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
            // RK: retired novel global-sort keys (the library-wide sort is LibraryPreferences.sortingMode
            // now); skip so an old backup can't resurrect them. Restoring would be worse than useless: a
            // pre-unification value carries a flag layout nothing can safely decode (see the consts).
            if (key == ReikaiLibraryPreferences.DEAD_NOVEL_SORT_KEY ||
                key == ReikaiLibraryPreferences.DEAD_NOVEL_RANDOM_SEED_KEY
            ) {
                return@forEach
            }
            // RK: retired novel filter keys (every novel_library_filter_* key) plus the novel
            // merge-icons toggle (the filter preferences unified onto the manga keys), the novel
            // group-by key (grouping unified the same way) and the show-empty-categories toggle
            // (empty categories are always hidden now).
            if (key.startsWith(ReikaiLibraryPreferences.DEAD_NOVEL_FILTER_KEY_PREFIX) ||
                key == ReikaiLibraryPreferences.DEAD_NOVEL_MERGE_ICONS_KEY ||
                key == ReikaiLibraryPreferences.DEAD_NOVEL_GROUP_BY_KEY ||
                key == ReikaiLibraryPreferences.DEAD_SHOW_EMPTY_CATEGORIES_KEY
            ) {
                return@forEach
            }
            // RK: retired per-content-type Updates category-filter keys and their master switch; the
            // filter is one selection over the shared id space now, covering the whole recents surface.
            // Likewise the download queue's content-type filter, which the queue order replaced.
            if (key == ReikaiSourcePreferences.DEAD_UPDATES_FILTER_CATEGORIES_KEY ||
                key == ReikaiSourcePreferences.DEAD_DOWNLOAD_CONTENT_TYPE_KEY ||
                key.startsWith(ReikaiSourcePreferences.DEAD_UPDATES_FILTER_CATEGORY_SET_PREFIX) ||
                key.startsWith(ReikaiSourcePreferences.DEAD_UPDATES_FILTER_NOVEL_CATEGORY_SET_PREFIX)
            ) {
                return@forEach
            }
            // RK: the retired novel-reader page padding. The upgrade migration carries it into the side
            // margins and deletes it, but a restore lands it afterwards (a fresh install marks every
            // migration done without running it), where nothing would read it and the user's padding
            // would be silently dropped. Carried here through the same kernel, then not written back.
            if (key == DEAD_READER_PADDING_KEY) {
                (value as? IntPreferenceValue)?.let { novelPreferences.carryReaderPaddingToMargins(it.value) }
                return@forEach
            }
            // RK: JavaScript runs in the chapter page with the reader's bridge beside it, so a backup
            // someone else made must not run code the moment a chapter opens. Restored switched off.
            if (key == NovelPreferences.JS_SNIPPETS_KEY) {
                (value as? StringPreferenceValue)?.let { stored ->
                    val snippets = NovelSnippets.decode(stored.value).map { it.copy(enabled = false) }
                    novelPreferences.readerJsSnippets().set(NovelSnippets.encode(snippets))
                }
                return@forEach
            }
            // RK: the WebView developer tools let any computer with debugging rights inspect the app's
            // WebViews, so a backup never turns them on. Not written, which leaves them off.
            if (key == NovelPreferences.WEBVIEW_DEV_TOOLS_KEY) return@forEach
            // RK: the plugin revalidation flag is armed below and cleared only by a revalidation, so a
            // backup's own value (false once it was taken after one) must never reach the store.
            if (key == NovelPreferences.PLUGINS_NEED_REVALIDATION_KEY) return@forEach
            // RK: upstream's retired extension NSFW switch, carried into the allowed content warnings.
            if (key == ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY) {
                (value as? BooleanPreferenceValue)?.let { extensionSourcePreferences.carryShowNsfwSource(it.value) }
                return@forEach
            }
            // RK: the retired novel tap-to-scroll switch, carried into the tap layout for the same reason.
            if (key == DEAD_READER_TAP_TO_SCROLL_KEY) {
                (value as? BooleanPreferenceValue)?.let { novelPreferences.carryReaderTapToScroll(it.value) }
                return@forEach
            }
            // RK: keys only the retired standalone novel reader wrote; skip so an old backup can't
            // resurrect them after the cleanup migration removed them. The read-aloud switch still owes
            // the bar its button, as the upgrade migration gives it, which a restore lands after.
            if (key == DEAD_READER_TTS_ENABLED_KEY) {
                readAloudWasOn = (value as? BooleanPreferenceValue)?.value == true
                return@forEach
            }
            if (key in DEAD_READER_TTS_BUTTON_KEYS) {
                return@forEach
            }
            // RK: an address that carries user:password@ never authenticated anything, and the key is
            // not private, so it is exactly what an old backup holds in clear text. Cleaned through
            // the same kernel the upgrade migration uses, since a fresh install runs no migrations.
            if (key == FLARESOLVERR_URL_KEY) {
                (value as? StringPreferenceValue)?.let { networkPreferences.carryFlareSolverrUserInfo(it.value) }
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
                                // RK: was a lookup by id, which a Yōkai backup sent to its last category
                                translateCategoryId(value.value.toString(), backupIdToName, nameToNewId)?.toInt()
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
                                backupIdToName,
                                nameToNewId,
                            )
                            if (!restored) preferenceStore.getStringSet(key).set(value.value)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PreferenceRestorer", "Failed to restore preference <$key>", e)
            }
        }
        if (readAloudWasOn) novelPreferences.addReadAloudButtonToCustomisedBar()
    }

    // RK: the remapped key list comes from the shared CategoryIdPreferences registry (manga side), so
    // it also covers the Reikai library and Updates-tab category filters, not just Mihon's update/download
    // prefs. The novel prefs are remapped after the restore, in NovelRestorer, since novel categories are
    // not restored yet at this point.
    private fun restoreCategoriesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        backupIdToName: Map<String, String>,
        nameToNewId: Map<String, String>,
    ): Boolean {
        // RK: the shared sets (the library-wide include/exclude filter) remap here too; a backup's novel
        // ids in them drop, since novel categories are not restored yet (see CategoryIdPreferences).
        val remappedKeys = (categoryIdPreferences.mangaSets + categoryIdPreferences.sharedSets)
            .mapTo(HashSet()) { it.key() }
        if (key !in remappedKeys) return false

        val ids = translateCategoryIds(ids = value, backupIdToName = backupIdToName, nameToNewId = nameToNewId)

        if (ids.isNotEmpty()) {
            preferenceStore.getStringSet(key) += ids
        }
        return true
    }
}
