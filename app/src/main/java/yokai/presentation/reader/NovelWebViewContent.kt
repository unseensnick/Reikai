package yokai.presentation.reader

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.util.system.setDefaultSettings

/**
 * Renders a novel chapter's HTML in a raw [WebView] with minimal default styling (Phase 1.2). The
 * LNReader web assets (CSS/JS, theming, scroll bridge) arrive in 1.3+. A single tap anywhere fires
 * [onTap] (to toggle the reader chrome) while leaving scrolling and text selection to the WebView.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun NovelWebViewContent(
    html: String,
    baseUrl: String?,
    fontSize: Int,
    lineSpacing: Float,
    theme: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val (bg, fg) = readerWebColors(theme, systemDark)
    val document = remember(html, fontSize, lineSpacing, bg, fg) {
        wrapChapterHtml(html, fontSize, lineSpacing, bg, fg)
    }

    // Latest onTap read at gesture time so the listener (attached once) never goes stale.
    val onTapState = rememberUpdatedState(onTap)
    val webView = remember {
        WebView(context).apply {
            setDefaultSettings()
            val gestureDetector = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        onTapState.value()
                        return false
                    }
                },
            )
            // Return false: feed the event to the detector but let the WebView keep handling
            // scroll, links, and text selection.
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(document, baseUrl) {
        webView.setBackgroundColor(AndroidColor.parseColor(bg))
        webView.loadDataWithBaseURL(baseUrl, document, "text/html", "UTF-8", null)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

/** Reader background / foreground as CSS hex strings, mirroring the plain-text reader's color map. */
private fun readerWebColors(theme: Int, systemDark: Boolean): Pair<String, String> = when (theme) {
    1 -> "#ffffff" to "#000000"
    2 -> "#101010" to "#e0e0e0"
    else -> if (systemDark) "#101010" to "#e0e0e0" else "#ffffff" to "#000000"
}

/** Wrap raw chapter HTML in a minimal, mobile-readable document. Replaced by the bundled LNReader
 *  stylesheet in 1.3; this is the Phase 1.2 default look. */
private fun wrapChapterHtml(body: String, fontSize: Int, lineSpacing: Float, bg: String, fg: String): String =
    """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
    <style>
      html, body { margin: 0; padding: 0; background: $bg; color: $fg; }
      body {
        padding: 16px 16px 72px;
        font-size: ${fontSize}px;
        line-height: $lineSpacing;
        font-family: sans-serif;
        word-wrap: break-word;
        overflow-wrap: break-word;
      }
      img { max-width: 100%; height: auto; }
      a { color: #4ea1ff; }
    </style>
    </head>
    <body>$body</body>
    </html>
    """.trimIndent()
