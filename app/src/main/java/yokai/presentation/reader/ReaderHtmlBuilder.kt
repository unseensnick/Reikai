package yokai.presentation.reader

import org.json.JSONArray
import org.json.JSONObject

/** Asset root for the bundled LNReader web layer (CSS/JS copied verbatim from lnreader-main). */
private const val ASSET_BASE = "file:///android_asset/lnreader-web"

/** Material theme colors the LNReader stylesheet expects as `--theme-*` CSS variables. Built from
 *  the app's `MaterialTheme.colorScheme` at the call site so the reader chrome (scrollbar, links,
 *  selection) matches the app theme. */
data class ReaderThemeColors(
    val primary: String,
    val onPrimary: String,
    val secondary: String,
    val onSecondary: String,
    val tertiary: String,
    val onTertiary: String,
    val surface: String,
    val surface09: String,
    val onSurface: String,
    val surfaceVariant: String,
    val onSurfaceVariant: String,
    val outline: String,
    val rippleColor: String,
)

/**
 * Build the LNReader reader document for a single chapter, mirroring lnreader-main's
 * `WebViewReader.tsx` template: the `#LNReader-chapter` scaffold, the `:root` settings/theme CSS
 * variables, the injected `initialReaderConfig`/`initialPageReaderConfig`, and the bundled CSS/JS.
 *
 * The native bridge differs from upstream: upstream relies on react-native-webview's
 * `window.ReactNativeWebView.postMessage`; here a tiny shim forwards that to an Android
 * `@JavascriptInterface` named `NativeReader`, so the vendored JS stays byte-identical to upstream.
 *
 * Phase 1.3 hardwires sane general settings (scroll mode, no TTS/page-reader/gestures); the real
 * settings surface lands in 1.4.
 */
fun buildReaderHtml(
    chapterHtml: String,
    chapterName: String,
    progressPercent: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    settings: ReaderSettings,
    colors: ReaderThemeColors,
    statusBarHeightPx: Int,
    debug: Boolean,
): String {
    val readerSettings = readerSettingsJson(settings)

    val generalSettings = JSONObject().apply {
        put("keepScreenOn", true)
        put("fullScreenMode", true)
        put("pageReader", false)
        put("swipeGestures", false)
        put("showScrollPercentage", true)
        put("useVolumeButtons", false)
        put("volumeButtonsOffset", JSONObject.NULL)
        put("showBatteryAndTime", false)
        put("autoScroll", false)
        put("autoScrollInterval", 10)
        put("autoScrollOffset", JSONObject.NULL)
        put("verticalSeekbar", true)
        put("removeExtraParagraphSpacing", false)
        put("bionicReading", false)
        put("tapToScroll", false)
        put("TTSEnable", false)
    }

    val config = JSONObject().apply {
        put("readerSettings", readerSettings)
        put("chapterGeneralSettings", generalSettings)
        put("novel", JSONObject.NULL)
        put("chapter", JSONObject().apply { put("name", chapterName); put("progress", progressPercent) })
        // Non-null enables the web layer's chapter-ending "Next" button / prev affordance; the name
        // is unused in scroll mode (only the page-reader transition shows it).
        put("nextChapter", if (hasNext) JSONObject().apply { put("name", "") } else JSONObject.NULL)
        put("prevChapter", if (hasPrev) JSONObject().apply { put("name", "") } else JSONObject.NULL)
        put("batteryLevel", 1.0)
        put("autoSaveInterval", 2222)
        put("DEBUG", debug)
        put(
            "strings",
            JSONObject().apply {
                put("finished", "Finished: ${chapterName.trim()}")
                put("nextChapter", "Next chapter")
                put("noNextChapter", "No next chapter")
            },
        )
    }

    val pageConfig = JSONObject().apply { put("nextChapterScreenVisible", false) }

    return """
        <!DOCTYPE html>
        <html dir="ltr">
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
        <link rel="stylesheet" href="$ASSET_BASE/css/index.css">
        <link rel="stylesheet" href="$ASSET_BASE/css/pageReader.css">
        <link rel="stylesheet" href="$ASSET_BASE/css/toolWrapper.css">
        <link rel="stylesheet" href="$ASSET_BASE/css/tts.css">
        <style>
        :root {
          --StatusBar-currentHeight: ${statusBarHeightPx}px;
          --readerSettings-theme: ${settings.backgroundColor};
          --readerSettings-padding: ${settings.padding}px;
          --readerSettings-textSize: ${settings.fontSize}px;
          --readerSettings-textColor: ${settings.textColor};
          --readerSettings-textAlign: ${settings.textAlign};
          --readerSettings-lineHeight: ${settings.lineHeight};
          --readerSettings-fontFamily: ${settings.fontFamily};
          --theme-primary: ${colors.primary};
          --theme-onPrimary: ${colors.onPrimary};
          --theme-secondary: ${colors.secondary};
          --theme-tertiary: ${colors.tertiary};
          --theme-onTertiary: ${colors.onTertiary};
          --theme-onSecondary: ${colors.onSecondary};
          --theme-surface: ${colors.surface};
          --theme-surface-0-9: ${colors.surface09};
          --theme-onSurface: ${colors.onSurface};
          --theme-surfaceVariant: ${colors.surfaceVariant};
          --theme-onSurfaceVariant: ${colors.onSurfaceVariant};
          --theme-outline: ${colors.outline};
          --theme-rippleColor: ${colors.rippleColor};
        }
        </style>
        </head>
        <body>
        <div class="transition-chapter" style="display: none">${escapeHtml(chapterName)}</div>
        <div id="LNReader-chapter">$chapterHtml</div>
        <div id="reader-ui"></div>
        </body>
        <script>
        window.ReactNativeWebView = { postMessage: function (m) { NativeReader.postMessage(m); } };
        var initialPageReaderConfig = $pageConfig;
        var initialReaderConfig = $config;
        </script>
        <script src="$ASSET_BASE/js/polyfill-onscrollend.js"></script>
        <script src="$ASSET_BASE/js/icons.js"></script>
        <script src="$ASSET_BASE/js/van.js"></script>
        <script src="$ASSET_BASE/js/text-vibe.js"></script>
        <script src="$ASSET_BASE/js/core.js"></script>
        <script src="$ASSET_BASE/js/index.js"></script>
        </html>
    """.trimIndent()
}

/**
 * The LNReader `readerSettings` object the web layer reads. Used both for the initial
 * [buildReaderHtml] config and for live updates pushed via `reader.readerSettings.val = ...`, so the
 * two stay in sync.
 */
fun readerSettingsJson(settings: ReaderSettings): JSONObject = JSONObject().apply {
    put("theme", settings.backgroundColor)
    put("textColor", settings.textColor)
    put("textSize", settings.fontSize)
    put("textAlign", settings.textAlign)
    put("padding", settings.padding)
    put("fontFamily", settings.fontFamily)
    put("lineHeight", settings.lineHeight.toDouble())
    put("customCSS", "")
    put("customJS", "")
    put("customThemes", JSONArray())
    put(
        "tts",
        JSONObject().apply {
            put("rate", 1)
            put("pitch", 1)
            put("autoPageAdvance", false)
            put("scrollToTop", true)
        },
    )
    put("epubLocation", "")
    put("epubUseAppTheme", false)
    put("epubUseCustomCSS", false)
    put("epubUseCustomJS", false)
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
