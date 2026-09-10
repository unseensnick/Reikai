package reikai.presentation.reader.web

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.presentation.reader.WebViewHostActivity
import reikai.presentation.reader.readerTestSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That the WebView rendering mode's document is one a browser can actually run, and that its window
 * verbs do what the host expects. A token left unsubstituted is a syntax error, which renders a
 * blank page and reports nothing, so it looks exactly like a chapter that failed to load.
 */
@RunWith(AndroidJUnit4::class)
class NovelWebDocumentTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var webView: WebView
    private val ready = CountDownLatch(1)

    private companion object {
        const val CHAPTER_ID = 4242L
        const val TIMEOUT_S = 10L
    }

    /** Only the ready signal is needed here; the rest exist so the page never calls a missing method. */
    private inner class Bridge {
        @JavascriptInterface
        fun onReady() = ready.countDown()

        @JavascriptInterface
        fun onVisibleChapter(chapterId: String) {
            visibleChapters += chapterId
        }

        @JavascriptInterface
        fun onProgress(chapterId: String, fraction: Double) = Unit

        @JavascriptInterface
        fun onProgressSettled(chapterId: String, fraction: Double) = Unit

        @JavascriptInterface
        fun onRetryBoundary(forward: Boolean) = Unit

        @JavascriptInterface
        fun onToggleMenu() = Unit

        @JavascriptInterface
        fun onStepChapter(forward: Boolean) {
            steps += forward
            stepped.countDown()
        }
    }

    /** What the page asked the host to do, for the gesture cases. */
    private val steps = mutableListOf<Boolean>()
    private var stepped = CountDownLatch(1)

    /** Every chapter the page has named as the one being read, in order. */
    private val visibleChapters = mutableListOf<String>()

    private val settings = readerTestSettings

    private fun document(
        useOriginalFonts: Boolean = false,
        sourceCssPriority: Boolean = false,
    ): String = NovelWebDocument.build(
        context = instrumentation.targetContext,
        chapterId = CHAPTER_ID,
        chapterTitle = "Chapter 1",
        chapterHtml = "<p>lorem ipsum</p>".repeat(200),
        initialFraction = 0f,
        settings = settings,
        statusBarHeightPx = 0,
        customFontUrl = null,
        useOriginalFonts = useOriginalFonts,
        sourceCssPriority = sourceCssPriority,
    )

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity {
            webView = it.webView
            webView.settings.javaScriptEnabled = true
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    // region document

    @Test
    fun everyBuildTokenIsSubstituted() {
        val leftover = Regex("__[A-Z_]+__").findAll(document()).map { it.value }.toSet()
        assertTrue("the document still carries build tokens: $leftover", leftover.isEmpty())
    }

    /** A chapter's own CSS sits inside the body and so wins a tie, which is what these take back. */
    @Test
    fun theReaderOverridesAChaptersOwnStylingByDefault() {
        val css = document()
        assertTrue("the reader does not force its font size", css.contains("font-size: inherit !important"))
        assertTrue("the reader does not force its face", css.contains("font-family: inherit !important"))
        assertTrue(
            "headings are not restated, so forcing an inherited size flattens them",
            css.contains("h1 { font-size: 2em !important; }"),
        )
    }

    @Test
    fun sourceCssPriorityLeavesAChaptersStylingAlone() {
        assertTrue(
            "the reader still forces its styling over the chapter's",
            !document(sourceCssPriority = true).contains("!important"),
        )
    }

    /** The narrower of the two: the chapter keeps its face, everything else is still the reader's. */
    @Test
    fun useOriginalFontsDropsOnlyTheFaceOverride() {
        val css = document(useOriginalFonts = true)
        assertTrue("the chapter's own face is still overridden", !css.contains("font-family: inherit !important"))
        assertTrue("the reader stopped forcing its size too", css.contains("font-size: inherit !important"))
    }

    // endregion

    // region the page

    @Test
    fun theEngineStartsAndReportsReady() {
        loadDocument()
        assertEquals("the chapter is not in the document", "1", chapterCount())
        assertEquals(
            "the chapter is not the one asked for",
            CHAPTER_ID.toString(),
            eval("document.querySelector('.rk-chapter').getAttribute('data-rk-chapter-id')"),
        )
    }

    /**
     * The window verbs against the real document, rather than the synthetic one
     * `WebViewSeamPositionTest` measures anchoring with, so a chapter that arrives is one the reader
     * can reach: in the container, carrying its id, with a seam introducing it.
     */
    @Test
    fun appendingAChapterAddsItWithASeam() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', 'Chapter 2', '<p>next chapter</p>')")
        assertEquals("the appended chapter is missing", "2", chapterCount())
        assertEquals("the seam introducing it is missing", "1", eval("document.querySelectorAll('.rk-seam').length"))
        assertEquals(
            "the appended chapter is not last",
            "99",
            eval("document.querySelectorAll('.rk-chapter')[1].getAttribute('data-rk-chapter-id')"),
        )
    }

    /**
     * A seam names the chapter that finished over the one that follows, the pair Mihon's
     * TransitionText draws. Both inserts have to read one of the two titles off the neighbour
     * already in the document; taking the arriving chapter's for both names the boundary wrongly in
     * one direction, which is what a first cut of the prepend did.
     */
    @Test
    fun aSeamNamesTheChapterEitherSideOfIt() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', 'Chapter 2', '<p>next</p>')")
        assertEquals("the appended seam has no finished chapter", "Chapter 1", seamTitle(0, 0))
        assertEquals("the appended seam has the wrong next chapter", "Chapter 2", seamTitle(0, 1))

        eval("window.rkReader.prependChapter('7', 'Chapter 0', '<p>earlier</p>')")
        assertEquals("the prepended seam has the wrong finished chapter", "Chapter 0", seamTitle(0, 0))
        assertEquals("the prepended seam has the wrong next chapter", "Chapter 1", seamTitle(0, 1))
    }

    // The part-th half (finished, then next) of the seam-th seam.
    private fun seamTitle(seam: Int, part: Int): String =
        eval("document.querySelectorAll('.rk-seam')[$seam].querySelectorAll('.rk-seam-title')[$part].textContent")

    @Test
    fun evictingAChapterTakesItsSeamWithIt() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', 'Chapter 2', '<p>next</p>')")
        eval("window.rkReader.evictChapter('99')")
        assertEquals("the chapter was not evicted", "1", chapterCount())
        assertEquals("its seam was left behind", "0", eval("document.querySelectorAll('.rk-seam').length"))
    }

    @Test
    fun theSameChapterIsNeverAddedTwice() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', 'Chapter 2', '<p>next</p>')")
        eval("window.rkReader.appendChapter('99', 'Chapter 2', '<p>next</p>')")
        assertEquals("the chapter was added twice", "2", chapterCount())
    }

    @Test
    fun aBoundaryFailureDrawsOutsideTheChapterContainer() {
        loadDocument()
        eval("window.rkReader.setBoundaryFailure(false, 'no luck', 'Retry')")
        assertEquals("the failure is missing", "1", eval("document.querySelectorAll('.rk-failure').length"))
        // Inside the container it would count as the last chapter's height and skew its progress.
        assertEquals(
            "the failure sits inside the chapter container",
            "0",
            eval("document.querySelectorAll('#rk-chapters .rk-failure').length"),
        )
        eval("window.rkReader.setBoundaryFailure(false, null, 'Retry')")
        assertEquals("clearing left it behind", "0", eval("document.querySelectorAll('.rk-failure').length"))
    }

    /**
     * The chapter the chrome names after a backward load. The reader is at the top of the chapter it
     * opened, a chapter arrives above, and the page scrolls on by what was added so the line does not
     * move. The reader is still in the chapter it opened, and the page has to keep saying so.
     */
    @Test
    fun aPrependAtTheTopDoesNotRenameTheChapterBeingRead() {
        loadDocument()
        visibleChapters.clear()
        eval("window.rkReader.prependChapter('7', 'Chapter 0', '${"<p>earlier</p>".repeat(200)}')")
        settleFrames()
        assertEquals(
            "the page named the chapter above the reader: ${visibleChapters.toList()}",
            emptyList<String>(),
            visibleChapters.filter { it != CHAPTER_ID.toString() },
        )
    }

    /**
     * A reader stopped inside a seam is reading the chapter the seam introduces, because the screen
     * below the seam is entirely that chapter. The native renderer draws the same line by putting the
     * seam inside the chapter's own item; before this the two renderers disagreed, and the WebView
     * named the chapter above while its text was nowhere on screen.
     */
    @Test
    fun aReaderInsideASeamIsInTheChapterBelowIt() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', 'Chapter 2', '${"<p>next</p>".repeat(200)}')")
        visibleChapters.clear()
        // The seam's own last pixel, which is the far side of the boundary from the chapter above.
        eval(
            "var s = document.querySelector('.rk-seam');" +
                "window.scrollTo({ top: s.getBoundingClientRect().top + window.scrollY +" +
                "s.getBoundingClientRect().height - 1, behavior: 'instant' })",
        )
        settleFrames()
        // The last report, not every one: the engine reports each frame a scroll passes through.
        assertEquals("the page named the chapter above the seam", "99", visibleChapters.last())
    }

    /** Long enough for the engine's own rAF report, which is what names the chapter. */
    private fun settleFrames() = Thread.sleep(500)

    // endregion

    // region behaviours

    /**
     * Switching bionic reading off has to reach the page, and it cannot do it by undoing the spans:
     * `applyBionic` replaces text nodes, so the emphasis is switched by a class on the root instead.
     * Before that it stayed on screen until the chapter was reopened.
     */
    @Test
    fun bionicReadingSwitchesBothWays() {
        loadDocument()
        eval("window.rkReader.setSettings({ bionic: true })")
        assertEquals(
            "bionic never applied",
            "true",
            eval("document.documentElement.classList.contains('rk-bionic-on')"),
        )
        assertTrue("no emphasis was added", eval("document.querySelectorAll('.rk-bionic b').length").toInt() > 0)

        eval("window.rkReader.setSettings({ bionic: false })")
        assertEquals(
            "the emphasis is still switched on",
            "false",
            eval("document.documentElement.classList.contains('rk-bionic-on')"),
        )
        // The spans stay; the stylesheet is what makes them inert, so the reader sees plain text.
        assertEquals(
            "the spans should survive, inert",
            "normal",
            eval("getComputedStyle(document.querySelector('.rk-bionic b')).fontWeight === '400' ? 'normal' : 'bold'"),
        )
    }

    /**
     * `core.js`'s rule, which the native renderer also implements: far enough sideways, mostly
     * sideways, and started on the half it moves away from. The WebView mode used a quarter of the
     * viewport with no origin rule, so a short corner flick changed chapter in one renderer only.
     */
    @Test
    fun aSwipeStepsAChapterOnlyOnCoreJsRule() {
        loadDocument()
        val width = eval("window.innerWidth").toInt()

        swipe(fromX = width - 20, toX = width - 20 - 200, y = 400)
        assertTrue("a long swipe from the far half did not step", stepped.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals("it stepped the wrong way", listOf(true), steps)

        assertNoStep("a flick shorter than core.js's 180px stepped a chapter") {
            swipe(fromX = width - 20, toX = width - 20 - 60, y = 400)
        }
        // Rightwards is the previous chapter, so it has to start on the left half. Starting on the
        // right and dragging further right is the corner flick the origin rule exists to refuse.
        assertNoStep("a swipe that started on the half it moved towards stepped a chapter") {
            swipe(fromX = width / 2 + 10, toX = width / 2 + 10 + 200, y = 400)
        }

        steps.clear()
        stepped = CountDownLatch(1)
        swipe(fromX = 20, toX = 20 + 200, y = 400)
        assertTrue("a long swipe from the near half did not step back", stepped.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals("it stepped the wrong way", listOf(false), steps)
    }

    /** Runs [gesture] and gives the bridge, which calls back off the main thread, time to arrive. */
    private fun assertNoStep(message: String, gesture: () -> Unit) {
        steps.clear()
        stepped = CountDownLatch(1)
        gesture()
        assertTrue(message, !stepped.await(1, TimeUnit.SECONDS))
    }

    /** A one-finger horizontal drag, as the page's own listeners see it. */
    private fun swipe(fromX: Int, toX: Int, y: Int) {
        eval(
            """
            (function () {
              function at(x) {
                return new Touch({ identifier: 1, target: document.body, clientX: x, clientY: $y });
              }
              function fire(type, x) {
                document.dispatchEvent(new TouchEvent(type, {
                  touches: type === 'touchend' ? [] : [at(x)],
                  changedTouches: [at(x)],
                  bubbles: true,
                }));
              }
              fire('touchstart', $fromX);
              fire('touchmove', $toX);
              fire('touchend', $toX);
            })();
            """.trimIndent(),
        )
    }

    // endregion

    private fun loadDocument() {
        val finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.addJavascriptInterface(Bridge(), NovelWebBridge.NAME)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = finished.countDown()
            }
            webView.loadDataWithBaseURL(null, document(), "text/html", "UTF-8", null)
        }
        assertTrue("the document never finished loading", finished.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertTrue(
            "the page never reported ready, so its script did not run to the end",
            ready.await(TIMEOUT_S, TimeUnit.SECONDS),
        )
    }

    private fun chapterCount(): String = eval("document.querySelectorAll('.rk-chapter').length")

    private fun eval(js: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(js) { value ->
                result = value.orEmpty().trim().removeSurrounding("\"")
                done.countDown()
            }
        }
        assertTrue("javascript never returned: $js", done.await(TIMEOUT_S, TimeUnit.SECONDS))
        return result
    }
}
