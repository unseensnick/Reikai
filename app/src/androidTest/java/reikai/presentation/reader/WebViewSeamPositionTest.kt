package reikai.presentation.reader

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.presentation.reader.web.NovelWebDocument
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a WebView novel reader loses its place as its window grows, the counterpart to
 * [RecyclerPrependPositionTest] on the native renderer. Chromium anchors the scroll position itself,
 * so growth above the reader is free, including the late growth that is the native side's real
 * hazard, with one exception this pins: anchoring is suppressed at scroll offset zero, which is
 * exactly where a backward load lands. A correction is therefore needed only there. Numbers go to
 * logcat tag "WebSeamSpike". Findings: docs/dev/plans/content-layer-reader-surface.md.
 */
@RunWith(AndroidJUnit4::class)
class WebViewSeamPositionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var webView: WebView
    private val frames = FrameSignal()

    /** The real document's engine reporting that its script ran to the end. */
    private var engineReady = CountDownLatch(1)

    /**
     * How the page says a frame was composited. `evaluateJavascript` does not await a promise, so a
     * returned one resolves after the result was already read; this is called from inside the
     * callback instead, which is the only thing that actually orders a measurement after layout.
     */
    private class FrameSignal {
        @Volatile
        var latch = CountDownLatch(1)

        @JavascriptInterface
        fun done() = latch.countDown()
    }

    private companion object {
        const val TAG = "WebSeamSpike"

        /** A rendered chapter, several screens tall. */
        const val CHAPTER_PX = 8000

        /** What a chapter occupies before its images and late layout have landed. */
        const val UNMEASURED_PX = 1000

        /** Anything past this reads as the reader visibly losing its line. */
        const val FREE = 2

        const val TIMEOUT_S = 10L
    }

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            webView = activity.webView
            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(frames, "SeamTest")
        }
    }

    @After
    fun tearDown() {
        // Guarded: a failure in setUp would otherwise report a second, misleading failure here.
        if (::scenario.isInitialized) scenario.close()
    }

    // region harness

    /** Loads [html] and returns once the document has finished, so a measurement cannot race it. */
    private fun load(html: String) {
        val finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = finished.countDown()
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
        assertTrue("the document never finished loading", finished.await(TIMEOUT_S, TimeUnit.SECONDS))
        settle()
    }

    /** Evaluates [js] as an expression and returns its value as text, with the JSON quoting undone. */
    private fun eval(js: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("(function(){ $js })()") { value ->
                result = value.orEmpty().trim().removeSurrounding("\"")
                done.countDown()
            }
        }
        assertTrue("javascript never returned: $js", done.await(TIMEOUT_S, TimeUnit.SECONDS))
        return result
    }

    private fun evalDouble(js: String): Double = eval(js).toDoubleOrNull() ?: Double.NaN

    /**
     * Waits for two composited frames. One is not enough: an insert schedules layout, and the
     * scroll-anchoring adjustment Chromium may apply lands on the frame after the one that laid out.
     */
    private fun settle() {
        frames.latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                "requestAnimationFrame(function(){ requestAnimationFrame(function(){ SeamTest.done() }) })",
                null,
            )
        }
        assertTrue("no frame was composited within ${TIMEOUT_S}s", frames.latch.await(TIMEOUT_S, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    // endregion

    // region document

    /**
     * Two chapters stacked in a container, with a marker mid-way through the first. The marker is
     * what a reader's eye is on, so its distance from the top of the viewport is the whole
     * measurement: if that number moves, the page moved under them.
     */
    private fun document(anchorSuppressed: Boolean = false): String {
        val anchorRule = if (anchorSuppressed) "* { overflow-anchor: none; }" else ""
        return """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
              html, body { margin: 0; padding: 0; }
              $anchorRule
            </style></head>
            <body><div id="container">
              <div class="chapter" data-id="current">
                <div style="height:${CHAPTER_PX / 2}px"></div>
                <div id="marker" style="height:2px"></div>
                <div style="height:${CHAPTER_PX / 2}px"></div>
              </div>
              <div class="chapter" data-id="next" style="height:${CHAPTER_PX}px"></div>
            </div></body></html>
        """.trimIndent()
    }

    /** Puts the marker in the middle of the screen, which is where a reader's line actually sits. */
    private fun scrollMarkerToMidScreen() {
        eval(
            "var m = document.getElementById('marker');" +
                "var top = m.getBoundingClientRect().top + window.scrollY;" +
                "window.scrollTo(0, Math.round(top - window.innerHeight / 2));" +
                "return 'ok'",
        )
        settle()
    }

    private fun markerTop(): Double = evalDouble("return document.getElementById('marker').getBoundingClientRect().top")

    /**
     * Inserts a chapter of [height] px above everything, optionally taking back what it added the
     * way tsundoku's prepend does: measure the document, insert, then scroll on by the difference.
     * Reading scrollHeight forces the pending layout, so the difference is exact.
     */
    private fun prepend(height: Int, compensate: Boolean) {
        eval(
            "var oldHeight = document.body.scrollHeight;" +
                "var oldScrollY = window.scrollY;" +
                "var d = document.createElement('div');" +
                "d.className = 'chapter'; d.setAttribute('data-id','previous');" +
                "d.style.height = '${height}px';" +
                "var c = document.getElementById('container');" +
                "c.insertBefore(d, c.firstChild);" +
                "var diff = document.body.scrollHeight - oldHeight;" +
                (if (compensate) "if (diff > 0) { window.scrollTo(0, oldScrollY + diff); }" else "") +
                "return String(diff)",
        )
        settle()
    }

    /** Grows the already-inserted chapter to its real height, which is what a late layout does. */
    private fun growPrepended(to: Int, compensate: Boolean) {
        eval(
            "var oldHeight = document.body.scrollHeight;" +
                "var oldScrollY = window.scrollY;" +
                "document.querySelector('[data-id=previous]').style.height = '${to}px';" +
                "var diff = document.body.scrollHeight - oldHeight;" +
                (if (compensate) "if (diff > 0) { window.scrollTo(0, oldScrollY + diff); }" else "") +
                "return String(diff)",
        )
        settle()
    }

    private fun append(height: Int) {
        eval(
            "var d = document.createElement('div');" +
                "d.className = 'chapter'; d.setAttribute('data-id','after');" +
                "d.style.height = '${height}px';" +
                "document.getElementById('container').appendChild(d);" +
                "return 'ok'",
        )
        settle()
    }

    private fun evictFirst(compensate: Boolean) {
        eval(
            "var oldHeight = document.body.scrollHeight;" +
                "var oldScrollY = window.scrollY;" +
                "var first = document.getElementById('container').firstElementChild;" +
                "first.parentNode.removeChild(first);" +
                "var diff = oldHeight - document.body.scrollHeight;" +
                (if (compensate) "if (diff > 0) { window.scrollTo(0, oldScrollY - diff); }" else "") +
                "return String(diff)",
        )
        settle()
    }

    /** Runs one case and reports how far the reader's line moved on screen. */
    private fun drift(label: String, mutate: () -> Unit): Int {
        load(document())
        scrollMarkerToMidScreen()
        val before = markerTop()
        mutate()
        val after = markerTop()
        val drift = (after - before).roundToInt()
        Log.i(TAG, "$label: before=$before after=$after drift=$drift")
        return drift
    }

    // endregion

    /** The forward half, which is the one tsundoku actually runs: nothing above the reader changes. */
    @Test
    fun appendBelowReadingPositionIsFree() {
        val drift = drift("append") { append(CHAPTER_PX) }
        assertTrue("appending below the reader moved it by $drift px", abs(drift) <= FREE)
    }

    /**
     * The backward half with no correction of our own. Chromium implements scroll anchoring, so this
     * says whether the browser already holds the line, and therefore whether a manual correction is
     * needed at all or would be a second one on top.
     */
    @Test
    fun prependAboveReadingPositionWithoutCompensation() {
        val drift = drift("prepend/plain") { prepend(CHAPTER_PX, compensate = false) }
        val held = abs(drift) <= FREE
        Log.i(TAG, "prepend/plain: browser scroll anchoring ${if (held) "HELD" else "DID NOT HOLD"}")
        // Either outcome is a usable answer; a value in between is not, because it would mean the
        // correction to write depends on a partial adjustment whose size nothing here predicts.
        assertTrue(
            "an uncompensated prepend drifted $drift px, neither held (0) nor a clean shift ($CHAPTER_PX)",
            held || abs(drift - CHAPTER_PX) <= FREE,
        )
    }

    /** The same insert with the correction tsundoku's `prependHtmlContent` applies. */
    @Test
    fun prependAboveReadingPositionWithCompensation() {
        val drift = drift("prepend/compensated") { prepend(CHAPTER_PX, compensate = true) }
        assertTrue("compensated prepend moved the reader by $drift px", abs(drift) <= FREE)
    }

    /**
     * The case a backward window load actually hits: the reader is at the very top of the document
     * when the chapter above arrives. Blink is documented to suppress scroll anchoring at scroll
     * offset zero, so the anchoring the cases above rely on may not be there for the one crossing
     * that needs it. Run at a few small offsets, because the suppression is a boundary condition.
     */
    @Test
    fun prependWhileNearTopOfDocument() {
        fun driftAt(offset: Int, compensate: Boolean): Int {
            load(document())
            eval("window.scrollTo(0, $offset); return 'ok'")
            settle()
            val before = markerTop()
            prepend(CHAPTER_PX, compensate = compensate)
            val after = markerTop()
            val drift = (after - before).roundToInt()
            val label = if (compensate) "compensated" else "plain"
            Log.i(TAG, "prepend/near-top/$label: scrollY=$offset before=$before after=$after drift=$drift")
            return drift
        }

        val offsets = listOf(0, 1, 50, 400)
        offsets.forEach { driftAt(it, compensate = false) }
        val bad = offsets.associateWith { driftAt(it, compensate = true) }.filterValues { abs(it) > FREE }
        assertTrue("a compensated prepend moved the reader at scroll offsets $bad", bad.isEmpty())
    }

    // region the real document

    /**
     * The same measurements against the document the reader actually renders, driven through the
     * engine's own window verbs. The synthetic cases above measure Chromium; these say whether the
     * real page still gets that behaviour once its stylesheet, its seams and its own ResizeObserver
     * are in the way. `insertChapter` compensates only at scroll offset zero and relies on anchoring
     * everywhere else, so a regression in either half shows up here as drift.
     */
    @Test
    fun theRealDocumentHoldsItsPlaceWhenTheWindowGrows() {
        loadReal()
        scrollMarkerToMidScreenInstantly()
        val beforeAppend = markerTop()
        insertReal(atStart = false)
        val appendDrift = (markerTop() - beforeAppend).roundToInt()
        Log.i(TAG, "real/append: drift=$appendDrift")
        assertTrue("appending below the reader moved it by $appendDrift px", abs(appendDrift) <= FREE)

        val beforePrepend = markerTop()
        insertReal(atStart = true)
        val prependDrift = (markerTop() - beforePrepend).roundToInt()
        Log.i(TAG, "real/prepend: drift=$prependDrift")
        assertTrue("prepending above the reader moved it by $prependDrift px", abs(prependDrift) <= FREE)
    }

    /**
     * The crossing that actually needs the correction: a backward load arrives while the reader sits
     * at the top of the document, which is the one offset Blink suppresses anchoring at.
     */
    @Test
    fun theRealDocumentHoldsItsPlaceWhenPrependedAtTheTop() {
        val bad = listOf(0, 1, 50).associateWith { offset ->
            loadReal()
            scrollInstantlyTo(offset.toDouble())
            val before = markerTop()
            insertReal(atStart = true)
            val drift = (markerTop() - before).roundToInt()
            Log.i(TAG, "real/prepend/near-top: scrollY=$offset drift=$drift")
            drift
        }.filterValues { abs(it) > FREE }
        assertTrue("a prepend moved the reader at scroll offsets $bad", bad.isEmpty())
    }

    /** The chapter body the real document is built around, carrying the marker the drift is read off. */
    private fun chapterBody(marker: Boolean) = buildString {
        append("<div style=\"height:${CHAPTER_PX / 2}px\"></div>")
        if (marker) append("<div id=\"marker\" style=\"height:2px\"></div>")
        append("<div style=\"height:${CHAPTER_PX / 2}px\"></div>")
    }

    /** Loads the real document and waits for its engine, not just for the page. */
    private fun loadReal() {
        engineReady = CountDownLatch(1)
        instrumentation.runOnMainSync { webView.addJavascriptInterface(EngineBridge(), "ReikaiWeb") }
        load(
            NovelWebDocument.build(
                context = instrumentation.targetContext,
                chapterId = 1L,
                chapterTitle = "Chapter 1",
                chapterHtml = chapterBody(marker = true),
                initialFraction = 0f,
                settings = readerTestSettings,
                statusBarHeightPx = 0,
                fontSource = null,
                useOriginalFonts = false,
                sourceCssPriority = false,
                textSelectable = false,
            ),
        )
        assertTrue("the engine never reported ready", engineReady.await(TIMEOUT_S, TimeUnit.SECONDS))
        settle()
    }

    /**
     * The real stylesheet sets `scroll-behavior: smooth`, so a plain scrollTo animates and a
     * measurement two frames later reads the animation rather than the result. The engine's own
     * seek asks for an instant scroll for the same reason.
     */
    private fun scrollInstantlyTo(y: Double) {
        eval("window.scrollTo({ top: $y, behavior: 'instant' }); return 'ok'")
        settle()
    }

    private fun scrollMarkerToMidScreenInstantly() {
        val target = evalDouble(
            "var m = document.getElementById('marker');" +
                "return m.getBoundingClientRect().top + window.scrollY - window.innerHeight / 2",
        )
        scrollInstantlyTo(target.roundToInt().toDouble())
    }

    /** Ids climb away from the opening chapter's, which the engine would refuse as already present. */
    private var insertedChapters = 0

    private fun insertReal(atStart: Boolean) {
        val verb = if (atStart) "prependChapter" else "appendChapter"
        val id = if (atStart) -(++insertedChapters) else 100 + insertedChapters++
        val before = evalDouble("return document.querySelectorAll('.rk-chapter').length")
        eval("window.rkReader.$verb('$id', 'Chapter $id', '${chapterBody(marker = false)}'); return 'ok'")
        settle()
        // An insert the engine refuses adds no height, which would read as a drift of zero and pass.
        assertTrue(
            "$verb('$id') did not add a chapter",
            evalDouble("return document.querySelectorAll('.rk-chapter').length") > before,
        )
    }

    /** The page calls these on every frame; without them the engine throws inside its own rAF. */
    private inner class EngineBridge {
        @JavascriptInterface
        fun onReady() = engineReady.countDown()

        @JavascriptInterface
        fun onVisibleChapter(chapterId: String) = Unit

        @JavascriptInterface
        fun onProgress(chapterId: String, fraction: Double) = Unit

        @JavascriptInterface
        fun onProgressSettled(chapterId: String, fraction: Double) = Unit

        @JavascriptInterface
        fun onRetryBoundary(forward: Boolean) = Unit

        @JavascriptInterface
        fun onToggleMenu() = Unit

        @JavascriptInterface
        fun onStepChapter(forward: Boolean) = Unit
    }

    // endregion

    /** With anchoring suppressed, a bare insert must shift the page by exactly what it added. */
    @Test
    fun prependWithoutBrowserAnchoringShiftsByTheInsertedHeight() {
        load(document(anchorSuppressed = true))
        scrollMarkerToMidScreen()
        val before = markerTop()
        prepend(CHAPTER_PX, compensate = false)
        val after = markerTop()
        val drift = (after - before).roundToInt()
        Log.i(TAG, "prepend/no-anchor: before=$before after=$after drift=$drift inserted=$CHAPTER_PX")
        assertTrue(
            "with anchoring off the page should shift by the inserted height, but moved $drift of $CHAPTER_PX",
            abs(drift - CHAPTER_PX) <= FREE,
        )
    }

    /**
     * The case the native side found to be the real hazard: the inserted chapter is short when it
     * lands and reaches its height afterwards, so a correction taken at insert time is already stale.
     */
    @Test
    fun prependThatGrowsAfterInsertWithCompensation() {
        val drift = drift("prepend/late-growth") {
            prepend(UNMEASURED_PX, compensate = true)
            growPrepended(CHAPTER_PX, compensate = true)
        }
        assertTrue("a late-growing prepend moved the reader by $drift px", abs(drift) <= FREE)
    }

    /**
     * The same late growth with no correction on the growth step. This is the shape a port of
     * tsundoku's prepend would have, since it corrects once at insert and never again, so it says
     * whether that single correction is enough or whether growth has to be watched for too.
     */
    @Test
    fun prependThatGrowsAfterInsertUncompensatedGrowth() {
        val drift = drift("prepend/late-growth-uncorrected") {
            prepend(UNMEASURED_PX, compensate = true)
            growPrepended(CHAPTER_PX, compensate = false)
        }
        val grew = CHAPTER_PX - UNMEASURED_PX
        Log.i(TAG, "prepend/late-growth-uncorrected: drift=$drift growth=$grew")
        assertTrue(
            "late growth drifted $drift px, neither absorbed (0) nor the full growth ($grew)",
            abs(drift) <= FREE || abs(drift - grew) <= FREE,
        )
    }

    /** Dropping a chapter the window has moved past, which the native side measured as free. */
    @Test
    fun evictAboveReadingPositionWithCompensation() {
        load(document())
        scrollMarkerToMidScreen()
        prepend(CHAPTER_PX, compensate = true)
        val before = markerTop()
        evictFirst(compensate = true)
        val after = markerTop()
        val drift = (after - before).roundToInt()
        Log.i(TAG, "evict/compensated: before=$before after=$after drift=$drift")
        assertTrue("evicting above the reader moved it by $drift px", abs(drift) <= FREE)
    }
}
