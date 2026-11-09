package eu.kanade.tachiyomi.ui.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.webview.WebViewScreenContent
import reikai.presentation.webview.rememberNovelPageActions
import reikai.presentation.webview.rememberPluginStorageCapture
import tachiyomi.presentation.core.screens.LoadingScreen

class WebViewScreen(
    private val url: String,
    private val initialTitle: String? = null,
    private val sourceId: Long? = null,
    // RK: a novel's page, which a source taking pages can read the novel's details and chapters from
    private val novelId: Long? = null,
    // RK: a novel source's text id, so the page opens with that source's headers
    private val novelSourceId: String? = null,
    // RK: a light-novel plugin whose site this is; its storage is kept for it if it is installed and asks,
    //     as LNReader's WebView does
    private val pluginId: String? = null,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<WebViewViewModel, WebViewViewModel.Factory> {
            // RK: a plugin's source id is its plugin id, so its site opens under the agent its fetches send
            create(sourceId = sourceId, url = url, novelSourceId = novelSourceId ?: pluginId)
        }

        val headers by viewModel.headers.collectAsState()
        if (headers == null) {
            LoadingScreen()
            return
        }

        WebViewScreenContent(
            onNavigateUp = { navigator.pop() },
            initialTitle = initialTitle,
            url = url,
            headers = headers.orEmpty(),
            defaultUserAgentProvider = viewModel::defaultUserAgentProvider,
            onUrlChange = { assistUrl = it },
            onShare = { viewModel.shareWebpage(context, it) },
            onOpenInBrowser = { viewModel.openInBrowser(context, it) },
            onClearCookies = viewModel::clearCookies,
            pageActions = rememberNovelPageActions(novelId), // RK
            onPageFinished = rememberPluginStorageCapture(pluginId, novelSourceId), // RK
        )
    }
}
