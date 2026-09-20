package eu.kanade.presentation.more.settings.screen

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
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
import eu.kanade.tachiyomi.network.interceptor.FlareSolverrTestFailure
import eu.kanade.tachiyomi.network.interceptor.FlareSolverrTestResult
import eu.kanade.tachiyomi.network.interceptor.TurnstileSolver
import eu.kanade.tachiyomi.network.interceptor.splitFlareSolverrUserInfo
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
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
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.RepairNovelDetails
import reikai.presentation.settings.FlareSolverrPasswordDialog
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
        return listOfNotNull(
            // RK --> the loose rows at the top grouped under what they have in common (owner ruling, see
            // docs/dev/plans/settings-restructure.md). The two update-error switches moved to Settings -> Library,
            // the adult-sources gate to Browse and sources, and clearing merges into the Library group below.
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_debugging),
                preferenceItems = listOf(
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
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_debug_info),
                        onClick = { navigator.push(DebugInfoScreen()) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_help),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_onboarding_guide),
                        onClick = { navigator.push(OnboardingScreen()) },
                    ),
                ),
            ),
            // RK <--
            getBackgroundActivityGroup(),
            getDataGroup(),
            getNetworkGroup(networkPreferences = networkPreferences),
            getLibraryGroup(libraryPreferences = libraryPreferences),
            getReaderGroup(basePreferences = basePreferences, novelPreferences = remember { graph.novelPreferences }),
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
                // RK --> moved from the top of the screen: like battery optimization, it opens Android's own settings
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_manage_notifications),
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    },
                ),
                // RK <--
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
                        context.appGraph.novelDownloadCache.invalidate() // RK
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
        val flareSolverrUsername by networkPreferences.flareSolverrUsername.collectAsState()
        val flareSolverrPassword by networkPreferences.flareSolverrPassword.collectAsState()
        var flareSolverrTesting by remember { mutableStateOf(false) }
        var flareSolverrTestResult by remember { mutableStateOf<FlareSolverrTestResult?>(null) }
        var flareSolverrTestFailure by remember { mutableStateOf<FlareSolverrTestResult.Failure?>(null) }
        var showFlareSolverrPassword by remember { mutableStateOf(false) }
        val turnstileSolverEnabled by networkPreferences.enableTurnstileSolver.collectAsState()
        // Spike state, debug only: mirrors the solver's own flag so the row can show it.
        var forceHeadlessSolver by remember { mutableStateOf(TurnstileSolver.forceHeadless) }
        var forceNoWatchSolver by remember { mutableStateOf(TurnstileSolver.forceNoWatch) }

        // A local copy, so the row below can tell the two outcomes apart.
        val lastTest = flareSolverrTestResult

        if (showFlareSolverrPassword) {
            FlareSolverrPasswordDialog(
                currentPassword = networkPreferences.flareSolverrPassword.get(),
                onConfirm = {
                    networkPreferences.flareSolverrPassword.set(it)
                    showFlareSolverrPassword = false
                },
                onDismissRequest = { showFlareSolverrPassword = false },
            )
        }

        flareSolverrTestFailure?.let { failure ->
            val dismiss = { flareSolverrTestFailure = null }
            val reason = stringResource(failure.reason.stringRes())
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(text = stringResource(MR.strings.pref_test_flaresolverr)) },
                text = {
                    // The server's own words go with the plain reason: they are what a reader pastes
                    // into a bug report, and they are the only part naming an unforeseen failure.
                    Text(
                        text = "$reason\n\n${failure.detail}",
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                dismissButton = {
                    TextButton(
                        onClick = { context.copyToClipboard(reason, "$reason\n${failure.detail}") },
                    ) {
                        Text(text = stringResource(MR.strings.action_copy_to_clipboard))
                    }
                },
                confirmButton = {
                    TextButton(onClick = dismiss) {
                        Text(text = stringResource(MR.strings.action_close))
                    }
                },
            )
        }

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
                //     entirely in this DSL, so a release build shows none of the three.
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
                        when {
                            it.isBlank() -> true
                            it.trim().toHttpUrlOrNull() == null -> {
                                context.toast(MR.strings.error_flaresolverr_invalid_url)
                                false
                            }
                            // Credentials in the address authenticate nothing and would travel in
                            // every backup, since this key is not private. The fields below are.
                            splitFlareSolverrUserInfo(it) != null -> {
                                context.toast(MR.strings.error_flaresolverr_url_credentials)
                                false
                            }
                            else -> true
                        }
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = networkPreferences.flareSolverrUsername,
                    title = stringResource(MR.strings.pref_flaresolverr_username),
                    subtitle = if (flareSolverrUsername.isBlank()) {
                        stringResource(MR.strings.pref_flaresolverr_username_summary)
                    } else {
                        "%s"
                    },
                    enabled = flareSolverrEnabled,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_flaresolverr_password),
                    // Never "%s": a preference row renders its value as the subtitle, so an
                    // EditTextPreference here would print the password on the screen.
                    subtitle = if (flareSolverrPassword.isBlank()) {
                        stringResource(MR.strings.pref_flaresolverr_password_unset)
                    } else {
                        stringResource(MR.strings.pref_flaresolverr_password_set)
                    },
                    enabled = flareSolverrEnabled,
                    onClick = { showFlareSolverrPassword = true },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_test_flaresolverr),
                    // The row carries the outcome, because a toast is gone in a second and cuts a
                    // long message off; this one holds up to ten lines until the screen is left.
                    subtitle = when {
                        flareSolverrTesting -> stringResource(MR.strings.flaresolverr_test_running)
                        lastTest is FlareSolverrTestResult.Success ->
                            stringResource(MR.strings.flaresolverr_test_success)
                        lastTest is FlareSolverrTestResult.Failure ->
                            stringResource(lastTest.reason.stringRes())
                        else -> stringResource(MR.strings.pref_test_flaresolverr_summary)
                    },
                    enabled = flareSolverrEnabled && !flareSolverrTesting,
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
                                flareSolverrTesting = true
                                val result = networkHelper.flareSolverr.test(url)
                                flareSolverrTesting = false
                                flareSolverrTestResult = result
                                flareSolverrTestFailure = result as? FlareSolverrTestResult.Failure
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

        // RK --> library maintenance that used to sit in the unheaded block at the top of this screen.
        // These stay in Advanced rather than moving beside the everyday merge switches in Library
        // settings, because dissolving every merge group is destructive and belongs with the other
        // destructive actions, not one mis-tap from a toggle.
        val graph = remember { context.appGraph }
        val mergeManager = remember { graph.mangaMergeManager }
        val novelMergeManager = remember { graph.novelMergeManager }
        val repairNovelDetails = remember { graph.repairNovelDetails }
        // RK <--

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
            ),
        )
    }

    @Composable
    private fun getReaderGroup(
        basePreferences: BasePreferences,
        // RK
        novelPreferences: NovelPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reader),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = basePreferences.highQualityRenderer,
                    title = stringResource(MR.strings.pref_high_quality_renderer),
                ),
                // RK -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerWebViewDevTools(),
                    title = stringResource(MR.strings.pref_novel_webview_dev_tools),
                    subtitle = stringResource(MR.strings.pref_novel_webview_dev_tools_summary),
                ),
                // RK <--
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
                    onClick = { trustExtension.revokeAll() },
                ),
            ),
        )
    }
}

// RK: the solver reports a case rather than a sentence, so the screen names it in the reader's
// language. The server's own text still reaches them through the failure dialog.
private fun FlareSolverrTestFailure.stringRes(): StringResource = when (this) {
    FlareSolverrTestFailure.AUTH_REQUIRED -> MR.strings.flaresolverr_test_error_auth
    FlareSolverrTestFailure.FORBIDDEN -> MR.strings.flaresolverr_test_error_forbidden
    FlareSolverrTestFailure.NOT_FOUND -> MR.strings.flaresolverr_test_error_not_found
    FlareSolverrTestFailure.SOLVER_DOWN -> MR.strings.flaresolverr_test_error_solver_down
    FlareSolverrTestFailure.HTTP_ERROR -> MR.strings.flaresolverr_test_error_http
    FlareSolverrTestFailure.UNREACHABLE -> MR.strings.flaresolverr_test_error_unreachable
    FlareSolverrTestFailure.NOT_A_SOLVER -> MR.strings.flaresolverr_test_error_not_solver
    FlareSolverrTestFailure.SOLVE_FAILED -> MR.strings.flaresolverr_test_error_solve
}
