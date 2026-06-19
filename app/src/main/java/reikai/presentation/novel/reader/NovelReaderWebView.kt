package reikai.presentation.novel.reader

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlin.math.roundToInt
import logcat.logcat

/**
 * Renders a novel chapter's text in a WebView using the bundled `index.css` + `core.js` (typography,
 * the scroll-save + center-tap bridge, font loading). This is the reader's text canvas ONLY, the
 * toolbar / prev-next / settings chrome is Compose ([NovelReaderScreen]). A center tap posts a `hide`
 * message over the native bridge, firing [onToggleMenu].
 *
 * [settings] changes are pushed live via `reader.readerSettings.val` (no reload), so font, spacing,
 * alignment, padding, and theme update in place. The document is rebuilt only when the chapter [html]
 * or the app [MaterialTheme] colors change.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NovelReaderWebView(
    html: String,
    baseUrl: String?,
    settings: NovelReaderSettings,
    chapterTitle: String,
    initialProgressPercent: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onToggleMenu: () -> Unit,
    onSaveProgress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Top padding so the first line clears the display cutout (the punch-hole otherwise overlaps text
    // in immersive mode). The WebView viewport is initial-scale=1, so its CSS px == dp: convert the
    // cutout inset from device px to dp. It's constant across the chrome show/hide toggle, so it
    // won't trigger a WebView reload.
    val density = LocalDensity.current
    val topInsetDp = with(density) { WindowInsets.displayCutout.getTop(density).toDp() }.value.roundToInt()
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
    val document = remember(html, themeColors, chapterTitle, initialProgressPercent, hasPrev, hasNext, topInsetDp) {
        buildReaderHtml(
            chapterHtml = html,
            chapterName = chapterTitle,
            progressPercent = initialProgressPercent,
            hasPrev = hasPrev,
            hasNext = hasNext,
            settings = currentSettings.value,
            colors = themeColors,
            statusBarHeightPx = topInsetDp,
            debug = BuildConfig.DEBUG,
        )
    }

    val onToggle = rememberUpdatedState(onToggleMenu)
    val onSave = rememberUpdatedState(onSaveProgress)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val webView = remember {
        WebView(context).apply {
            setDefaultSettings()
            // file:///android_asset bundled CSS/JS + fonts. The dangerous universal/file-from-file
            // access flags stay off (security): the chapter HTML is loaded over an http base URL.
            this.settings.allowFileAccess = true
            addJavascriptInterface(
                NovelReaderWebInterface(
                    onHide = { mainHandler.post { onToggle.value() } },
                    onConsole = { msg -> if (BuildConfig.DEBUG) logcat { msg } },
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
