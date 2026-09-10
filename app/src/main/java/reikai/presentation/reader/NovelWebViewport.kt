package reikai.presentation.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import org.json.JSONObject
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.fraction
import reikai.presentation.novel.reader.NovelChapterNavigationClient
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.reader.web.NovelWebBridge
import reikai.presentation.reader.web.NovelWebDocument
import kotlin.math.roundToInt

/**
 * The light-novel adapter under [ReaderViewport], rendering chapters into a document that is
 * Reikai's own: its stylesheet and engine come from `assets/novel-web/`, not the vendored bundle.
 *
 * The volume-key preferences arrive as values rather than a preferences class, so the viewport is
 * constructible without the graph. They are read once at construction and nothing rebuilds the
 * viewport, so a mid-session change to them takes effect on the next open.
 */
@SuppressLint("SetJavaScriptEnabled")
class NovelWebViewport(
    private val context: Context,
    private val volumeKeysEnabled: Boolean,
    private val volumeKeysInverted: Boolean,
    private val volumeKeyScrollFraction: Float,
    /** Named with its chapter, matching the native viewport, so the model never has to assume which
     *  chapter a percentage belongs to. */
    private val onProgressChanged: (chapterId: Long, percent: Int) -> Unit,
    private val onProgressSettled: (chapterId: Long, percent: Int) -> Unit,
    private val onToggleMenu: () -> Unit,
    /** Swipe-between-chapters, forward or back. */
    private val onStepChapter: (forward: Boolean) -> Unit,
    /** Which chapter the reader is actually in, which stops being the loaded one once a window grows. */
    private val onVisibleChapter: (chapterId: Long) -> Unit,
    /** Read per load rather than once: the cutout inset is only known after the window has one. */
    private val statusBarHeightPx: () -> Int,
) : ReaderViewport, TextViewport {

    /** The chapter the document was built around, so a load can be told apart from a re-entry. */
    private var loadedChapterId: Long? = null

    /** The document URL the chapter was loaded as, so the navigation policy can tell a footnote jump
     *  from the chapter trying to leave. */
    private var loadedBaseUrl: String? = null

    /** The last auto-scroll state the host asked for, so a freshly built document can be given it. */
    private var autoScrollRunning = false
    private var autoScrollPixelsPerFrame = 0f

    // Bridge messages arrive on a WebView background thread, so UI-affecting callbacks marshal here.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val webView = ProgressWebView(context).apply {
        setDefaultSettings()
        webViewClient = NovelChapterNavigationClient(context) { loadedBaseUrl }
        // The stylesheet and engine are inlined into the document, so unlike the legacy reader this
        // mode needs no file origin at all and the flag stays off.
        settings.allowFileAccess = false
        addJavascriptInterface(
            NovelWebBridge(
                onVisibleChapter = { id -> mainHandler.post { onVisibleChapter(id) } },
                onProgress = { id, f -> mainHandler.post { onProgressChanged(id, f.toPercent()) } },
                // Persisted rather than drawn, so it does not need the main thread to be correct.
                onProgressSettled = { id, f -> onProgressSettled(id, f.toPercent()) },
                onReachedEnd = { },
                onReachedStart = { },
                onToggleMenu = { mainHandler.post { onToggleMenu() } },
                onStepChapter = { forward -> mainHandler.post { onStepChapter(forward) } },
                // Auto-scroll is a call into the document, so one that was not up yet dropped it.
                onReady = { mainHandler.post { pushAutoScroll() } },
            ),
            NovelWebBridge.NAME,
        )
    }

    override val view: View
        get() = webView

    // A novel chapter is one vertically scrolling document, so there is no right-to-left shape to report.
    override val isRtl: Boolean
        get() = false

    // Scrolled natively rather than through JS, so the thumb and the text move together while the rail
    // is being dragged. A paged progress is not this medium's unit and is ignored.
    override fun seekTo(progress: ChapterProgress) {
        if (progress !is ChapterProgress.Percent) return
        webView.scrollTo(0, (webView.maxScroll * progress.fraction).roundToInt())
    }

    // Nothing to do: a step reloads the document, which starts at that chapter's own stored position.
    override fun onChapterStepped() = Unit

    override fun destroy() {
        webView.stopLoading()
        // The bridge captures the host and is called off the main thread, so drop it before teardown.
        // destroy() on an attached WebView is undefined and pins the hierarchy, hence the detach.
        webView.removeJavascriptInterface(NovelWebBridge.NAME)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!volumeKeysEnabled || !isVolumeKey) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val forward = (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != volumeKeysInverted
            val fraction = volumeKeyScrollFraction.coerceIn(0.1f, 1f)
            scrollByFraction(if (forward) fraction else -fraction)
        }
        // Consume the key-up too, so the system volume UI never shows during a press.
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * The document is assembled here rather than by the host, because it is this renderer's own
     * format: the cutout inset is a custom property only this document has, and the build itself is
     * off the main thread because a downloaded chapter has its images inlined and the string runs to
     * megabytes.
     */
    override suspend fun load(
        chapter: NovelReaderViewModel.LoadedChapter,
        hasPrevious: Boolean,
        hasNext: Boolean,
        settings: NovelReaderSettings,
    ) {
        loadedChapterId = chapter.chapterId
        val statusBarPx = statusBarHeightPx()
        // Resolving a user font copies it out of the user's storage folder on first use, which is
        // disk work over SAF, so it happens off the main thread with the document build rather than
        // in front of it.
        val fonts = context.appGraph.novelFontManager
        val fontUrl = withContext(Dispatchers.IO) { fonts.webUrl(settings.fontFamily) }
        val html = withContext(Dispatchers.Default) {
            NovelWebDocument.build(
                context = context,
                chapterId = chapter.chapterId,
                chapterHtml = chapter.html,
                // Carried into the document rather than scrolled to afterwards, because the page has
                // to exist before it has anywhere to scroll and the load is asynchronous.
                initialFraction = chapter.progressPercent / 100f,
                settings = settings,
                statusBarHeightPx = statusBarPx,
                customFontUrl = fontUrl,
            )
        }
        // Only trust an http(s) base URL. The plugin controls the site URL, and a file:// base would
        // hand the chapter document a file origin.
        val safeBaseUrl = chapter.baseUrl
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        loadedBaseUrl = safeBaseUrl
        webView.loadDataWithBaseURL(safeBaseUrl, html, "text/html", "UTF-8", null)
    }

    /**
     * Pushes changed display settings into the live document, so a size or colour change reflows in
     * place rather than waiting for the next chapter. The custom properties are rewritten wholesale,
     * since the stylesheet reads them and nothing else has to be told; only what CSS cannot express
     * goes to the page's own settings object.
     */
    override fun applySettings(settings: NovelReaderSettings) {
        val variables = NovelWebDocument.variables(settings, statusBarHeightPx())
        val behaviour = NovelWebDocument.behaviourJson(settings).toString()
        val script = buildString {
            append("document.documentElement.setAttribute('style', ")
            append(JSONObject.quote(variables))
            append("); if (window.rkReader) rkReader.setSettings(").append(behaviour).append(");")
        }
        webView.evaluateJavascript(script, null)
    }

    /**
     * Auto-scroll, run by the page. The values are held because the document is rebuilt on every
     * chapter and starts with the scroller stopped, so they are pushed again from the page's ready
     * report rather than only when the host changes them.
     */
    override fun setAutoScroll(running: Boolean, pixelsPerFrame: Float) {
        autoScrollRunning = running
        autoScrollPixelsPerFrame = pixelsPerFrame
        pushAutoScroll()
    }

    private fun pushAutoScroll() {
        val js = if (autoScrollRunning) {
            "if (window.rkReader) rkReader.autoScrollStart($autoScrollPixelsPerFrame);"
        } else {
            "if (window.rkReader) rkReader.autoScrollStop();"
        }
        webView.evaluateJavascript(js, null)
    }

    /** Smooth-scrolls the viewport by a signed fraction (positive = forward), reusing the WebView's own
     *  smooth scroll so a volume press feels like tap-to-scroll. */
    private fun scrollByFraction(fraction: Float) {
        webView.evaluateJavascript(
            "window.scrollBy({ top: window.innerHeight * $fraction, behavior: 'smooth' });",
            null,
        )
    }

    private fun Double.toPercent(): Int = (this * 100).roundToInt().coerceIn(0, 100)
}

/** Exposes the vertical scroll range, which `WebView` keeps protected, so a scrub can land natively. */
@SuppressLint("ViewConstructor")
private class ProgressWebView(context: Context) : WebView(context) {
    val maxScroll: Int
        get() = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
}
