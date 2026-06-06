package yokai.presentation.reader

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.setDefaultSettings

/**
 * Renders a novel chapter in a WebView using the bundled LNReader web layer (Phase 1.3): typography,
 * theme, and the side scrollbar come from the vendored CSS/JS. A center tap posts a `hide` message
 * over the native bridge, which fires [onToggleMenu] to toggle the reader chrome (replacing the
 * Compose-side tap detection from 1.2).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NovelWebViewContent(
    html: String,
    baseUrl: String?,
    fontSize: Int,
    lineSpacing: Float,
    theme: Int,
    chapterTitle: String,
    onToggleMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val (background, textColor) = readerWebColors(theme, systemDark)
    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) {
        ReaderThemeColors(
            primary = colorScheme.primary.toCssHex(),
            onPrimary = colorScheme.onPrimary.toCssHex(),
            secondary = colorScheme.secondary.toCssHex(),
            onSecondary = colorScheme.onSecondary.toCssHex(),
            tertiary = colorScheme.tertiary.toCssHex(),
            onTertiary = colorScheme.onTertiary.toCssHex(),
            surface = colorScheme.surface.toCssHex(),
            surface09 = colorScheme.surface.toCssRgba(0.9f),
            onSurface = colorScheme.onSurface.toCssHex(),
            surfaceVariant = colorScheme.surfaceVariant.toCssHex(),
            onSurfaceVariant = colorScheme.onSurfaceVariant.toCssHex(),
            outline = colorScheme.outline.toCssHex(),
            rippleColor = colorScheme.onSurface.toCssHex(),
        )
    }

    val document = remember(html, fontSize, lineSpacing, background, textColor, themeColors, chapterTitle) {
        buildReaderHtml(
            chapterHtml = html,
            chapterName = chapterTitle,
            fontSize = fontSize,
            lineSpacing = lineSpacing,
            background = background,
            textColor = textColor,
            padding = DEFAULT_PADDING,
            textAlign = DEFAULT_TEXT_ALIGN,
            fontFamily = "",
            colors = themeColors,
            statusBarHeightPx = 0,
            debug = BuildConfig.DEBUG,
        )
    }

    val onToggle = rememberUpdatedState(onToggleMenu)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val webView = remember {
        WebView(context).apply {
            setDefaultSettings()
            settings.allowFileAccess = true // file:///android_asset bundled CSS/JS
            addJavascriptInterface(
                ReaderWebInterface(
                    onHide = { mainHandler.post { onToggle.value() } },
                    onConsole = { msg -> if (BuildConfig.DEBUG) Logger.d("NovelReaderWeb") { msg } },
                ),
                "NativeReader",
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(document, baseUrl) {
        webView.loadDataWithBaseURL(baseUrl, document, "text/html", "UTF-8", null)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private const val DEFAULT_PADDING = 16
private const val DEFAULT_TEXT_ALIGN = "left"

/** Reader background / foreground as CSS hex strings, mirroring the plain-text reader's color map. */
private fun readerWebColors(theme: Int, systemDark: Boolean): Pair<String, String> = when (theme) {
    1 -> "#ffffff" to "#000000"
    2 -> "#101010" to "#e0e0e0"
    else -> if (systemDark) "#101010" to "#e0e0e0" else "#ffffff" to "#000000"
}

private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

private fun Color.toCssRgba(alpha: Float): String {
    val argb = toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r, $g, $b, $alpha)"
}
