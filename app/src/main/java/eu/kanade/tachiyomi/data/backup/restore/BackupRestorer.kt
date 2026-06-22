package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    // RK -->
    private val novelRestorer: NovelRestorer = NovelRestorer(),
    private val extensionRestorer: ExtensionRestorer = ExtensionRestorer(),
    // RK <--
) {

    private var restoreAmount = 0
    private var restoreProgress = 0
    private val errors = mutableListOf<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val backup = BackupDecoder(context).decode(uri)

        // Store source mapping for error messages
        val backupMaps = backup.backupSources
        sourceMapping = backupMaps.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size
        }
        if (options.categories) {
            restoreAmount += 1
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += backup.backupExtensionStores.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }
        // RK: count restored novels toward progress.
        if (options.libraryEntries) {
            restoreAmount += backup.backupNovels.size
        }

        coroutineScope {
            // RK: categories must finish restoring BEFORE manga + app-settings, both of which map to
            // live categories by name (a manga's category assignments in MangaRestorer.restoreCategories;
            // the default-category pref in PreferenceRestorer). Upstream launches all of these
            // concurrently, so a manga restored before its categories exist loses them and lands in the
            // Default category (a long-standing Tachiyomi-lineage race: a random count slips through each
            // run). Awaiting the categories job first fixes it; everything below stays parallel.
            if (options.categories) {
                restoreCategories(backup.backupCategories).join()
            }
            if (options.appSettings) {
                restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories })
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(backup.backupSourcePreferences)
            }
            if (options.libraryEntries) {
                restoreManga(
                    backup.backupManga,
                    if (options.categories) backup.backupCategories else emptyList(),
                    backup.backupMangaMerges,
                    backup.backupMangaUnmerges,
                )
            }
            if (options.extensionStores) {
                restoreExtensionStores(backup)
            }
            // RK -->
            restoreNovels(backup, options)
            // RK: novel plugins are NOT reinstalled here. Their install state (URLs + metadata) rides
            // the preference backup, so the normal lazy loader re-downloads them on the next novel-
            // screen open. A restore-time reinstall just duplicated that work and stalled on any
            // unreachable repo.
            // RK <--

            // TODO: optionally trigger online library + tracker update
        }
    }

    // RK: restore the light-novel library. Self-contained and sequential (categories first, then each
    // novel, then the merge prefs) so it doesn't race the parallel manga/preference restore jobs.
    private fun CoroutineScope.restoreNovels(backup: Backup, options: RestoreOptions) = launch {
        if (options.categories) {
            ensureActive()
            novelRestorer.restoreCategories(backup.backupNovelCategories)
        }
        if (options.libraryEntries) {
            backup.backupNovels.forEach { backupNovel ->
                ensureActive()
                try {
                    novelRestorer.restore(backupNovel, backup.backupNovelCategories)
                } catch (e: Exception) {
                    errors.add(Date() to "${backupNovel.title} [${backupNovel.source}]: ${e.message}")
                }

                restoreProgress += 1
                notifier.showRestoreProgress(backupNovel.title, restoreProgress, restoreAmount, isSync)
            }
            novelRestorer.restoreMerges(backup.backupNovelMerges, backup.backupNovelUnmerges)
        }
    }

    private fun CoroutineScope.restoreCategories(backupCategories: List<BackupCategory>) = launch {
        ensureActive()
        categoriesRestorer(backupCategories)

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
        // RK: merge/unmerge groups, rebuilt from {url,source} once the restored manga have fresh IDs.
        backupMangaMerges: List<BackupMangaMergeGroup>,
        backupMangaUnmerges: List<BackupMangaMergeGroup>,
    ) = launch {
        mangaRestorer.sortByNew(backupMangas)
            .forEach {
                ensureActive()

                try {
                    mangaRestorer.restore(it, backupCategories)
                } catch (e: Exception) {
                    val sourceName = sourceMapping[it.source] ?: it.source.toString()
                    errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                }

                restoreProgress += 1
                notifier.showRestoreProgress(it.title, restoreProgress, restoreAmount, isSync)
            }

        // RK: with every manga restored (fresh IDs), rebuild the merge prefs from the backup's refs.
        ensureActive()
        mangaRestorer.restoreMerges(backupMangaMerges, backupMangaUnmerges)
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensionStores(
        backup: Backup,
    ) = launch {
        backup.backupExtensionStores
            .forEach {
                ensureActive()

                try {
                    extensionStoreRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error Adding Repo: ${it.name} : ${e.message}")
                }

                restoreProgress += 1
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionStores),
                    restoreProgress,
                    restoreAmount,
                    isSync,
                )
            }

        // RK: with the repos restored, reinstall the recorded manga extensions. Those whose repo is
        // missing can't be matched; log them so the user knows what to reinstall by hand.
        ensureActive()
        try {
            extensionRestorer.restore(backup.backupExtensions).forEach { name ->
                errors.add(Date() to "Extension not reinstalled (repo missing): $name")
            }
        } catch (e: Exception) {
            errors.add(Date() to "Error reinstalling extensions: ${e.message}")
        }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("mihon_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }
}
