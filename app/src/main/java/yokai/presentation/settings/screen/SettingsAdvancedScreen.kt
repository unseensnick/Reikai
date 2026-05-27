package yokai.presentation.settings.screen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.installer.ShizukuInstaller
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.setting.controllers.database.ClearDatabaseController
import eu.kanade.tachiyomi.ui.setting.controllers.debug.DebugController
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import java.io.File
import java.net.URI
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Headers
import rikka.sui.Sui
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.coroutines.resume
import yokai.domain.DialogHostState
import yokai.domain.base.BasePreferences
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.extension.interactor.TrustExtension
import yokai.domain.manga.interactor.GetManga
import yokai.domain.simple
import yokai.domain.source.SourcePreferences
import yokai.domain.ui.settings.ReaderPreferences
import yokai.i18n.MR
import yokai.util.lang.getString
import yokai.presentation.component.preference.Preference
import yokai.presentation.settings.ComposableSettings
import yokai.presentation.settings.screen.advanced.StoryBookScreen
import yokai.presentation.settings.screen.advanced.awaitCleanupDownloadedChapters

object SettingsAdvancedScreen : ComposableSettings() {

    private fun readResolve() = SettingsAdvancedScreen

    @Composable
    override fun getTitleRes(): StringResource = MR.strings.advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences: PreferencesHelper by injectLazy()
        val basePreferences: BasePreferences by injectLazy()
        val networkPreferences: NetworkPreferences by injectLazy()
        val isUpdaterEnabled = BuildConfig.INCLUDE_UPDATER

