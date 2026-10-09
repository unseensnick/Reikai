package reikai.presentation.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.FlareSolverrTestFailure
import eu.kanade.tachiyomi.network.interceptor.FlareSolverrTestResult
import eu.kanade.tachiyomi.network.interceptor.TurnstileSolver
import eu.kanade.tachiyomi.network.interceptor.isPrivateChannel
import eu.kanade.tachiyomi.network.interceptor.splitFlareSolverrUserInfo
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * The Cloudflare bypass rows at the end of Settings -> Advanced -> Network: the in-app Turnstile
 * solver and a FlareSolverr server, plus the sign-in and failed-test dialogs those rows open.
 */
@Composable
fun bypassPreferenceItems(networkPreferences: NetworkPreferences): List<Preference.PreferenceItem<out Any, out Any>> {
    val context = LocalContext.current
    val networkHelper = remember { context.appGraph.networkHelper }
    val scope = rememberCoroutineScope()
    val flareSolverrEnabled by networkPreferences.enableFlareSolverr.collectAsState()
    val flareSolverrUrl by networkPreferences.flareSolverrUrl.collectAsState()
    val flareSolverrUsername by networkPreferences.flareSolverrUsername.collectAsState()
    val flareSolverrPassword by networkPreferences.flareSolverrPassword.collectAsState()
    var flareSolverrTesting by remember { mutableStateOf(false) }
    var flareSolverrTestResult by remember { mutableStateOf<FlareSolverrTestResult?>(null) }
    var flareSolverrTestFailure by remember { mutableStateOf<FlareSolverrTestResult.Failure?>(null) }
    var showFlareSolverrLogin by remember { mutableStateOf(false) }
    val turnstileSolverEnabled by networkPreferences.enableTurnstileSolver.collectAsState()
    // Spike state, debug only: mirrors the solver's own flag so the row can show it.
    var forceHeadlessSolver by remember { mutableStateOf(TurnstileSolver.forceHeadless) }
    var forceNoWatchSolver by remember { mutableStateOf(TurnstileSolver.forceNoWatch) }

    // A local copy, so the row below can tell the two outcomes apart.
    val lastTest = flareSolverrTestResult

    if (showFlareSolverrLogin) {
        FlareSolverrLoginDialog(
            currentUsername = flareSolverrUsername,
            currentPassword = networkPreferences.flareSolverrPassword.get(),
            sentInTheClear = flareSolverrUrl.isNotBlank() && !isPrivateChannel(flareSolverrUrl),
            onConfirm = { username, password ->
                networkPreferences.flareSolverrUsername.set(username)
                networkPreferences.flareSolverrPassword.set(password)
                showFlareSolverrLogin = false
            },
            onDismissRequest = { showFlareSolverrLogin = false },
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

    return listOf(
        Preference.PreferenceItem.SwitchPreference(
            preference = networkPreferences.enableTurnstileSolver,
            title = stringResource(MR.strings.pref_enable_turnstile_solver),
            subtitle = stringResource(MR.strings.pref_enable_turnstile_solver_summary),
        ),
        Preference.PreferenceItem.SwitchPreference(
            preference = networkPreferences.enableTurnstileBackgroundSolver,
            title = stringResource(MR.strings.pref_enable_turnstile_background_solver),
            subtitle = stringResource(MR.strings.pref_enable_turnstile_background_solver_summary),
            visible = turnstileSolverEnabled,
        ),
        // Spike instrumentation, debug builds only.
        Preference.PreferenceItem.TextPreference(
            title = "Turnstile: library update in 60s (spike)",
            subtitle = "Queues a manual update after a delay. Kill the app during it, and the " +
                "job starts a process with no activity, which is the solver's real no-window trigger",
            visible = BuildConfig.DEBUG,
            onClick = {
                LibraryUpdateWorker.startDelayed(context.workManager, delaySeconds = 60)
                context.toast("Library update queued for 60s, kill the app now")
            },
        ),
        Preference.PreferenceItem.TextPreference(
            title = "Turnstile: force the no-isolated-world path (spike)",
            subtitle = "Currently ${if (forceNoWatchSolver) "on" else "off"}. Solves as if the " +
                "WebView were too old for an isolated world, on events alone with no probe. Resets on restart",
            visible = BuildConfig.DEBUG,
            onClick = {
                forceNoWatchSolver = !forceNoWatchSolver
                TurnstileSolver.forceNoWatch = forceNoWatchSolver
            },
        ),
        Preference.PreferenceItem.TextPreference(
            title = "Turnstile: force the no-window path (spike)",
            subtitle = "Currently ${if (forceHeadlessSolver) "on" else "off"}. Solves as if no " +
                "app screen were open, which otherwise only a scheduled update reaches. Resets on restart",
            visible = BuildConfig.DEBUG,
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
            visible = flareSolverrEnabled,
            onValueChanged = {
                when {
                    it.trim().toHttpUrlOrNull() == null -> {
                        context.toast(MR.strings.error_flaresolverr_invalid_url)
                        false
                    }
                    // Credentials in the address authenticate nothing and would travel in
                    // every backup, since this key is not private. The sign-in row keeps them private.
                    splitFlareSolverrUserInfo(it) != null -> {
                        context.toast(MR.strings.error_flaresolverr_url_credentials)
                        false
                    }
                    else -> true
                }
            },
        ),
        resetToDefaultPreference(
            preference = networkPreferences.flareSolverrUrl,
            current = flareSolverrUrl,
            title = stringResource(MR.strings.pref_clear_flaresolverr_url),
            visible = flareSolverrEnabled,
        ),
        Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_flaresolverr_login),
            // The username identifies the row; the password is never rendered, since a
            // preference subtitle prints its value at up to ten lines.
            subtitle = when {
                flareSolverrUsername.isNotBlank() -> flareSolverrUsername
                flareSolverrPassword.isNotEmpty() ->
                    stringResource(MR.strings.pref_flaresolverr_login_password_only)
                else -> stringResource(MR.strings.pref_flaresolverr_login_summary)
            },
            visible = flareSolverrEnabled,
            onClick = { showFlareSolverrLogin = true },
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
            // Stays shown while a test runs, since this DSL has no disabled state; a tap then does nothing.
            visible = flareSolverrEnabled,
            onClick = {
                val url = networkPreferences.flareSolverrUrl.get().trim()
                if (flareSolverrTesting) {
                    // One test at a time.
                } else if (url.isBlank()) {
                    context.toast(MR.strings.error_flaresolverr_invalid_url)
                } else {
                    scope.launch {
                        // The solver's agent is deliberately not stored as the app default: every
                        // WebView would then announce FlareSolverr's desktop browser while running
                        // as Android WebView, and Cloudflare re-challenges that mismatch. The pin in
                        // FlareSolverrUserAgentPin.kt covers each solved host, which is what
                        // cf_clearance is actually bound to.
                        flareSolverrTesting = true
                        val result = networkHelper.flareSolverr.test(url)
                        flareSolverrTesting = false
                        flareSolverrTestResult = result
                        flareSolverrTestFailure = result as? FlareSolverrTestResult.Failure
                    }
                }
            },
        ),
    )
}

