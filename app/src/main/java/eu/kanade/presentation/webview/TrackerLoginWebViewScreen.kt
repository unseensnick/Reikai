package eu.kanade.presentation.webview

import android.webkit.WebView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.rememberWebViewNavigator
import com.kevinnzou.web.rememberWebViewState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import com.kevinnzou.web.WebView as ComposeWebView

/**
 * Generic sign-in WebView for a [eu.kanade.tachiyomi.data.track.CookieLoginTracker].
 *
 * The check button is the reliable way to finish, not a convenience: these sites route client-side
 * after a sign-in, so `onPageFinished` may never fire again and an automatic capture alone would
 * leave the user re-entering the screen to be noticed. [onPageFinished] still fires so a session
 * that does land on a page load is picked up without a tap.
 *
 * Cookies are deliberately not cleared on entry, so someone already signed in is recognised at once.
 */
@Composable
fun TrackerLoginWebViewScreen(
    title: String,
    url: String,
    defaultUserAgentProvider: () -> String,
    onUp: () -> Unit,
    onPageFinished: (url: String) -> Unit,
    onConfirmLogin: () -> Unit,
) {
    val state = rememberWebViewState(url = url)
    val navigator = rememberWebViewNavigator()

    Scaffold(
        topBar = {
            Box {
                AppBar(
                    title = title,
                    navigateUp = onUp,
                    navigationIcon = Icons.Outlined.Close,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_webview_refresh),
                                    icon = Icons.Outlined.Refresh,
                                    onClick = { navigator.reload() },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.login_webview_confirm),
                                    icon = Icons.Outlined.Check,
                                    onClick = onConfirmLogin,
                                ),
                            ),
                        )
                    },
                )
                when (val loadingState = state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    is LoadingState.Loading -> {
                        val animatedProgress by animateFloatAsState(
                            loadingState.progress,
                            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                            label = "tracker_login_loading",
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        )
                    }
                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        val webClient = remember {
            object : AccompanistWebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    onPageFinished(url ?: return)
                }
            }
        }

        Column(modifier = Modifier.padding(contentPadding)) {
            ComposeWebView(
                state = state,
                navigator = navigator,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onCreated = {
                    it.setDefaultSettings()
                    // Must match what OkHttp sends: a Cloudflare clearance cookie is bound to the agent
                    // that earned it, so a sign-in solved here would be rejected on every later request.
                    it.setUserAgent(defaultUserAgentProvider())
                },
                client = webClient,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = stringResource(MR.strings.login_webview_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.padding.medium),
                )
            }
        }
    }
}
