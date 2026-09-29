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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.novel.content.NovelCodeSnippet
import reikai.presentation.reader.web.NovelWebSnippets
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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

    /** Every chapter the viewport told the host the reader is in. */
    private val visibleReports = CopyOnWriteArrayList<Long>()

    /** Every chapter step, and every chapter end, the viewport passed on. */
    private val steps = CopyOnWriteArrayList<Boolean>()
    private val endsSeen = CopyOnWriteArrayList<Long>()

    /** Every chapter the viewport reported a fits-on-screen answer for. */
    private val fitsReports = CopyOnWriteArrayList<Long>()
    private var destroyedByTest = false

    private val webView: WebView get() = viewport.view as WebView

    private companion object {
        const val TIMEOUT_S = 10L

        /** Long enough for a bridge call made from the page to be queued on the main thread. */
        const val REPORT_QUEUED_MS = 300L

        /** Any token but the one the viewport built its document with, as every other caller has. */
        const val ANOTHER_DOCUMENT = "not-this-document"

        /** A chapter no test opens, so it can only reach the host through a forged call. */
        const val FORGED_CHAPTER = 99L
    }

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            viewport = NovelWebViewport(
                context = activity,
                fontManager = activity.appGraph.novelFontManager,
                imageRequests = activity.appGraph.novelImageRequests,
                textSelectable = false,
                callbacks = NovelViewportCallbacks(
                    volumeKeysActive = { false },
                    onProgressChanged = { _, _ -> },
                    onProgressSettled = { _, _ -> },
                    onTopLine = { _, _ -> },
                    onToggleMenu = {},
                    onStepChapter = { steps += it },
                    onVisibleChapter = { visibleReports += it },
                    onRetryBoundary = {},
                    cutoutTopDp = { 0 },
                    onChapterFits = { id, _ -> fitsReports += id },
                    onChapterEndSeen = { endsSeen += it },
                    onReaderScrolled = {},
                ),
                useOriginalFonts = false,
                sourceCssPriority = false,
                autoScrollSpeed = InMemoryPreferenceStore().getFloat("speed", 1f),
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
        if (::viewport.isInitialized && !destroyedByTest) instrumentation.runOnMainSync { viewport.destroy() }
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
            scope.launch { viewport.append(chapter(4L)) }
        }
        assertEquals("3,4", awaitChapters("3,4"))
    }

    @Test
    fun aReadyReportFromAnotherDocumentDoesNotReleaseTheVerbs() {
        openAndAwaitReady(1L)
        instrumentation.runOnMainSync {
            // The page being replaced reporting late, or a chapter's own script calling the bridge.
            webView.evaluateJavascript("window.ReikaiWeb.onReady('$ANOTHER_DOCUMENT')", null)
            // Held, so that report is queued ahead of anything the next load posts to this thread.
            Thread.sleep(REPORT_QUEUED_MS)
            scope.launch {
                viewport.load(chapter(3L), readerTestSettings)
            }
            scope.launch { viewport.append(chapter(4L)) }
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
            webView.evaluateJavascript("window.ReikaiWeb.onVisibleChapter('$ANOTHER_DOCUMENT', '1')", null)
        }
        assertEquals(true, awaitVisibleReport(3L))
        assertEquals(false, 1L in visibleReports)
    }

    /** Passed on, the page being replaced finishing a chapter the new window has would read it. */
    @Test
    fun aChapterEndReportedByThePageBeingReplacedIsNotPassedOn() {
        openAndAwaitReady(1L)
        instrumentation.runOnMainSync {
            scope.launch { viewport.load(chapter(3L), readerTestSettings) }
            webView.evaluateJavascript("window.ReikaiWeb.onChapterEndSeen('$ANOTHER_DOCUMENT', '3')", null)
        }
        assertEquals(true, awaitVisibleReport(3L))
        assertEquals(emptyList<Long>(), endsSeen.toList())
    }

    /**
     * On a page that is up, with nothing loading, so the token is the only thing refusing the call. The
     * cases above forge theirs behind a load, which closes the ready gate and would drop them anyway.
     */
    @Test
    fun aChapterNamedByACallerWithoutTheDocumentsTokenIsNotPassedOn() {
        openAndAwaitReady(1L)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                "window.ReikaiWeb.onVisibleChapter('$ANOTHER_DOCUMENT', '$FORGED_CHAPTER')",
                null,
            )
        }
        assertEquals(true, reportFromThePageItself())
        assertEquals(false, FORGED_CHAPTER in visibleReports)
    }

    /** A chapter's own script reaches the bridge, from the page or from a frame it makes, but not the
     *  token, which only the engine holds. */
    @Test
    fun aStepFromACallerWithoutTheDocumentsTokenIsNotPassedOn() {
        openAndAwaitReady(1L)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("window.ReikaiWeb.onStepChapter('$ANOTHER_DOCUMENT', true)", null)
        }
        assertEquals(true, reportFromThePageItself())
        assertEquals(emptyList<Boolean>(), steps.toList())
    }

    /** A report the page posted before teardown runs after it, so a destroyed viewport must not pass it on. */
    @Test
    fun aReportQueuedBeforeTeardownIsNotPassedOn() {
        openAndAwaitReady(1L)
        // Positive control: the page's own token-bearing fits report reaches the host with no frame.
        evalOnPage("rkReader.appendChapter('3', '<p>x</p>', null, null)")
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (3L !in fitsReports && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(true, 3L in fitsReports)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("rkReader.appendChapter('4', '<p>x</p>', null, null)", null)
            // Held, so the report is queued on this thread behind the teardown.
            Thread.sleep(REPORT_QUEUED_MS)
            viewport.destroy()
            destroyedByTest = true
        }
        instrumentation.waitForIdleSync()
        assertEquals(false, 4L in fitsReports)
    }

    /** A per-append snippet leans on the page's one-shot setup, so a chapter added before ready runs after it. */
    @Test
    fun aChapterAddedWhileThePageLoadsRunsItsSnippetsAfterThePagesOwn() {
        val settings = readerTestSettings.copy(
            webSnippets = NovelWebSnippets(
                js = listOf(
                    NovelCodeSnippet("setup", "window.rkT=1"),
                    NovelCodeSnippet(
                        "each",
                        "window.rkSeen=(window.rkSeen||[]).concat([window.rkT===1])",
                        runOnAppend = true,
                    ),
                ),
            ),
        )
        instrumentation.runOnMainSync {
            scope.launch { viewport.load(chapter(1L), settings) }
            scope.launch { viewport.append(chapter(2L)) }
        }
        assertEquals("1,2", awaitChapters("1,2"))
        assertEquals("true,true", awaitEval("String(window.rkSeen)", "true,true"))
    }

    /** rkReader is the page's to overwrite, and a chapter's script replacing an answer used to crash the app. */
    @Test
    fun aMalformedFirstVisibleParagraphReadsAsNone() {
        openAndAwaitReady(1L)
        evalOnPage("window.rkReader.readAloud.firstVisible = function () { return 5; }")
        assertEquals(null, bounded { viewport.readAloud.firstVisibleParagraph() })
    }

    @Test
    fun malformedParagraphsReadAsNone() {
        openAndAwaitReady(1L)
        evalOnPage("window.rkReader.readAloud.paragraphs = function () { return { not: 'a list' }; }")
        assertEquals(null, bounded { viewport.readAloud.paragraphs(1L) })
    }

    /** A read-aloud answer waits on the page's reply, so a page that never replies fails here rather than hangs. */
    private fun <T> bounded(query: suspend () -> T): T = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_S)) { query() }
    }

    private fun evalOnPage(js: String) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { webView.evaluateJavascript(js) { done.countDown() } }
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
    }

    /**
     * Scrolls the open window into its second chapter, so the page itself names it. Every bridge call is
     * queued on one thread, so that report arriving proves a call forged before it was already answered.
     */
    private fun reportFromThePageItself(): Boolean {
        evalOnPage("window.scrollTo(0, document.querySelectorAll('.rk-chapter')[1].offsetTop + 10)")
        return awaitVisibleReport(2L)
    }

    /** Whether the viewport passed on [id] before the timeout. */
    private fun awaitVisibleReport(id: Long): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (id !in visibleReports && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return id in visibleReports
    }

    /** Opens [id] with a verb behind it, and waits for the verb, which proves the gate opened. */
    private fun openAndAwaitReady(id: Long) {
        instrumentation.runOnMainSync {
            scope.launch {
                viewport.load(chapter(id), readerTestSettings)
            }
            scope.launch { viewport.append(chapter(id + 1)) }
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
        chapterNumber = id.toDouble(),
        novelId = 1L,
        sourceId = null,
        downloaded = false,
        isLast = false,
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
