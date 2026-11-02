package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import androidx.annotation.ColorInt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.reader.ReaderError
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import mihon.app.di.appGraph

/**
 * [ReaderError] for a [page] that failed with [error]: Retry asks its loader again, and an image from the web can
 * be opened in a WebView with the cookies of the source [sourceId].
 */
@Composable
fun ReaderPageError(
    page: ReaderPage,
    error: Throwable?,
    sourceId: Long?,
    modifier: Modifier = Modifier,
    onButtonPressChange: ((pressed: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val imageUrl = page.imageUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
    ReaderError(
        message = with(context) { error?.formattedMessage },
        onRetry = { page.chapter.pageLoader?.retryPage(page) },
        onOpenInWebView = imageUrl?.let {
            { context.startActivity(WebViewActivity.newIntent(context, it, sourceId)) }
        },
        modifier = modifier,
        onButtonPressChange = onButtonPressChange,
    )
}

/**
 * [ReaderPageError] for the view-based viewers, filling the view, in the theme of its reader-themed [context].
 */
// RK: split to take seedColor, as the transition and progress views do
class ReaderPageErrorView(
    context: Context,
    @ColorInt private val seedColor: Int? = null,
) : AbstractComposeView(context) {

    private var data by mutableStateOf<Data?>(null)

    fun bind(
        page: ReaderPage,
        error: Throwable?,
        sourceId: Long?,
        onButtonPressChange: ((pressed: Boolean) -> Unit)? = null,
    ) {
        data = Data(page, error, sourceId, onButtonPressChange)
    }

    @Composable
    override fun Content() {
        val data = data ?: return
        // RK: tint the error page from the cover color when enabled
        val themeCoverBased = remember { context.appGraph.uiPreferences.themeCoverBased.get() }
        ReaderThemedContent(context, seedColor?.let { Color(it) }.takeIf { themeCoverBased }) {
            ReaderPageError(
                page = data.page,
                error = data.error,
                sourceId = data.sourceId,
                modifier = Modifier.fillMaxSize(),
                onButtonPressChange = data.onButtonPressChange,
            )
        }
    }

    private data class Data(
        val page: ReaderPage,
        val error: Throwable?,
        val sourceId: Long?,
        val onButtonPressChange: ((pressed: Boolean) -> Unit)?,
    )
}
