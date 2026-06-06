package yokai.presentation.reader

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
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
 * Renders a novel chapter in a WebView using the bundled LNReader web layer. Typography, theme, and
 * the side scrollbar come from the vendored CSS/JS. A center tap posts a `hide` message over the
 * native bridge, firing [onToggleMenu] to toggle the reader chrome.
 *
 * [settings] changes are pushed live via `reader.readerSettings.val` (no reload), so font, spacing,
 * alignment, padding, and theme update in place. The document is rebuilt only when the chapter [html]
 * or the app [MaterialTheme] colors change.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NovelWebViewContent(
    html: String,
    baseUrl: String?,
    settings: ReaderSettings,
    chapterTitle: String,
    initialProgressPercent: Int,
    onToggleMenu: () -> Unit,
    onSaveProgress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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

    // Keyed on chapter + app theme only (NOT settings): settings changes push live instead of
    // reloading. The initial document bakes in the settings present at build time.
    val currentSettings = rememberUpdatedState(settings)
    val document = remember(html, themeColors, chapterTitle, initialProgressPercent) {
        buildReaderHtml(
            chapterHtml = html,
            chapterName = chapterTitle,
            progressPercent = initialProgressPercent,
            settings = currentSettings.value,
            colors = themeColors,
            statusBarHeightPx = 0,
            debug = BuildConfig.DEBUG,
        )
    }

    val onToggle = rememberUpdatedState(onToggleMenu)
    val onSave = rememberUpdatedState(onSaveProgress)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val webView = remember {
        WebView(context).apply {
            setDefaultSettings()
            this.settings.allowFileAccess = true // file:///android_asset bundled CSS/JS
            addJavascriptInterface(
                ReaderWebInterface(
                    onHide = { mainHandler.post { onToggle.value() } },
                    onConsole = { msg -> if (BuildConfig.DEBUG) Logger.d("NovelReaderWeb") { msg } },
                    onSave = { percent -> onSave.value(percent) },
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

    // Push settings live once the page is up (guarded so it no-ops before the reader exists).
    LaunchedEffect(settings) {
        val json = readerSettingsJson(settings).toString()
        webView.evaluateJavascript(
            "if (window.reader) { reader.readerSettings.val = $json; }",
            null,
        )
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

private fun Color.toCssRgba(alpha: Float): String {
    val argb = toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r, $g, $b, $alpha)"
}