// The solver reports a case rather than a sentence, so the screen names it in the reader's language.
// The server's own text still reaches them through the failure dialog.
private fun FlareSolverrTestFailure.stringRes(): StringResource = when (this) {
    FlareSolverrTestFailure.AUTH_REQUIRED -> MR.strings.flaresolverr_test_error_auth
    FlareSolverrTestFailure.FORBIDDEN -> MR.strings.flaresolverr_test_error_forbidden
    FlareSolverrTestFailure.NOT_FOUND -> MR.strings.flaresolverr_test_error_not_found
    FlareSolverrTestFailure.SOLVER_DOWN -> MR.strings.flaresolverr_test_error_solver_down
    FlareSolverrTestFailure.HTTP_ERROR -> MR.strings.flaresolverr_test_error_http
    FlareSolverrTestFailure.UNREACHABLE -> MR.strings.flaresolverr_test_error_unreachable
    FlareSolverrTestFailure.TIMED_OUT -> MR.strings.flaresolverr_test_error_timed_out
    FlareSolverrTestFailure.NOT_A_SOLVER -> MR.strings.flaresolverr_test_error_not_solver
    FlareSolverrTestFailure.SOLVE_FAILED -> MR.strings.flaresolverr_test_error_solve
    FlareSolverrTestFailure.LOGIN_NOT_PRIVATE -> MR.strings.flaresolverr_login_not_private
    FlareSolverrTestFailure.REDIRECTED -> MR.strings.flaresolverr_test_error_redirected
}
