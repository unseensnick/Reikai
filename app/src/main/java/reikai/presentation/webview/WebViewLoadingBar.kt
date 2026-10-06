package reikai.presentation.webview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kevinnzou.web.LoadingState

/**
 * The page-load bar along the bottom of a sign-in browser's top bar. It eases between progress values
 * as Komikku's login screen does, where Mihon's WebView, left in upstream's shape, jumps.
 */
@Composable
fun BoxScope.WebViewLoadingBar(loadingState: LoadingState) {
    val modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.BottomCenter)
    when (loadingState) {
        is LoadingState.Initializing -> LinearProgressIndicator(modifier = modifier)
        is LoadingState.Loading -> {
            val animatedProgress by animateFloatAsState(
                loadingState.progress,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "webview_loading",
            )
            LinearProgressIndicator(progress = { animatedProgress }, modifier = modifier)
        }
        else -> {}
    }
}