        return buildList {
            add(Preference.PreferenceItem.SwitchPreference(
                pref = basePreferences.crashReport(),
                title = stringResource(MR.strings.send_crash_report),
                subtitle = stringResource(MR.strings.helps_fix_bugs),
            ))
            add(getDumpCrashLog())
            add(Preference.PreferenceItem.SwitchPreference(
                pref = networkPreferences.verboseLogging(),
                title = stringResource(MR.strings.pref_verbose_logging),
                subtitle = stringResource(MR.strings.pref_verbose_logging_summary),
            ))
            add(getDebugInfo())
            add(getBackgroundActivityGroup())
            if (isUpdaterEnabled || BuildConfig.DEBUG) {
                add(getCheckForBeta(preferences))
            }
            add(getDataManagementGroup())
            add(getNetworkGroup(networkPreferences))
            add(getExtensionGroup(basePreferences))
            add(getLibraryGroup(basePreferences))
            add(getReaderGroup(basePreferences))
            add(getLocalSourceGroup())
            add(getDeveloperGroup())
        }.toPersistentList()
    }

    @Composable
    private fun getDumpCrashLog(): Preference.PreferenceItem.TextPreference {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.dump_crash_logs),
            subtitle = stringResource(MR.strings.saves_error_logs),
            onClick = {
                scope.launchIO {
                    CrashLogUtil(context.localeContext).dumpLogs()
                }
            }
        )
    }

    @Composable
    private fun getDebugInfo(): Preference.PreferenceItem.TextPreference {
        val router = LocalRouter.currentOrThrow

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_debug_info),
            onClick = {
                router.pushController(DebugController().withFadeTransaction())
            }
        )
    }

    @Composable
    private fun getBackgroundActivityGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current

        val children = buildList {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager?
            if (pm != null) {
                add(Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.disable_battery_optimization),
                    subtitle = stringResource(MR.strings.disable_if_issues_with_updating),
                    onClick = {
                        val packageName: String = context.packageName
                        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                            val intent = Intent().apply {
                                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                data = "package:$packageName".toUri()
                            }
                            context.startActivity(intent)
                        } else {
                            context.toast(MR.strings.battery_optimization_disabled)
                        }
                    },
                ))
            }
            add(Preference.PreferenceItem.TextPreference(
                title = "Don't kill my app!",
                subtitle = stringResource(MR.strings.about_dont_kill_my_app),
                onClick = {
                    context.openInBrowser("https://dontkillmyapp.com/")
                },
            ))
        }.toPersistentList()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_background_activity),
            preferenceItems = children,
        )
    }

    @Composable
    private fun getCheckForBeta(preferences: PreferencesHelper): Preference.PreferenceItem.SwitchPreference {
        val scope = rememberCoroutineScope()
        val alertDialog = LocalDialogHostState.currentOrThrow
        val pref = preferences.checkForBetas()

        return Preference.PreferenceItem.SwitchPreference(
            pref = pref,
            title = stringResource(MR.strings.check_for_beta_releases),
            subtitle = stringResource(MR.strings.try_new_features),
            onValueChanged = {
                if (it != BuildConfig.BETA) {
                    scope.launch {
                        alertDialog.simple {
                            titleRes = MR.strings.warning
                            textRes = if (it) MR.strings.warning_enroll_into_beta else MR.strings.warning_unenroll_from_beta
                            onConfirm = {
                                pref.set(it)
                            }
                        }
                    }
                    false
                } else {
                    true
                }
            }
        )
    }

    @Composable
    private fun getDataManagementGroup(): Preference.PreferenceGroup {
        // FIXME: Maybe this should be moved to Data and storage?
        val context = LocalContext.current
        val router = LocalRouter.currentOrThrow
        val scope = rememberCoroutineScope()
        val alertDialog = LocalDialogHostState.currentOrThrow

        val downloadManager: DownloadManager by injectLazy()
        val getChapter: GetChapter by injectLazy()
        val getManga: GetManga by injectLazy()

        val children = buildList {
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.force_download_cache_refresh),
                subtitle = stringResource(MR.strings.force_download_cache_refresh_summary),
                onClick = { downloadManager.refreshCache() },
            ))
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.clean_up_downloaded_chapters),
                subtitle = stringResource(MR.strings.delete_unused_chapters),
                onClick = {
                    scope.launch {
                        val opts = alertDialog.awaitCleanupDownloadedChapters() ?: return@launch
                        cleanupDownloads(
                            context = context,
                            downloadManager = downloadManager,
                            getChapter = getChapter,
                            getManga = getManga,
                            removeRead = opts.deleteRead,
                            removeNonFavorite = opts.deleteNonFavorite,
                        )
                    }
                },
            ))
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_clear_webview_data),
                onClick = { context.clearWebViewData() },
            ))
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.clear_database),
                subtitle = stringResource(MR.strings.clear_database_summary),
                onClick = { router.pushController(ClearDatabaseController().withFadeTransaction()) },
            ))
        }.toPersistentList()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.data_management),
            preferenceItems = children,
        )
    }

    private fun Context.clearWebViewData() {
        try {
            val webview = WebView(this)
            webview.setDefaultSettings()
            webview.clearCache(true)
            webview.clearFormData()
            webview.clearHistory()
            webview.clearSslPreferences()
            WebStorage.getInstance().deleteAllData()
            applicationInfo?.dataDir?.let { File("$it/app_webview/").deleteRecursively() }
            toast(MR.strings.webview_data_deleted)
        } catch (e: Throwable) {
            Logger.e(e) { "Unable to delete WebView data" }
            toast(MR.strings.cache_delete_error)
        }
    }

    @Volatile private var cleanupJob: Job? = null

    @OptIn(DelicateCoroutinesApi::class)
    private fun cleanupDownloads(
        context: Context,
        downloadManager: DownloadManager,
        getChapter: GetChapter,
        getManga: GetManga,
        removeRead: Boolean,
        removeNonFavorite: Boolean,
    ) {
        if (cleanupJob?.isActive == true) return
        context.toast(MR.strings.starting_cleanup)
        cleanupJob = GlobalScope.launch(Dispatchers.IO, CoroutineStart.DEFAULT) {
            val mangaList = getManga.awaitAll()
            val sourceManager: SourceManager = Injekt.get()
            val downloadProvider = DownloadProvider(context)
            var foldersCleared = 0
            val sources = sourceManager.getOnlineSources()

            for (source in sources) {
                val mangaFolders = downloadManager.getMangaFolders(source)
                val sourceManga = mangaList.filter { it.source == source.id }

                for (mangaFolder in mangaFolders) {
                    val manga = sourceManga.find { downloadProvider.getMangaDirName(it) == mangaFolder.name }
                    if (manga == null) {
                        if (removeNonFavorite) {
                            foldersCleared += 1 + (mangaFolder.listFiles()?.size ?: 0)
                            mangaFolder.delete()
                        }
                        continue
                    }
                    val chapterList = getChapter.awaitAll(manga, false)
                    foldersCleared += downloadManager.cleanupChapters(chapterList, manga, source, removeRead, removeNonFavorite)
                }
            }
            launchUI {
                val cleanupString = if (foldersCleared == 0) {
                    context.getString(MR.strings.no_folders_to_cleanup)
                } else {
                    context.getString(MR.plurals.cleanup_done, foldersCleared, foldersCleared)
                }
                context.toast(cleanupString, Toast.LENGTH_LONG)
            }
        }
    }

    @Composable
    private fun getNetworkGroup(networkPreferences: NetworkPreferences): Preference.PreferenceGroup {
        val network: NetworkHelper by injectLazy()
        val context = LocalContext.current

        val children = buildList {
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.clear_cookies),
                onClick = {
                    network.cookieJar.removeAll()
                    context.toast(MR.strings.cookies_cleared)
                },
            ))
            add(Preference.PreferenceItem.ListPreference(
                pref = networkPreferences.dohProvider(),
                title = stringResource(MR.strings.doh),
                entries = mapOf(
                    -1 to stringResource(MR.strings.disabled),
                    PREF_DOH_CLOUDFLARE to "Cloudflare",
                    PREF_DOH_GOOGLE to "Google",
                    PREF_DOH_ADGUARD to "AdGuard",
                    PREF_DOH_QUAD9 to "Quad9",
                    PREF_DOH_ALIDNS to "AliDNS",
                    PREF_DOH_DNSPOD to "DNSPod",
                    PREF_DOH_360 to "360",
                    PREF_DOH_QUAD101 to "Quad 101",
                    PREF_DOH_MULLVAD to "Mullvad",
                    PREF_DOH_CONTROLD to "Control D",
                    PREF_DOH_NJALLA to "Njalla",
                    PREF_DOH_SHECAN to "Shecan",
                ).toImmutableMap(),
                onValueChanged = {
                    context.toast(MR.strings.requires_app_restart)
                    true
                },
            ))
            add(Preference.PreferenceItem.EditTextPreference(
                pref = networkPreferences.defaultUserAgent(),
                title = stringResource(MR.strings.user_agent_string),
                onValueChanged = onChange@{
                    try {
                        // OkHttp checks for valid values internally
                        Headers.Builder().add("User-Agent", it)
                    } catch (_: IllegalArgumentException) {
                        context.toast(MR.strings.error_user_agent_string_invalid)
                        return@onChange false
                    }
                    context.toast(MR.strings.requires_app_restart)
                    true
                },
            ))
            add(Preference.PreferenceItem.EditTextPreference(
                pref = networkPreferences.flareSolverrUrl(),
                title = stringResource(MR.strings.pref_flaresolverr_url),
                onValueChanged = onChange@{
                    if (it.isNotBlank()) {
                        try {
                            val uri = URI(it)
                            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                                context.toast(MR.strings.error_flaresolverr_invalid_url)
                                return@onChange false
                            }
                        } catch (_: Exception) {
                            context.toast(MR.strings.error_flaresolverr_invalid_url)
                            return@onChange false
                        }
                    }
                    true
                },
            ))
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_flaresolverr_disable),
                onClick = {
                    val pref = networkPreferences.flareSolverrUrl()
                    if (pref.get().isBlank()) {
                        context.toast(MR.strings.flaresolverr_already_disabled)
                    } else {
                        pref.set("")
                        context.toast(MR.strings.flaresolverr_disabled)
                    }
                },
            ))
        }.toPersistentList()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.network),
            preferenceItems = children,
        )
    }

    @Composable
    private fun getExtensionGroup(basePreferences: BasePreferences): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val alertDialog = LocalDialogHostState.currentOrThrow
        val trustExtension: TrustExtension by injectLazy()
        val installerPref by basePreferences.extensionInstaller().collectAsState()


        val children = buildList {
            add(Preference.PreferenceItem.ListPreference(
                pref = basePreferences.extensionInstaller(),
                title = stringResource(MR.strings.ext_installer_pref),
                entries = BasePreferences.ExtensionInstaller.entries
                    .associateWith { stringResource(it.titleResId) }
                    .toImmutableMap(),
                onValueChanged = onChange@{
                    if (it == BasePreferences.ExtensionInstaller.SHIZUKU) {
                        return@onChange if (!context.isPackageInstalled(ShizukuInstaller.shizukuPkgName) && !Sui.isSui()) {
                            scope.launch {
                                alertDialog.simple {
                                    titleRes = MR.strings.ext_installer_shizuku
                                    textRes = MR.strings.ext_installer_shizuku_unavailable_dialog
                                    confirmTextRes = MR.strings.download
                                    onConfirm = {
                                        context.openInBrowser(ShizukuInstaller.downloadLink)
                                    }
                                }
                            }
                            false
                        } else {
                            true
                        }
                    }
                    true
                }
            ))
            if ((installerPref == BasePreferences.ExtensionInstaller.SHIZUKU && Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                || installerPref == BasePreferences.ExtensionInstaller.LEGACY) {
                add(Preference.PreferenceItem.InfoPreference(
                    title = stringResource(when (installerPref) {
                        BasePreferences.ExtensionInstaller.SHIZUKU -> {
                            MR.strings.ext_installer_summary
                        }
                        BasePreferences.ExtensionInstaller.LEGACY -> {
                            MR.strings.ext_installer_summary_legacy
                        }
                        else -> {
                            throw IllegalStateException("How?")
                        }
                    }),
                ))
            }
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.action_revoke_all_extensions),
                onClick = {
                    scope.launch {
                        alertDialog.simple {
                            titleRes = MR.strings.confirm_revoke_all_extensions
                            onConfirm = {
                                trustExtension.revokeAll()
                                context.toast(MR.strings.requires_app_restart)
                            }
                        }
                    }
                },
            ))
        }.toPersistentList()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.extensions),
            preferenceItems = children,
        )
    }

    @Composable
    private fun getLibraryGroup(basePreferences: BasePreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val preferences: PreferencesHelper = remember { Injekt.get() }
        val getManga: GetManga = remember { Injekt.get() }
        val alertDialog = LocalDialogHostState.currentOrThrow
        val scope = rememberCoroutineScope()

        val children = buildList {
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.refresh_library_metadata),
                subtitle = stringResource(MR.strings.updates_covers_genres_desc),
                onClick = { LibraryUpdateJob.startNow(context, target = LibraryUpdateJob.Target.DETAILS) },
            ))
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.refresh_tracking_metadata),
                subtitle = stringResource(MR.strings.updates_tracking_details),
                onClick = { LibraryUpdateJob.startNow(context, target = LibraryUpdateJob.Target.TRACKING) },
            ))
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.clear_all_manual_merges),
                subtitle = stringResource(MR.strings.clear_all_manual_merges_summary),
                onClick = {
                    scope.launch {
                        // 3-button dialog so the user picks the scope at clear-time:
                        //  - Manual only: clear mangaManualMerges, leave auto-grouping alone.
                        //  - All including auto-grouped: also walk current favorites and add an
                        //    unmerge pair for every same-title duplicate so the auto-grouping
                        //    stops re-creating bad pairs. Same-title auto-grouping still applies
                        //    to NEW favorites added later (their fresh ids aren't in any unmerge
                        //    pair, so they auto-group on first encounter).
                        // mangaManualUnmerges accumulates either way; the user's explicit
                        // "do not auto-group" rules from past long-presses stay respected.
                        val choice = chooseClearMergesScope(alertDialog)
                        when (choice) {
                            ClearMergesChoice.MANUAL_ONLY -> {
                                preferences.mangaManualMerges().set(emptySet())
                                context.toast(MR.strings.cleared_all_manual_merges)
                            }
                            ClearMergesChoice.ALL_INCLUDING_AUTO -> {
                                clearAllMergesIncludingAuto(preferences, getManga)
                                context.toast(MR.strings.cleared_all_merges_including_auto)
                            }
                            ClearMergesChoice.CANCEL -> Unit
                        }
                    }
                },
            ))
            add(Preference.PreferenceItem.SwitchPreference(
                pref = basePreferences.useSharedLibraryDisplayPrefs(),
                title = stringResource(MR.strings.pref_share_library_display_prefs),
                subtitle = stringResource(MR.strings.pref_share_library_display_prefs_summary),
            ))
            if (BuildConfig.FLAVOR == "dev" || BuildConfig.DEBUG) {
                add(Preference.PreferenceItem.SwitchPreference(
                    pref = basePreferences.composeLibrary(),
                    title = stringResource(MR.strings.pref_use_compose_library),
                    // FIXME: Add beta tag support to preference item
                ))
            }
        }.toPersistentList()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.library),
            preferenceItems = children,
        )
    }

    @Composable
    private fun getReaderGroup(basePreferences: BasePreferences): Preference.PreferenceGroup {
        val readerPreferences: ReaderPreferences by injectLazy()
        val context = LocalContext.current
        val displayProfilePath by basePreferences.displayProfile().collectAsState()
        val displayProfileSubtitle = remember(displayProfilePath) {
            if (displayProfilePath.isEmpty()) {
                null
            } else {
                UniFile.fromUri(context, displayProfilePath.toUri())?.filePath ?: displayProfilePath
            }
        }

        val children = buildList {
            if (!ImageUtil.HARDWARE_BITMAP_UNSUPPORTED && GLUtil.DEVICE_TEXTURE_LIMIT > GLUtil.SAFE_TEXTURE_LIMIT) {
                val entries = GLUtil.CUSTOM_TEXTURE_LIMIT_OPTIONS
                    .mapIndexed { index, option ->
                        val display = if (index == 0) {
                            context.getString(MR.strings.pref_hardware_bitmap_threshold_default, option)
                        } else {
                            option.toString()
                        }
                        option to display
                    }
                    .toMap()
                    .toImmutableMap()
                add(Preference.PreferenceItem.ListPreference(
                    pref = basePreferences.hardwareBitmapThreshold(),
                    title = stringResource(MR.strings.pref_hardware_bitmap_threshold),
                    subtitle = stringResource(MR.strings.pref_hardware_bitmap_threshold_summary, "%s"),
                    entries = entries,
                ))
            }
            add(Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_display_profile),
                subtitle = displayProfileSubtitle,
                onClick = { (context as? MainActivity)?.showColourProfilePicker() },
            ))
            add(Preference.PreferenceItem.SwitchPreference(
                pref = readerPreferences.debugMode(),
                title = stringResource(MR.strings.pref_reader_debug_mode),
            ))
        }.toPersistentList()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.reader),
            preferenceItems = children,
        )
    }

    @Composable
    private fun getLocalSourceGroup(): Preference.PreferenceGroup {
        val sourcePreferences: SourcePreferences by injectLazy()

        // FIXME: Add beta tag support to preference item — matches the FIXME in getLibraryGroup.
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.local_source),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = sourcePreferences.externalLocalSource(),
                    title = stringResource(MR.strings.pref_external_local_source),
                ),
            ).toPersistentList(),
        )
    }

    @Composable
    private fun getDeveloperGroup(): Preference.PreferenceGroup {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val alertDialog = LocalDialogHostState.currentOrThrow

        val children = buildList {
            add(Preference.PreferenceItem.TextPreference(
                title = "Storybook",
                onClick = { navigator.push(StoryBookScreen()) },
            ))
            if (BuildConfig.FLAVOR == "dev" || BuildConfig.DEBUG || BuildConfig.NIGHTLY) {
                add(Preference.PreferenceItem.TextPreference(
                    title = "Crash the app!",
                    subtitle = "To test crashes",
                    onClick = {
                        scope.launch {
                            alertDialog.simple {
                                titleRes = MR.strings.warning
                                text = "I told you this would crash the app, why would you want that?"
                                confirmText = "Crash it anyway"
                                onConfirm = { throw RuntimeException("Fell into the void") }
                            }
                        }
                    },
                ))
                add(Preference.PreferenceItem.TextPreference(
                    title = "Prune finished workers",
                    subtitle = "In case worker stuck in FAILED state and you're too impatient to wait",
                    onClick = {
                        scope.launch {
                            alertDialog.simple {
                                title = "Are you sure?"
                                text = "Failed workers should clear out by itself eventually, this option should only be used if you're being impatient and you know what you're doing."
                                confirmText = "Prune"
                                onConfirm = { context.workManager.pruneWork() }
                            }
                        }
                    },
                ))
            }
        }.toPersistentList()

        return Preference.PreferenceGroup(
            title = "Danger Zone!",
            preferenceItems = children,
        )
    }
}

