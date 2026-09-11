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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import org.json.JSONObject
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.fraction
import reikai.presentation.novel.reader.NovelChapterNavigationClient
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.reader.web.NovelWebBridge
import reikai.presentation.reader.web.NovelWebDocument
import reikai.presentation.reader.web.NovelWebFonts
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.UUID
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
    /** Long press selects text, the same setting the native viewport reads. Links keep working here,
     *  which the native one cannot offer alongside selection. */
    private val textSelectable: Boolean,
    /** Whether a volume key scrolls right now: the setting, and the menu being down. The provider
     *  builds it once for both viewports, so they cannot disagree on when the keys are theirs. */
    private val volumeKeysActive: () -> Boolean,
    private val volumeKeysInverted: Boolean,
    private val volumeKeyScrollFraction: Float,
    /** Two settings only a WebView renderer can honour, so the rows are gated to this mode. Read
     *  once like the volume-key values above, so a change lands on the next open. */
    private val useOriginalFonts: Boolean,
    private val sourceCssPriority: Boolean,
    /** Named with its chapter, matching the native viewport, so the model never has to assume which
     *  chapter a percentage belongs to. */
    private val onProgressChanged: (chapterId: Long, percent: Int) -> Unit,
    private val onProgressSettled: (chapterId: Long, percent: Int) -> Unit,
    private val onToggleMenu: () -> Unit,
    /** Swipe-between-chapters, forward or back. */
    private val onStepChapter: (forward: Boolean) -> Unit,
    /** Which chapter the reader is actually in, which stops being the loaded one once a window grows. */
    private val onVisibleChapter: (chapterId: Long) -> Unit,
    /** Asking again for the neighbour whose failure is drawn at an edge. */
    private val onRetryBoundary: (forward: Boolean) -> Unit,
    /** Read per load rather than once: the cutout inset is only known after the window has one. */
    private val statusBarHeightPx: () -> Int,
    /** Whether a chapter fits on one screen, whenever that answer changes, as the native viewport
     *  reports it. Called off the main thread; the model's record of it is synchronised. */
    private val onChapterFits: (chapterId: Long, fits: Boolean) -> Unit,
    /** A chapter's last line reached the screen, once its images had landed. Off the main thread too. */
    private val onChapterEndSeen: (chapterId: Long) -> Unit,
) : ReaderViewport, TextViewport, ChapterWindow {

    /** The chapter the reader is actually in, which the rail seeks inside of. */
    private var visibleChapterId: Long? = null

    /** The document URL the chapter was loaded as, so the navigation policy can tell a footnote jump
     *  from the chapter trying to leave. */
    private var loadedBaseUrl: String? = null

    /** The last auto-scroll state the host asked for, so a freshly built document can be given it. */
    private var autoScrollRunning = false
    private var autoScrollPixelsPerFrame = 0f

    /**
     * Window verbs the page was not up to receive yet. Loading a document is asynchronous, so the
     * host's first append and prepend arrive while the page is still an empty frame, where the call
     * would find no engine and be dropped without a trace. Held in order and flushed on ready.
     */
    private var pageReady = false
    private val pendingWindowVerbs = mutableListOf<String>()

    /**
     * The document built last, which is the only one whose ready report opens the gate. The page being
     * replaced can still report after the next load has begun, and a chapter's own script can reach the
     * bridge too; either one opening it sent the new document's verbs to a page that dropped them.
     */
    private var documentToken: String? = null

    /** What the open document's variables were last written from, so an inset that changes after the
     *  build can be written again. A document rebuilt before its window attached reads an inset of 0. */
    private var documentSettings: NovelReaderSettings? = null
    private var documentInset = 0

    // Bridge messages arrive on a WebView background thread, so UI-affecting callbacks marshal here.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The family the page holds a face for, so a settings push knows when the face has to change. */
    private var faceFamily: String? = null
    private var faceJob: Job? = null

    private val webView = WebView(context).apply {
        setDefaultSettings()
        webViewClient = NovelChapterNavigationClient(context) { loadedBaseUrl }
        // The stylesheet and engine are inlined into the document, so unlike the legacy reader this
        // mode needs no file origin at all and the flag stays off.
        settings.allowFileAccess = false
        isLongClickable = textSelectable
        if (textSelectable) {
            isFocusable = true
            isFocusableInTouchMode = true
            addOnAttachStateChangeListener(SelectableWhileAttached())
        }
        addJavascriptInterface(
            NovelWebBridge(
                onVisibleChapter = { id ->
                    visibleChapterId = id
                    mainHandler.post { onVisibleChapter(id) }
                },
                onProgress = { id, f -> mainHandler.post { onProgressChanged(id, f.toPercent()) } },
                // On the same thread as the live reports, so a live one still queued cannot land after
                // it and overwrite the settled position.
                onProgressSettled = { id, f -> mainHandler.post { onProgressSettled(id, f.toPercent()) } },
                onRetryBoundary = { forward -> mainHandler.post { onRetryBoundary(forward) } },
                onToggleMenu = { mainHandler.post { onToggleMenu() } },
                onStepChapter = { forward -> mainHandler.post { onStepChapter(forward) } },
                onChapterFits = onChapterFits,
                onChapterEndSeen = onChapterEndSeen,
                // Auto-scroll is a call into the document, so one that was not up yet dropped it.
                onReady = { token -> mainHandler.post { onPageReady(token) } },
            ),
            NovelWebBridge.NAME,
        )
        // Every layout, because the inset is only known once the window has one and it moves with the
        // system bars; comparing first keeps an unchanged one from rewriting the page.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val settings = documentSettings
            if (settings != null && statusBarHeightPx() != documentInset) applySettings(settings)
        }
    }

    override val view: View
        get() = webView

    // A novel chapter is one vertically scrolling document, so there is no right-to-left shape to report.
    override val isRtl: Boolean
        get() = false

    /**
     * Seeks inside the chapter being read rather than across the document, because with a window the
     * two stopped being the same thing: a rail at half way means half of this chapter, not half of
     * everything loaded around it. A paged progress is not this medium's unit and is ignored.
     */
    override fun seekTo(progress: ChapterProgress) {
        if (progress !is ChapterProgress.Percent) return
        val chapterId = visibleChapterId ?: return
        webView.evaluateJavascript(
            "if (window.rkReader) rkReader.seekWithin(" +
                "${JSONObject.quote(chapterId.toString())}, ${progress.fraction});",
            null,
        )
    }

    // Nothing to do: a step reloads the document, which starts at that chapter's own stored position.
    override fun onChapterStepped() = Unit

    override fun destroy() {
        scope.cancel()
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
        if (!isVolumeKey || !volumeKeysActive()) return false
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
        visibleChapterId = chapter.chapterId
        // A new document has no engine until it says so, and whatever the old one had queued belongs
        // to a window that is being replaced.
        pageReady = false
        pendingWindowVerbs.clear()
        val token = UUID.randomUUID().toString()
        documentToken = token
        // The document is built with this family's face, and any swap still resolving is for the old page.
        faceJob?.cancel()
        faceFamily = settings.fontFamily
        val statusBarPx = statusBarHeightPx()
        documentSettings = settings
        documentInset = statusBarPx
        // Resolving a user font copies it out of the user's storage folder on first use, which is
        // disk work over SAF, so it happens off the main thread with the document build rather than
        // in front of it.
        val fontSource = NovelWebFonts.dataUri(context, context.appGraph.novelFontManager, settings.fontFamily)
        val html = withContext(Dispatchers.Default) {
            NovelWebDocument.build(
                context = context,
                chapterId = chapter.chapterId,
                documentToken = token,
                chapterTitle = chapter.title,
                chapterHtml = chapter.html,
                // Carried into the document rather than scrolled to afterwards, because the page has
                // to exist before it has anywhere to scroll and the load is asynchronous.
                initialFraction = chapter.progressPercent / 100f,
                settings = settings,
                statusBarHeightPx = statusBarPx,
                fontSource = fontSource,
                useOriginalFonts = useOriginalFonts,
                sourceCssPriority = sourceCssPriority,
                textSelectable = textSelectable,
            )
        }
        val safeBaseUrl = safeBaseUrl(chapter)
        loadedBaseUrl = safeBaseUrl
        webView.loadDataWithBaseURL(safeBaseUrl, html, "text/html", "UTF-8", null)
    }

    // Only trust an http(s) base URL. The plugin controls the site URL, and a file:// base would hand
    // the chapter document a file origin.
    private fun safeBaseUrl(chapter: NovelReaderViewModel.LoadedChapter): String? =
        chapter.baseUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * Pushes changed display settings into the live document, so a size or colour change reflows in
     * place rather than waiting for the next chapter. The custom properties are rewritten wholesale,
     * since the stylesheet reads them; only what CSS cannot express goes to the page's own settings
     * object. Queued like a window verb, because a change made while a document is still loading
     * would otherwise find no engine and be dropped with no trace until the next chapter.
     */
    override fun applySettings(settings: NovelReaderSettings) {
        documentSettings = settings
        documentInset = statusBarHeightPx()
        val variables = NovelWebDocument.variables(settings, documentInset)
        val behaviour = NovelWebDocument.behaviourJson(settings).toString()
        // A block, since runOrQueue's guard would otherwise cover only the first of the two.
        runOrQueue(
            "{ document.documentElement.setAttribute('style', ${JSONObject.quote(variables)}); " +
                "rkReader.setSettings($behaviour); }",
        )
        if (settings.fontFamily != faceFamily) swapFontFace(settings.fontFamily)
    }

    /** The variable above already names the new family; without its face the text falls back. */
    private fun swapFontFace(family: String) {
        faceFamily = family
        faceJob?.cancel()
        faceJob = scope.launch {
            val source = NovelWebFonts.dataUri(context, context.appGraph.novelFontManager, family)
            runOrQueue("rkReader.setFontFace(${JSONObject.quote(NovelWebDocument.fontFace(family, source))});")
        }
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

    /** Scrolls by a signed fraction of the screen (positive = forward), through the page's own
     *  relative animation so a volume press moves exactly as a tap does. */
    private fun scrollByFraction(fraction: Float) {
        webView.evaluateJavascript(
            "if (window.rkReader) rkReader.scrollSmoothlyBy(window.innerHeight * $fraction);",
            null,
        )
    }

    // The renderer holds a window, so the host drives these rather than reloading the document.
    override val window: ChapterWindow
        get() = this

    override suspend fun append(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) =
        insert(chapter, atStart = false)

    override suspend fun prepend(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) =
        insert(chapter, atStart = true)

    /**
     * The page holds the chapter's own scroll anchoring, so nothing is compensated here. Chromium
     * keeps the reading position when content lands above it, except at scroll offset zero, which
     * the page corrects for; both halves are measured in `WebViewSeamPositionTest`.
     */
    private fun insert(chapter: NovelReaderViewModel.LoadedChapter, atStart: Boolean) {
        val verb = if (atStart) "prependChapter" else "appendChapter"
        // Its own base, since the document's is the opened chapter's and a neighbour can come from a
        // download or, in a merged series, another site.
        val baseUrl = safeBaseUrl(chapter)?.let(JSONObject::quote) ?: "null"
        runOrQueue(
            "rkReader.$verb(" +
                "${JSONObject.quote(chapter.chapterId.toString())}, " +
                "${JSONObject.quote(chapter.title)}, " +
                "${JSONObject.quote(chapter.html)}, " +
                "$baseUrl);",
        )
    }

    override fun evict(chapterId: Long) {
        runOrQueue("rkReader.evictChapter(${JSONObject.quote(chapterId.toString())});")
    }

    /** Runs [js] against the page, or holds it in order until the page says it has an engine. */
    private fun runOrQueue(js: String) {
        if (pageReady) {
            webView.evaluateJavascript("if (window.rkReader) $js", null)
        } else {
            pendingWindowVerbs += js
        }
    }

    private fun onPageReady(token: String) {
        if (token != documentToken) return
        pageReady = true
        pendingWindowVerbs.forEach { webView.evaluateJavascript("if (window.rkReader) $it", null) }
        pendingWindowVerbs.clear()
        pushAutoScroll()
    }

    override fun setBoundaryFailures(
        previous: NovelReaderViewModel.BoundaryFailure?,
        next: NovelReaderViewModel.BoundaryFailure?,
    ) {
        // The page has no resources, so the strings it draws are resolved here.
        val retry = context.stringResource(MR.strings.action_retry)
        val fallback = context.stringResource(MR.strings.chapter_load_failed)
        listOf(true to previous, false to next).forEach { (atStart, failure) ->
            val message = failure?.let { JSONObject.quote(it.message ?: fallback) } ?: "null"
            runOrQueue(
                "rkReader.setBoundaryFailure($atStart, $message, ${JSONObject.quote(retry)});",
            )
        }
    }

    private fun Double.toPercent(): Int = (this * 100).roundToInt().coerceIn(0, 100)
}
