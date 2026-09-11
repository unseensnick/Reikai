package reikai.presentation.reader

import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The gate between the host's window verbs and the page that runs them, against the real viewport. A
 * document is built off the main thread and reports ready a frame after its engine runs, so a verb
 * sent in between is held. A ready from any page but the one built last must not release it: the verb
 * then reaches a page that drops it, and the host, which assumes delivery, never sends it again.
 */
@RunWith(AndroidJUnit4::class)
class NovelWebViewportGateTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var viewport: NovelWebViewport
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the host would say the cutout inset is, which a test can change after a build. */
    @Volatile
    private var inset = 0

    /** Every chapter the viewport told the host the reader is in. */
    private val visibleReports = CopyOnWriteArrayList<Long>()

    private val webView: WebView get() = viewport.view as WebView

    private companion object {
        const val TIMEOUT_S = 10L

        /** Long enough for a bridge call made from the page to be queued on the main thread. */
        const val REPORT_QUEUED_MS = 300L
    }

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            viewport = NovelWebViewport(
                context = activity,
                textSelectable = false,
                volumeKeysActive = { false },
                volumeKeysInverted = false,
                volumeKeyScrollFraction = 0.75f,
                useOriginalFonts = false,
                sourceCssPriority = false,
                onProgressChanged = { _, _ -> },
                onProgressSettled = { _, _ -> },
                onToggleMenu = {},
                onStepChapter = {},
                onVisibleChapter = { visibleReports += it },
                onRetryBoundary = {},
                statusBarHeightPx = { inset },
                onChapterFits = { _, _ -> },
                onChapterEndSeen = {},
            )
            (activity.webView.parent as ViewGroup).addView(
                viewport.view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (::viewport.isInitialized) instrumentation.runOnMainSync { viewport.destroy() }
        if (::scenario.isInitialized) scenario.close()
    }

    /** Opening a document closes the gate, so a verb sent while it builds waits for it. */
    @Test
    fun aVerbSentWhileTheNextDocumentLoadsReachesThatDocument() {
        openAndAwaitReady(1L)
        instrumentation.runOnMainSync {
            scope.launch {
                viewport.load(chapter(3L), readerTestSettings)
            }
            scope.launch { viewport.append(chapter(4L), readerTestSettings) }
        }
        assertEquals("3,4", awaitChapters("3,4"))
    }

    @Test
    fun aReadyReportFromAnotherDocumentDoesNotReleaseTheVerbs() {
        openAndAwaitReady(1L)
        instrumentation.runOnMainSync {
            // The page being replaced reporting late, or a chapter's own script calling the bridge.
            webView.evaluateJavascript("window.ReikaiWeb.onReady('not-this-document')", null)
            // Held, so that report is queued ahead of anything the next load posts to this thread.
            Thread.sleep(REPORT_QUEUED_MS)
            scope.launch {
                viewport.load(chapter(3L), readerTestSettings)
            }
            scope.launch { viewport.append(chapter(4L), readerTestSettings) }
        }
        assertEquals("3,4", awaitChapters("3,4"))
    }

    /**
     * The page being replaced goes on reporting until it unloads, about a window the model has already
     * let go of. Passed on, it moved the reader back to the chapter they had just left.
     */
    @Test
    fun aChapterNamedByThePageBeingReplacedIsNotPassedOn() {
        openAndAwaitReady(1L)
        // The first page names its chapter once the second one lands under it.
        Thread.sleep(REPORT_QUEUED_MS)
        visibleReports.clear()
        instrumentation.runOnMainSync {
            scope.launch { viewport.load(chapter(3L), readerTestSettings) }
            webView.evaluateJavascript("window.ReikaiWeb.onVisibleChapter('1')", null)
        }
        Thread.sleep(REPORT_QUEUED_MS)
        assertEquals(false, 1L in visibleReports)
    }

    /** A document rebuilt as the Activity is recreated is built before its window has an inset. */
    @Test
    fun anInsetLearnedAfterTheBuildReachesThePage() {
        openAndAwaitReady(1L)
        inset = 40
        instrumentation.runOnMainSync { webView.requestLayout() }
        assertEquals(
            "40px",
            awaitEval("getComputedStyle(document.documentElement).getPropertyValue('--rk-inset-top').trim()", "40px"),
        )
    }

    /** Opens [id] with a verb behind it, and waits for the verb, which proves the gate opened. */
    private fun openAndAwaitReady(id: Long) {
        instrumentation.runOnMainSync {
            scope.launch {
                viewport.load(chapter(id), readerTestSettings)
            }
            scope.launch { viewport.append(chapter(id + 1), readerTestSettings) }
        }
        assertEquals("the first document never opened the gate", "$id,${id + 1}", awaitChapters("$id,${id + 1}"))
    }

    private fun chapter(id: Long) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = "Chapter $id",
        url = "",
        html = "<p>lorem ipsum</p>".repeat(50),
        baseUrl = null,
        progressPercent = 0,
    )

    /** The page's chapter ids once they read [expected], or as they stand at the timeout. */
    private fun awaitChapters(expected: String): String = awaitEval(
        "[...document.querySelectorAll('.rk-chapter')].map(c => c.getAttribute('data-rk-chapter-id')).join(',')",
        expected,
    )

    private fun awaitEval(js: String, expected: String): String {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        var value = eval(js)
        while (value != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            value = eval(js)
        }
        return value
    }

    private fun eval(js: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(js) { value ->
                result = value.orEmpty().trim().removeSurrounding("\"")
                done.countDown()
            }
        }
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
        return result
    }
}