private enum class ClearMergesChoice { CANCEL, MANUAL_ONLY, ALL_INCLUDING_AUTO }

/**
 * 3-button confirm dialog for the "Clear all manual merges" setting. The user chooses scope at
 * confirm time instead of needing a separate settings entry per scope.
 */
private suspend fun chooseClearMergesScope(dialogHostState: DialogHostState): ClearMergesChoice =
    dialogHostState.dialog<ClearMergesChoice> { cont ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = stringResource(MR.strings.clear_all_manual_merges),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = stringResource(MR.strings.clear_all_manual_merges_confirm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onDismissRequest = { if (cont.isActive) cont.resume(ClearMergesChoice.CANCEL) },
            confirmButton = {
                // Stacked horizontally so "All" sits next to "Manual only", with Cancel below
                // as the dismiss button. The two action labels make the trade-off explicit.
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        if (cont.isActive) cont.resume(ClearMergesChoice.MANUAL_ONLY)
                    }) {
                        Text(stringResource(MR.strings.clear_manual_only_action))
                    }
                    TextButton(onClick = {
                        if (cont.isActive) cont.resume(ClearMergesChoice.ALL_INCLUDING_AUTO)
                    }) {
                        Text(stringResource(MR.strings.clear_all_incl_auto_action))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (cont.isActive) cont.resume(ClearMergesChoice.CANCEL)
                }) {
                    Text(stringResource(MR.strings.cancel))
                }
            },
        )
    }

