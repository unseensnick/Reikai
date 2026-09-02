package eu.kanade.presentation.more.settings.screen

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.advanced.ClearDatabaseScreen
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
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
import eu.kanade.tachiyomi.network.interceptor.TurnstileHarness
import eu.kanade.tachiyomi.network.interceptor.TurnstileSolver
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import eu.kanade.tachiyomi.util.system.isShizukuInstalled
import eu.kanade.tachiyomi.util.system.powerManager
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import reikai.domain.novel.interactor.RepairNovelDetails
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.io.File

object SettingsAdvancedScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val graph = remember { context.appGraph }
        val basePreferences = remember { graph.basePreferences }
        val networkPreferences = remember { graph.networkPreferences }
        val libraryPreferences = remember { graph.libraryPreferences }
        // RK: opt-in for the library update-errors screen
        val reikaiLibraryPreferences = remember { graph.reikaiLibraryPreferences }
        // RK: pref-based merge maintenance
        val mergeManager = remember { graph.mangaMergeManager }
        val novelMergeManager = remember { graph.novelMergeManager }
        val repairNovelDetails = remember { graph.repairNovelDetails }
        // RK: gate for the built-in adult sources (E-Hentai / ExHentai)
        val exhPreferences = remember { graph.exhPreferences }

        return listOfNotNull(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_dump_crash_logs),
                subtitle = stringResource(MR.strings.pref_dump_crash_logs_summary),
                onClick = {
                    scope.launch {
                        context.appGraph.crashLogUtil.dumpLogs()
                    }
                },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = networkPreferences.verboseLogging,
                title = stringResource(MR.strings.pref_verbose_logging),
                subtitle = stringResource(MR.strings.pref_verbose_logging_summary),
                onValueChanged = {
                    context.toast(MR.strings.requires_app_restart)
                    true
                },
            ),
            // RK: opt-in for recording library update failures + the Update errors screen. Content-typed
            // titles ("Track update errors · Manga|Novels") match the Library / Downloads label style.
            Preference.PreferenceItem.SwitchPreference(
                preference = reikaiLibraryPreferences.trackUpdateErrors,
                title = contentTypedCategory(MR.strings.pref_track_update_errors, MR.strings.content_type_manga),
                subtitle = stringResource(MR.strings.pref_track_update_errors_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = reikaiLibraryPreferences.trackNovelUpdateErrors,
                title = contentTypedCategory(MR.strings.pref_track_update_errors, MR.strings.content_type_novels),
                subtitle = stringResource(MR.strings.pref_track_update_errors_summary),
            ),
            // RK: enable the built-in E-Hentai sources (anonymous browsing). Turning this on reveals
            //     the dedicated E-Hentai settings as a top-level category (login, image quality, etc.).
            Preference.PreferenceItem.SwitchPreference(
                preference = exhPreferences.isHentaiEnabled(),
                title = stringResource(MR.strings.pref_enable_adult_sources),
                subtitle = stringResource(MR.strings.pref_enable_adult_sources_summary),
            ),
            // RK --> dissolve every merge group. The old "clear manual" vs "separate auto" split
            // collapsed after the rebuild (both now clear every group), so it is one action per type.
            Preference.PreferenceItem.TextPreference(
                title = contentTypedCategory(MR.strings.pref_clear_merges, MR.strings.content_type_manga),
                subtitle = stringResource(MR.strings.pref_clear_merges_summary),
                onClick = {
                    scope.launch {
                        mergeManager.clearAllMergesIncludingAuto()
                        context.toast(MR.strings.merges_cleared)
                    }
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = contentTypedCategory(MR.strings.pref_clear_merges, MR.strings.content_type_novels),
                subtitle = stringResource(MR.strings.pref_clear_merges_summary),
                onClick = {
                    scope.launch {
                        novelMergeManager.clearAllMergesIncludingAuto()
                        context.toast(MR.strings.merges_cleared)
                    }
                },
            ),
            // Repair novels left wearing another novel's details by the plugin-host result mix-up
            // (fixed, but rows written before the fix stay wrong until something re-fetches them).
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_repair_novel_details),
                subtitle = stringResource(MR.strings.pref_repair_novel_details_summary),
                onClick = {
                    scope.launch {
                        val result = repairNovelDetails.await()
                        if (result.suspects == 0) {
                            context.toast(MR.strings.novel_details_repair_none)
                        } else {
                            context.toast(
                                context.stringResource(
                                    MR.strings.novel_details_repair_done,
                                    result.repaired,
                                    result.suspects,
                                ),
                            )
                        }
                    }
                },
            ),
            // RK <--
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_debug_info),
                onClick = { navigator.push(DebugInfoScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_onboarding_guide),
                onClick = { navigator.push(OnboardingScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_manage_notifications),
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                },
            ),
            getBackgroundActivityGroup(),
            getDataGroup(),
            getNetworkGroup(networkPreferences = networkPreferences),
            getLibraryGroup(libraryPreferences = libraryPreferences),
            getReaderGroup(basePreferences = basePreferences),
            getExtensionsGroup(basePreferences = basePreferences),
        )
    }

    @Composable
    private fun getBackgroundActivityGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_background_activity),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_disable_battery_optimization),
                    subtitle = stringResource(MR.strings.pref_disable_battery_optimization_summary),
                    onClick = {
                        val packageName: String = context.packageName
                        if (!context.powerManager.isIgnoringBatteryOptimizations(packageName)) {
                            try {
                                @SuppressLint("BatteryLife")
                                val intent = Intent().apply {
                                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                    data = "package:$packageName".toUri()
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                context.toast(MR.strings.battery_optimization_setting_activity_not_found)
                            }
                        } else {
                            context.toast(MR.strings.battery_optimization_disabled)
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Don't kill my app!",
                    subtitle = stringResource(MR.strings.about_dont_kill_my_app),
                    onClick = { uriHandler.openUri("https://dontkillmyapp.com/") },
                ),
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_data),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_invalidate_download_cache),
                    subtitle = stringResource(MR.strings.pref_invalidate_download_cache_summary),
                    onClick = {
                        context.appGraph.downloadCache.invalidateCache()
                        context.toast(MR.strings.download_cache_invalidated)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_database),
                    subtitle = stringResource(MR.strings.pref_clear_database_summary),
                    onClick = { navigator.push(ClearDatabaseScreen()) },
                ),
            ),
        )
    }

    @Composable
    private fun getNetworkGroup(
        networkPreferences: NetworkPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val networkHelper = remember { context.appGraph.networkHelper }

        val userAgentPref = networkPreferences.defaultUserAgent
        val userAgent by userAgentPref.collectAsState()

        // RK: FlareSolverr settings live in the Network group, gated on the enable toggle
        val scope = rememberCoroutineScope()
        val flareSolverrEnabled by networkPreferences.enableFlareSolverr.collectAsState()
        val flareSolverrUrl by networkPreferences.flareSolverrUrl.collectAsState()
        val turnstileSolverEnabled by networkPreferences.enableTurnstileSolver.collectAsState()
        // Spike state, debug only: mirrors the solver's own flag so the row can show it.
        var forceHeadlessSolver by remember { mutableStateOf(TurnstileSolver.forceHeadless) }
        var forceNoWatchSolver by remember { mutableStateOf(TurnstileSolver.forceNoWatch) }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_network),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_cookies),
                    onClick = {
                        networkHelper.cookieJar.removeAll()
                        context.toast(MR.strings.cookies_cleared)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_webview_data),
                    onClick = {
                        try {
                            WebView(context).run {
                                setDefaultSettings()
                                clearCache(true)
                                clearFormData()
                                clearHistory()
                                clearSslPreferences()
                            }
                            WebStorage.getInstance().deleteAllData()
                            context.applicationInfo?.dataDir?.let { File("$it/app_webview/").deleteRecursively() }
                            context.toast(MR.strings.webview_data_deleted)
                        } catch (e: Throwable) {
                            logcat(LogPriority.ERROR, e)
                            context.toast(MR.strings.cache_delete_error)
                        }
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = networkPreferences.dohProvider,
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
                    ),
                    title = stringResource(MR.strings.pref_dns_over_https),
                    onValueChanged = {
                        context.toast(MR.strings.requires_app_restart)
                        true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = userAgentPref,
                    title = stringResource(MR.strings.pref_user_agent_string),
                    onValueChanged = {
                        try {
                            // OkHttp checks for valid values internally
                            Headers.Builder().add("User-Agent", it)
                            context.toast(MR.strings.requires_app_restart)
                        } catch (_: IllegalArgumentException) {
                            context.toast(MR.strings.error_user_agent_string_invalid)
                            return@EditTextPreference false
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_reset_user_agent_string),
                    enabled = remember(userAgent) { userAgent != userAgentPref.defaultValue() },
                    onClick = {
                        userAgentPref.delete()
                        context.toast(MR.strings.requires_app_restart)
                    },
                ),
                // RK -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = networkPreferences.enableTurnstileSolver,
                    title = stringResource(MR.strings.pref_enable_turnstile_solver),
                    subtitle = stringResource(MR.strings.pref_enable_turnstile_solver_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = networkPreferences.enableTurnstileBackgroundSolver,
                    title = stringResource(MR.strings.pref_enable_turnstile_background_solver),
                    subtitle = stringResource(MR.strings.pref_enable_turnstile_background_solver_summary),
                    enabled = turnstileSolverEnabled,
                ),
                // RK: spike instrumentation, debug builds only. `enabled = false` removes the row
                //     entirely in this DSL, so a release build shows none of the five.
                Preference.PreferenceItem.TextPreference(
                    title = "Turnstile: library update in 60s (spike)",
                    subtitle = "Queues a manual update after a delay. Kill the app during it, and the " +
                        "job starts a process with no activity, which is the solver's real no-window trigger",
                    enabled = BuildConfig.DEBUG,
                    onClick = {
                        LibraryUpdateJob.startDelayed(context.workManager, delaySeconds = 60)
                        context.toast("Library update queued for 60s, kill the app now")
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Turnstile: force the no-isolated-world path (spike)",
                    subtitle = "Currently ${if (forceNoWatchSolver) "on" else "off"}. Solves as if the " +
                        "WebView were too old for an isolated world, on events alone with no probe. Resets on restart",
                    enabled = BuildConfig.DEBUG,
                    onClick = {
                        forceNoWatchSolver = !forceNoWatchSolver
                        TurnstileSolver.forceNoWatch = forceNoWatchSolver
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Turnstile: force the no-window path (spike)",
                    subtitle = "Currently ${if (forceHeadlessSolver) "on" else "off"}. Solves as if no " +
                        "app screen were open, which otherwise only a scheduled update reaches. Resets on restart",
                    enabled = BuildConfig.DEBUG,
                    onClick = {
                        forceHeadlessSolver = !forceHeadlessSolver
                        TurnstileSolver.forceHeadless = forceHeadlessSolver
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Turnstile harness: dummy sitekey (spike)",
                    subtitle = "Bisect sweep, no live challenge needed",
                    enabled = BuildConfig.DEBUG,
                    onClick = {
                        TurnstileHarness.run(
                            context,
                            TurnstileHarness.Target.Dummy,
                        )
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Turnstile harness: live site (spike)",
                    subtitle = "Bisect sweep against the first host that challenges interactively",
                    enabled = BuildConfig.DEBUG,
                    onClick = {
                        TurnstileHarness.run(
                            context,
                            TurnstileHarness.Target.Live(
                                listOf(
                                    "https://comix.to/",
                                    "https://comix.ws/",
                                    "https://aquareader.org/",
                                    "https://mangafire.to/",
                                    "https://toonily.com/",
                                    "https://www.natomanga.com/",
                                    "https://comick.live/",
                                ),
                            ),
                        )
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = networkPreferences.enableFlareSolverr,
                    title = stringResource(MR.strings.pref_enable_flaresolverr),
                    subtitle = stringResource(MR.strings.pref_enable_flaresolverr_summary),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = networkPreferences.flareSolverrUrl,
                    title = stringResource(MR.strings.pref_flaresolverr_url),
                    // The example is only worth screen space until an address exists; after that the
                    // address is what the reader wants. "%s" is the widget's own value placeholder,
                    // so the URL is never run through a format string that could choke on a percent.
                    subtitle = if (flareSolverrUrl.isBlank()) {
                        stringResource(MR.strings.pref_flaresolverr_url_summary)
                    } else {
                        "%s"
                    },
                    enabled = flareSolverrEnabled,
                    onValueChanged = {
                        if (it.isBlank() || it.trim().toHttpUrlOrNull() != null) {
                            true
                        } else {
                            context.toast(MR.strings.error_flaresolverr_invalid_url)
                            false
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_test_flaresolverr),
                    subtitle = stringResource(MR.strings.pref_test_flaresolverr_summary),
                    enabled = flareSolverrEnabled,
                    onClick = {
                        val url = networkPreferences.flareSolverrUrl.get().trim()
                        if (url.isBlank()) {
                            context.toast(MR.strings.error_flaresolverr_invalid_url)
                        } else {
                            scope.launch {
                                // The agent it reports is deliberately not stored as the app default.
                                // Doing that made every WebView announce FlareSolverr's desktop browser
                                // while running as Android WebView, and Cloudflare re-challenged that
                                // mismatch endlessly. UserAgentInterceptor already pins the agent per
                                // solved host, which is what cf_clearance is actually bound to.
                                networkHelper.flareSolverr.test(url)
                                    .onSuccess {
                                        context.toast(MR.strings.flaresolverr_test_success)
                                    }
                                    .onFailure {
                                        context.toast(MR.strings.flaresolverr_test_failure)
                                    }
                            }
                        }
                    },
                ),
                // RK <--
            ),
        )
    }

    @Composable
    private fun getLibraryGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_library),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_refresh_library_covers),
                    onClick = { MetadataUpdateJob.startNow(context.workManager) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_reset_viewer_flags),
                    subtitle = stringResource(MR.strings.pref_reset_viewer_flags_summary),
                    onClick = {
                        scope.launchNonCancellable {
                            val success = context.appGraph.resetViewerFlags.await()
                            withUIContext {
                                val message = if (success) {
                                    MR.strings.pref_reset_viewer_flags_success
                                } else {
                                    MR.strings.pref_reset_viewer_flags_error
                                }
                                context.toast(message)
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.updateMangaTitles,
                    title = stringResource(MR.strings.pref_update_library_manga_titles),
                    subtitle = stringResource(MR.strings.pref_update_library_manga_titles_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.disallowNonAsciiFilenames,
                    title = stringResource(MR.strings.pref_disallow_non_ascii_filenames),
                    subtitle = stringResource(MR.strings.pref_disallow_non_ascii_filenames_details),
                ),
            ),
        )
    }

    @Composable
    private fun getReaderGroup(
        basePreferences: BasePreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reader),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = basePreferences.highQualityRenderer,
                    title = stringResource(MR.strings.pref_high_quality_renderer),
                ),
            ),
        )
    }

    @Composable
    private fun getExtensionsGroup(
        basePreferences: BasePreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val extensionInstallerPref = basePreferences.extensionInstaller
        var shizukuMissing by rememberSaveable { mutableStateOf(false) }
        val trustExtension = remember { context.appGraph.trustExtension }

        if (shizukuMissing) {
            val dismiss = { shizukuMissing = false }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(text = stringResource(MR.strings.ext_installer_shizuku)) },
                text = { Text(text = stringResource(MR.strings.ext_installer_shizuku_unavailable_dialog)) },
                dismissButton = {
                    TextButton(onClick = dismiss) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dismiss()
                            uriHandler.openUri("https://shizuku.rikka.app/download")
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
            )
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_extensions),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = extensionInstallerPref,
                    entries = extensionInstallerPref.entries
                        .filter {
                            // TODO: allow private option in stable versions once URL handling is more fleshed out
                            if (isReleaseBuildType) {
                                it != BasePreferences.ExtensionInstaller.PRIVATE
                            } else {
                                true
                            }
                        }
                        .associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.ext_installer_pref),
                    onValueChanged = {
                        if (it == BasePreferences.ExtensionInstaller.SHIZUKU &&
                            !context.isShizukuInstalled
                        ) {
                            shizukuMissing = true
                            false
                        } else {
                            true
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.ext_revoke_trust),
                    onClick = {
                        trustExtension.revokeAll()
                        context.toast(MR.strings.requires_app_restart)
                    },
                ),
            ),
        )
    }
}
