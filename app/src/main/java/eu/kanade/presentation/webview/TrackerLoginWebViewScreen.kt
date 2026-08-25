package eu.kanade.presentation.webview

import android.webkit.WebView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
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
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import tachiyomi.presentation.core.components.material.Scaffold
import com.kevinnzou.web.WebView as ComposeWebView

/**
 * Generic sign-in WebView for a [eu.kanade.tachiyomi.data.track.CookieLoginTracker]: it only shows
 * the service's own login page and reports each finished page, leaving the caller to decide when a
 * usable cookie has appeared. Unlike the ExHentai login it clears no cookies on entry, so a user
 * already signed in on that site is recognised without typing anything.
 */
@Composable
fun TrackerLoginWebViewScreen(
    title: String,
    url: String,
    onUp: () -> Unit,
    onPageFinished: (url: String) -> Unit,
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

        ComposeWebView(
            state = state,
            navigator = navigator,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            onCreated = { it.setDefaultSettings() },
            client = webClient,
        )
    }
}