/**
 * Wipes `mangaManualMerges` AND walks every favourited manga to find same-title duplicates,
 * adding an unmerge pair for each so the auto-grouping path can't re-create the same groups.
 * Auto-grouping for NEW favourites added later still works because their ids don't appear in
 * any unmerge pair, so they auto-group on first encounter with an existing same-title entry.
 */
private suspend fun clearAllMergesIncludingAuto(
    preferences: PreferencesHelper,
    getManga: GetManga,
) {
    val favorites = getManga.awaitFavorites()
    val byTitle = HashMap<String, MutableList<Long>>()
    for (m in favorites) {
        val id = m.id ?: continue
        val key = m.title.trim().lowercase()
        if (key.isEmpty()) continue
        byTitle.getOrPut(key) { mutableListOf() } += id
    }

    val newUnmerges = mutableSetOf<String>()
    for ((_, ids) in byTitle) {
        if (ids.size < 2) continue
        val sorted = ids.sorted()
        for (i in sorted.indices) {
            for (j in (i + 1) until sorted.size) {
                newUnmerges += "${sorted[i]},${sorted[j]}"
            }
        }
    }

    val existingUnmerges = preferences.mangaManualUnmerges().get()
    preferences.mangaManualMerges().set(emptySet())
    preferences.mangaManualUnmerges().set(existingUnmerges + newUnmerges)
}
