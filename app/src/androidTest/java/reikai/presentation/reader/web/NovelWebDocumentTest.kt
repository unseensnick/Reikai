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
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.novel.reader.ReaderMargins
import reikai.presentation.reader.WebViewHostActivity
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

    private val settings = NovelReaderSettings(
        fontSize = 18,
        lineHeight = 1.6f,
        textAlign = "left",
        margins = ReaderMargins(top = 24, bottom = 24, left = 16, right = 16),
        paragraphIndent = 1f,
        paragraphSpacing = 0.6f,
        fontFamily = "",
        followSystemTheme = false,
        backgroundColor = "#101010",
        textColor = "#eeeeee",
        keepScreenOn = false,
        orientation = 0,
        resolvedOrientation = 0,
        ttsEnabled = false,
        ttsRate = 1f,
        ttsPitch = 1f,
        ttsAutoPageAdvance = false,
        ttsScrollToTop = false,
        bionicReading = false,
        removeExtraSpacing = false,
        tapToScroll = true,
        swipeGestures = true,
        showProgressPercentage = false,
        autoScroll = false,
        autoScrollSpeed = 1f,
        useVolumeButtons = false,
        volumeButtonsInverted = false,
        volumeButtonsFraction = 0.75f,
        railHeightPercent = 60,
        railOnLeft = false,
    )

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
     * A seam introduces the chapter below it, so a prepend labels its seam with the title of the
     * chapter it landed above, not its own. Getting this backwards names every boundary after the
     * chapter the reader just finished, which reads as the reader having gone nowhere.
     */
    @Test
    fun aSeamNamesTheChapterBelowIt() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', 'Chapter 2', '<p>next</p>')")
        assertEquals(
            "an appended seam does not name the chapter it introduces",
            "Chapter 2",
            eval("document.querySelector('.rk-seam').textContent"),
        )
        eval("window.rkReader.prependChapter('7', 'Chapter 0', '<p>earlier</p>')")
        assertEquals(
            "a prepended seam names the arriving chapter instead of the one below it",
            "Chapter 1",
            eval("document.querySelectorAll('.rk-seam')[0].textContent"),
        )
    }

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
