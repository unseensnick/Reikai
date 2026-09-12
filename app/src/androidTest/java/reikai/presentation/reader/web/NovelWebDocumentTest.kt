package reikai.presentation.reader.web

import android.graphics.Bitmap
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.presentation.reader.WebViewHostActivity
import reikai.presentation.reader.readerTestSettings
import reikai.presentation.reader.text.ChapterScrollProgress
import reikai.presentation.reader.text.NovelBionicSpans
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

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
    private var ready = CountDownLatch(1)

    /** The last fraction the page reported for the chapter it names, as the rail would show it. */
    @Volatile
    private var lastProgress = -1.0

    /** The last fraction reported per chapter. */
    private val progressByChapter = ConcurrentHashMap<String, Double>()

    private val menuToggles = AtomicInteger()
    private var menuToggled = CountDownLatch(1)

    private companion object {
        const val CHAPTER_ID = 4242L
        const val DOCUMENT_TOKEN = "test-document-token"
        const val TIMEOUT_S = 10L
        const val DRAG_STEPS = 5
        const val DRAG_STEP_MS = 16L

        /** The path the intercepted response answers with a tall picture rather than one pixel. */
        const val TALL_IMAGE = "tall.png"

        /** How long an image is held: past the seek's own frame, well inside the engine's own cap. */
        const val IMAGE_DELAY_MS = 600L

        /** A seam as the viewport sends one, for the cases about what a chapter arriving does. */
        const val SEAM =
            "{ finished: { title: 'Above', downloaded: false }, next: { title: 'Below', downloaded: false } }"

        /** A failure as the viewport sends one, with the source's reason. */
        const val FAILURE = "{ heading: \"Couldn't load\", message: 'no luck', retry: 'Retry' }"
        const val LORA_LOADED =
            "[...document.fonts].some(f => f.family.replace(/['\"]/g, '') === 'lora' && f.status === 'loaded')"
    }

    /**
     * Records what the page reports, heard only with the document's token as the host hears it, so an
     * engine call that passes the wrong one fails the test waiting on it. Every method exists so the
     * page never calls a missing one.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun onReady(documentToken: String) = ready.countDown()

        @JavascriptInterface
        fun onVisibleChapter(documentToken: String, chapterId: String) {
            if (documentToken == DOCUMENT_TOKEN) visibleChapters += chapterId
        }

        @JavascriptInterface
        fun onProgress(documentToken: String, chapterId: String, fraction: Double) {
            if (documentToken != DOCUMENT_TOKEN) return
            lastProgress = fraction
            progressByChapter[chapterId] = fraction
        }

        @JavascriptInterface
        fun onProgressSettled(documentToken: String, chapterId: String, fraction: Double) = Unit

        @JavascriptInterface
        fun onRetryBoundary(documentToken: String, forward: Boolean) = Unit

        @JavascriptInterface
        fun onToggleMenu(documentToken: String) {
            if (documentToken != DOCUMENT_TOKEN) return
            menuToggles.incrementAndGet()
            menuToggled.countDown()
        }

        @JavascriptInterface
        fun onStepChapter(documentToken: String, forward: Boolean) {
            if (documentToken != DOCUMENT_TOKEN) return
            steps += forward
            stepped.countDown()
        }

        @JavascriptInterface
        fun onChapterFits(documentToken: String, chapterId: String, fits: Boolean) {
            if (documentToken == DOCUMENT_TOKEN) fitsReports[chapterId] = fits
        }

        @JavascriptInterface
        fun onChapterEndSeen(documentToken: String, chapterId: String) {
            if (documentToken == DOCUMENT_TOKEN) endsSeen += chapterId
        }
    }

    /** Every chapter the page said had its last line on screen, in order. */
    private val endsSeen = CopyOnWriteArrayList<String>()

    /** Holds every image the page asks for until counted down, when one is set. */
    @Volatile
    private var imageGate: CountDownLatch? = null

    /** The last fit answer the page sent per chapter. */
    private val fitsReports = mutableMapOf<String, Boolean>()

    /** What the page asked the host to do, for the gesture cases. */
    private val steps = mutableListOf<Boolean>()
    private var stepped = CountDownLatch(1)

    /** Every chapter the page has named as the one being read, in order. */
    private val visibleChapters = mutableListOf<String>()

    private val settings = readerTestSettings

    private fun document(
        useOriginalFonts: Boolean = false,
        sourceCssPriority: Boolean = false,
        fontFamily: String = settings.fontFamily,
        fontSource: String? = null,
        chapterHtml: String = "<p>lorem ipsum</p>".repeat(200),
        initialFraction: Float = 0f,
    ): String = NovelWebDocument.build(
        context = instrumentation.targetContext,
        chapterId = CHAPTER_ID,
        documentToken = DOCUMENT_TOKEN,
        chapterHtml = chapterHtml,
        initialFraction = initialFraction,
        settings = settings.copy(fontFamily = fontFamily),
        statusBarHeightPx = 0,
        fontSource = fontSource,
        useOriginalFonts = useOriginalFonts,
        sourceCssPriority = sourceCssPriority,
        textSelectable = false,
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
        // A failed case can leave an image request parked on the gate.
        imageGate?.countDown()
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

    /**
     * A bundled face has to reach the page, not just its name: the family is written into the
     * stylesheet either way, and with no face behind it the text falls back without a trace. The
     * WebView mode keeps file access off, so the face travels inline. Nothing asks for the face here,
     * so it loads only because the chapter's own family names it.
     */
    @Test
    fun aBundledFontActuallyLoads() {
        val context = instrumentation.targetContext
        val source = runBlocking { NovelWebFonts.dataUri(context, context.appGraph.novelFontManager, "lora") }
        loadDocument(document(fontFamily = "lora", fontSource = source))
        assertEquals("the bundled face did not load", "true", awaitEval(LORA_LOADED, "true"))
    }

    /** A font picked while a chapter is open reaches the page as a face, not only as a family name. */
    @Test
    fun aFontPickedWithThePageOpenLoadsItsFace() {
        val context = instrumentation.targetContext
        loadDocument()
        val source = runBlocking { NovelWebFonts.dataUri(context, context.appGraph.novelFontManager, "lora") }
        // What the viewport's settings push sends: the family as a variable, then its face.
        val variables = NovelWebDocument.variables(settings.copy(fontFamily = "lora"), 0)
        eval("document.documentElement.setAttribute('style', ${JSONObject.quote(variables)})")
        eval("window.rkReader.setFontFace(${JSONObject.quote(NovelWebDocument.fontFace("lora", source))})")
        assertEquals("the face pushed into the open page did not load", "true", awaitEval(LORA_LOADED, "true"))
    }

    /**
     * A downloaded font is stored as its file name, and a family whose word starts with a digit is not
     * an identifier: unquoted, the declaration was dropped and the chapter fell back to the default.
     */
    @Test
    fun aUserFontWithADigitInItsNameIsTheChaptersFamily() {
        loadDocument(document(fontFamily = "Source_Sans_3.ttf"))
        assertTrue(
            "the chapter does not use the font",
            eval("getComputedStyle(document.querySelector('.rk-chapter p')).fontFamily").contains("Source Sans 3"),
        )
    }

    /**
     * A chapter shorter than the screen has no scroll room, so it holds at 0 as the native renderer
     * does and says it fits, which is what lets the model read it when the reader steps on. It used
     * to report itself finished on sight, marking read a chapter nobody had read.
     */
    @Test
    fun aChapterThatFitsOnScreenHoldsAtZeroAndSaysSo() {
        loadDocument(document(chapterHtml = "<p>short</p>"))
        // A page this short cannot scroll, so the report is asked for rather than scrolled into.
        eval("window.rkReader.refresh()")
        settleFrames()
        assertEquals("a short chapter reported progress", 0.0, lastProgress, 0.0)
        assertEquals("the page never said the chapter fits", true, fitsReports[CHAPTER_ID.toString()])
    }

    /**
     * The hold only matters once a short chapter can be scrolled into, which takes a chapter below it.
     * Measured against its own height, a few pixels into one read as a negative percent.
     */
    @Test
    fun aShortChapterBetweenTwoHoldsAtZeroOnceScrolledInto() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', '<p>short</p>', null, $SEAM)")
        eval("window.rkReader.appendChapter('100', '${"<p>next</p>".repeat(200)}', null, $SEAM)")
        // Part way through the first chapter first, so the short one's 0 is a change worth reporting.
        eval("window.scrollTo({ top: 3000, behavior: 'instant' })")
        settleFrames()
        eval(
            "var c = document.querySelector('[data-rk-chapter-id=\"99\"]');" +
                "window.scrollTo({ top: c.getBoundingClientRect().top + window.scrollY + 10, behavior: 'instant' })",
        )
        awaitProgress("99")
        assertEquals(0.0, progressByChapter["99"] ?: -1.0, 0.0)
    }

    /**
     * Two scroll frames land well inside the report interval, so the second position is held back. It
     * still has to reach the rail once the interval passes, or a scroll that stops there leaves the
     * rail showing where the reader was a moment before.
     */
    @Test
    fun aScrollStoppingInsideTheReportIntervalStillReportsWhereItStopped() {
        loadDocument()
        settleFrames()
        // Registered after the engine's own listener, so the second scroll lands in the frame that sent
        // the first report and is one frame behind it however slow the frames are.
        eval(
            "window.addEventListener('scroll', function once() {" +
                "window.removeEventListener('scroll', once);" +
                "requestAnimationFrame(function () { window.scrollTo({ top: 3000, behavior: 'instant' }); });" +
                "});" +
                "window.scrollTo({ top: 1000, behavior: 'instant' });",
        )
        settleFrames()
        val expected = eval(
            "var c = document.querySelector('.rk-chapter').getBoundingClientRect();" +
                "Math.min(Math.max(-c.top, 0) / (c.height - window.innerHeight), 1)",
        ).toDouble()
        assertEquals(expected, lastProgress, 0.01)
    }

    /** The novel's last chapter is read when its last line reaches the screen, which a short one's does
     *  as it opens. */
    @Test
    fun aShortChapterSaysItsEndWasSeen() {
        loadDocument(document(chapterHtml = "<p>short</p>"))
        settleFrames()
        assertEquals(listOf(CHAPTER_ID.toString()), endsSeen.toList())
    }

    @Test
    fun aLongChapterSaysNothingUntilItsLastLineIsOnScreen() {
        loadDocument()
        settleFrames()
        val atTheTop = endsSeen.toList()
        eval("window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' })")
        settleFrames()
        assertEquals(emptyList<String>() to listOf(CHAPTER_ID.toString()), atTheTop to endsSeen.toList())
    }

    /** A chapter measures short until its images land, so one still loading would be read on opening. */
    @Test
    fun anImageStillLoadingHoldsBackTheEnd() {
        loadDocument(document(chapterHtml = "<p>short</p>"))
        imageGate = CountDownLatch(1)
        eval("window.rkReader.appendChapter('99', '<p>short</p><img src=\"https://rk.test/a.png\">', null, $SEAM)")
        settleFrames()
        val whileLoading = endsSeen.toList()
        imageGate?.countDown()
        awaitEndSeen("99")
        assertEquals(
            listOf(CHAPTER_ID.toString()) to listOf(CHAPTER_ID.toString(), "99"),
            whileLoading to endsSeen.toList(),
        )
    }

    /** Held the same way, since a forward step reads a chapter that fits, and a long illustrated one
     *  measures short until its pictures arrive. The native renderer holds its fit report too. */
    @Test
    fun anImageStillLoadingHoldsBackTheFit() {
        loadDocument(document(chapterHtml = "<p>short</p>"))
        imageGate = CountDownLatch(1)
        eval("window.rkReader.appendChapter('99', '<p>short</p><img src=\"https://rk.test/a.png\">', null, $SEAM)")
        settleFrames()
        val whileLoading = fitsReports["99"]
        imageGate?.countDown()
        awaitFits("99")
        assertEquals(null to true, whileLoading to fitsReports["99"])
    }

    /**
     * A chapter shorter than the screen opened between two others keeps its first line at the top only
     * if the chapter after it arrives first: the one before it is kept in place by scrolling down by
     * its height, which a document with nothing below the short chapter has no room for.
     */
    @Test
    fun aShortChapterOpenedBetweenTwoKeepsItsPlaceWhenTheWindowGrowsBelowFirst() {
        loadDocument(document(chapterHtml = "<p>short</p>"))
        settleFrames()
        eval("window.rkReader.appendChapter('99', '${"<p>next</p>".repeat(200)}', null, $SEAM)")
        eval("window.rkReader.prependChapter('98', '${"<p>previous</p>".repeat(200)}', null, $SEAM)")
        settleFrames()
        assertEquals(0.0, chapterTop(CHAPTER_ID.toString()), 2.0)
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
        eval("window.rkReader.appendChapter('99', '<p>next chapter</p>', null, $SEAM)")
        assertEquals("the appended chapter is missing", "2", chapterCount())
        assertEquals("the seam introducing it is missing", "1", eval("document.querySelectorAll('.rk-seam').length"))
        assertEquals(
            "the appended chapter is not last",
            "99",
            eval("document.querySelectorAll('.rk-chapter')[1].getAttribute('data-rk-chapter-id')"),
        )
    }

    /**
     * A prepended chapter's seam goes below it, between it and the chapter it finished into, and
     * draws the finished chapter over the next the way TransitionText does. Which two chapters those
     * are is the viewport's to say (`TextViewportContractTest`), so the page draws what it is given.
     */
    @Test
    fun aPrependedSeamSitsBetweenTheTwoChaptersFinishedFirst() {
        loadDocument()
        eval(
            "window.rkReader.prependChapter('7', '<p>earlier</p>', null, " +
                "{ finished: { title: 'Chapter 0', downloaded: false }, next: { title: 'Chapter 1', downloaded: false } })",
        )
        assertEquals(
            "rk-chapter, rk-seam, rk-chapter: Chapter 0 over Chapter 1",
            eval(
                "[...document.getElementById('rk-chapters').children].map(e => e.className).join(', ') + ': ' + " +
                    "[...document.querySelectorAll('.rk-seam .rk-seam-title')].map(e => e.textContent).join(' over ')",
            ),
        )
    }

    @Test
    fun evictingAChapterTakesItsSeamWithIt() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', '<p>next</p>', null, $SEAM)")
        eval("window.rkReader.evictChapter('99')")
        assertEquals("the chapter was not evicted", "1", chapterCount())
        assertEquals("its seam was left behind", "0", eval("document.querySelectorAll('.rk-seam').length"))
    }

    /** The first chapter's seam sits below it, and a forward crossing evicts the first chapter. */
    @Test
    fun evictingTheFirstChapterTakesTheSeamBelowIt() {
        loadDocument()
        eval("window.rkReader.prependChapter('7', '<p>earlier</p>', null, $SEAM)")
        eval("window.rkReader.evictChapter('7')")
        assertEquals(
            "0 seams, then rk-chapter",
            eval(
                "document.querySelectorAll('.rk-seam').length + ' seams, then ' + " +
                    "document.getElementById('rk-chapters').firstElementChild.className",
            ),
        )
    }

    /**
     * The document has one base, the chapter it opened on, so a chapter from another site, or one
     * after a download, resolved its relative images and links against the wrong one.
     */
    @Test
    fun aChapterAddedByScrollingResolvesAgainstItsOwnBase() {
        loadDocument()
        val html = "<img src=\"/a.png\"><a href=\"b.html\">b</a><a href=\"#note\">note</a>"
        eval("window.rkReader.appendChapter('99', ${JSONObject.quote(html)}, 'https://other.test/novel/', $SEAM)")
        assertEquals(
            listOf("https://other.test/a.png", "https://other.test/novel/b.html", "#note"),
            evalList(
                "var c = document.querySelector('[data-rk-chapter-id=\"99\"]');" +
                    "[...c.querySelectorAll('[src], [href]')]" +
                    ".map(e => e.getAttribute(e.hasAttribute('src') ? 'src' : 'href'))",
            ),
        )
    }

    /** A srcset is a list of URLs of its own, on an image and on a picture's sources alike, and a data
     *  URI in it holds commas that do not end a candidate. */
    @Test
    fun aChapterAddedByScrollingResolvesItsSrcsetAgainstItsOwnBase() {
        loadDocument()
        val html = "<picture><source srcset=\"/a.webp 1x,b.webp 2x\"></picture>" +
            "<img srcset=\"c.png, data:image/png;base64,AA== 2x\">"
        eval("window.rkReader.appendChapter('99', ${JSONObject.quote(html)}, 'https://other.test/novel/', $SEAM)")
        assertEquals(
            listOf(
                "https://other.test/a.webp 1x,https://other.test/novel/b.webp 2x",
                "https://other.test/novel/c.png, data:image/png;base64,AA== 2x",
            ),
            evalList(
                "[...document.querySelectorAll('[data-rk-chapter-id=\"99\"] [srcset]')]" +
                    ".map(e => e.getAttribute('srcset'))",
            ),
        )
    }

    /** A tap on Retry is the button's. With tap-to-scroll off, the default, any other tap toggles the menu. */
    @Test
    fun tappingRetryIsTheButtonsTapNotTheReaders() {
        loadDocument()
        eval("window.rkReader.setSettings({ tapToScroll: false })")
        eval("window.rkReader.setBoundaryFailure(false, $FAILURE)")
        tap("document.querySelector('.rk-chapter p')")
        assertTrue("a tap on the text did not toggle the menu", menuToggled.await(TIMEOUT_S, TimeUnit.SECONDS))
        tap("document.querySelector('.rk-failure-retry')")
        // The bridge calls back off the main thread, so a toggle that should not come is given time.
        Thread.sleep(1000)
        assertEquals("tapping Retry toggled the menu too", 1, menuToggles.get())
    }

    /** The heading stays when the source gave a reason, as the text renderer draws it: the reason alone
     *  read as a stray line of the chapter. */
    @Test
    fun aFailureWithAReasonKeepsItsHeading() {
        loadDocument()
        eval("window.rkReader.setBoundaryFailure(false, $FAILURE)")
        assertEquals(
            "Couldn't load | no luck",
            eval("[...document.querySelectorAll('.rk-failure > div')].map(e => e.textContent).join(' | ')"),
        )
    }

    /** Retry turns into progress while the retry runs, where it used to only dim. */
    @Test
    fun tappingRetryShowsProgressInItsPlace() {
        loadDocument()
        eval("window.rkReader.setBoundaryFailure(false, $FAILURE)")
        eval("document.querySelector('.rk-failure-retry').click()")
        assertEquals(
            "0 buttons, 1 progress",
            eval(
                "document.querySelectorAll('.rk-failure-retry').length + ' buttons, ' + " +
                    "document.querySelectorAll('.rk-failure-progress').length + ' progress'",
            ),
        )
    }

    /** The token is in the engine's own text, and a chapter's script must not be able to read it. */
    @Test
    fun theDocumentTokenIsNotLeftInThePage() {
        loadDocument()
        assertEquals("-1", eval("document.documentElement.outerHTML.indexOf('$DOCUMENT_TOKEN')"))
    }

    /** A chapter script standing in for the bridge would see the ready report and its token. */
    @Test
    fun aChapterScriptReplacingTheBridgeIsNotTheOneTheEngineCalls() {
        loadDocument(
            document(
                chapterHtml = "<p>text</p><script>window.ReikaiWeb = " +
                    "{ onReady: function (token) { window.rkStolen = token; } };</script>",
            ),
        )
        assertEquals("undefined", eval("typeof window.rkStolen"))
    }

    @Test
    fun theSameChapterIsNeverAddedTwice() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', '<p>next</p>', null, $SEAM)")
        eval("window.rkReader.appendChapter('99', '<p>next</p>', null, $SEAM)")
        assertEquals("the chapter was added twice", "2", chapterCount())
    }

    @Test
    fun aBoundaryFailureDrawsOutsideTheChapterContainer() {
        loadDocument()
        eval("window.rkReader.setBoundaryFailure(false, $FAILURE)")
        assertEquals("the failure is missing", "1", eval("document.querySelectorAll('.rk-failure').length"))
        // Inside the container it would count as the last chapter's height and skew its progress.
        assertEquals(
            "the failure sits inside the chapter container",
            "0",
            eval("document.querySelectorAll('#rk-chapters .rk-failure').length"),
        )
        eval("window.rkReader.setBoundaryFailure(false, null)")
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
        eval("window.rkReader.prependChapter('7', '${"<p>earlier</p>".repeat(200)}', null, $SEAM)")
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
        eval("window.rkReader.appendChapter('99', '${"<p>next</p>".repeat(200)}', null, $SEAM)")
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

    /**
     * A chapter's percent has one meaning. It is saved while the next chapter sits below it, and
     * restored when the chapter is opened alone, so the two must be the same position. The native
     * renderer's own rule is asked, not restated, so a change to it cannot leave this page behind.
     */
    @Test
    fun aPercentSavedWithANeighbourRestoresToTheSamePlace() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', '${"<p>next</p>".repeat(200)}', null, $SEAM)")
        settleFrames()
        eval("window.scrollTo({ top: 3000, behavior: 'instant' })")
        settleFrames()
        val savedAt = eval("window.scrollY").toDouble()
        val fraction = lastProgress
        val (top, height, viewport) = evalList(
            "var c = document.querySelector('.rk-chapter').getBoundingClientRect();" +
                "[c.top, c.height, window.innerHeight].map(String)",
        ).map { it.toDouble().roundToInt() }
        // A fraction does not change with the pixel scale, so CSS pixels answer for device ones.
        val nativeFraction = ChapterScrollProgress.fractionOf(top, height, viewport).toDouble()

        loadDocument()
        eval("window.rkReader.seekWithin('$CHAPTER_ID', $fraction)")
        settleFrames()
        val restoredAt = eval("window.scrollY").toDouble()

        val findings = "reported $fraction where native reads $nativeFraction; " +
            "restored ${restoredAt - savedAt}px from where it was saved"
        assertEquals(findings, savedAt, restoredAt, 2.0)
        assertEquals(findings, nativeFraction, fraction, 0.01)
    }

    /**
     * A saved percent is of the chapter's height with its images in it, so the restore has to wait for
     * them. `reader.css` gives every image `height: auto`, overriding any size a source supplies, so
     * before they land the chapter measures short by the whole image block and the seek drops the
     * reader past text they have not read; anchoring then holds them there and the next report saves
     * that place over the one they left.
     */
    @Test
    fun anIllustratedChapterRestoresToWhereItWasSaved() {
        // Held past the frame the seek runs in, so the chapter measures short at that point either way.
        imageGate = CountDownLatch(1)
        val release = Thread {
            Thread.sleep(IMAGE_DELAY_MS)
            imageGate?.countDown()
        }
        release.start()
        val illustrated = "<img src=\"https://rk.test/$TALL_IMAGE\">" + "<p>lorem ipsum</p>".repeat(60)
        loadDocument(document(chapterHtml = illustrated, initialFraction = 0.5f))
        release.join()
        awaitEval("String(document.querySelector('.rk-chapter img').complete)", "true")
        settleFrames()

        val picture = eval("String(document.querySelector('.rk-chapter img').getBoundingClientRect().height)")
            .toDouble()
        val landed = chapterFraction(CHAPTER_ID.toString())
        // Without this the case would pass on an image that never took a height, which is the whole
        // mechanism: the chapter would measure the same before and after.
        assertTrue("the image drew ${picture}px tall, so nothing about the chapter's height moved", picture > 500)
        assertEquals("the restore landed $landed through the chapter", 0.5, landed, 0.02)
    }

    /** The chapter the page opened on runs its scripts as it loads; one arriving by scrolling has to too. */
    @Test
    fun aSeamlessChapterRunsItsOwnScripts() {
        loadDocument()
        eval("window.rkReader.appendChapter('99', '<p>next</p><script>window.rkRan = 1;</script>', null, $SEAM)")
        assertEquals("the arriving chapter's script never ran", "1", eval("String(window.rkRan)"))
    }

    /** As the parser runs them: a script with a source has loaded before the inline one after it. */
    @Test
    fun aSeamlessChaptersScriptsRunInOrder() {
        loadDocument()
        val html = "<script src=\"data:text/javascript,window.rkLib=1\"></script>" +
            "<script>window.rkSaw = typeof window.rkLib;</script>"
        eval("window.rkReader.appendChapter('99', ${JSONObject.quote(html)}, null, $SEAM)")
        assertEquals("number", awaitEval("String(window.rkSaw)", "number"))
    }

    /** A script of a type the browser never runs fires no load and no error, so waiting on one stopped
     *  every script after it. A language attribute names the type when there is none. */
    @Test
    fun aSeamlessChaptersScriptsRunPastOnesTheBrowserNeverFetches() {
        loadDocument()
        val html = "<script type=\"text/javascript1.6\" src=\"data:text/javascript,window.rkNever=1\"></script>" +
            "<script language=\"vbscript\" src=\"data:text/javascript,window.rkNever=1\"></script>" +
            "<script>window.rkAfter = 1;</script>"
        eval("window.rkReader.appendChapter('99', ${JSONObject.quote(html)}, null, $SEAM)")
        assertEquals("1", awaitEval("String(window.rkAfter)", "1"))
    }

    /** Written once parsing is over, document.write opened a new document and wiped the reader. */
    @Test
    fun aSeamlessChaptersDocumentWriteLandsWhereItsScriptIs() {
        loadDocument()
        val html = "<p>before</p><script>document.write('<p id=\"rk-written\">written</p>');</script>"
        eval("window.rkReader.appendChapter('99', ${JSONObject.quote(html)}, null, $SEAM)")
        assertEquals(
            "true",
            eval(
                "!!document.querySelector('[data-rk-chapter-id=\"99\"] #rk-written') && " +
                    "!!document.getElementById('rk-chapters')",
            ),
        )
    }

    /**
     * Bionic reading walks a chapter's text, and a style block's CSS is text too. Wrapped in spans it
     * stops being CSS, so a chapter that ships its own styling lost it whenever bionic was on.
     */
    @Test
    fun bionicLeavesAChaptersOwnStylesAlone() {
        loadDocument()
        eval("window.rkReader.setSettings({ bionic: true })")
        eval(
            "window.rkReader.appendChapter('99', " +
                "'<style>.rk-probe { letter-spacing: 7px; }</style><p class=\"rk-probe\">styled</p>', null, $SEAM)",
        )
        assertEquals(
            "the chapter's own style block was emptied",
            "7px",
            eval("getComputedStyle(document.querySelector('.rk-probe')).letterSpacing"),
        )
    }

    /** Forcing every element to the reader's size is what stops a source sizing its text, and it must
     *  not take footnote markers with it. */
    @Test
    fun footnoteMarkersStaySmallerThanTheText() {
        loadDocument()
        eval(
            "window.rkReader.appendChapter('99', '<p id=\"rk-body\">text<sup id=\"rk-note\">1</sup></p>', null, $SEAM)",
        )
        val body = eval("parseFloat(getComputedStyle(document.getElementById('rk-body')).fontSize)").toDouble()
        val note = eval("parseFloat(getComputedStyle(document.getElementById('rk-note')).fontSize)").toDouble()
        assertTrue("a footnote marker renders at $note px beside $body px text", note < body)
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

    /** Switching back on walks the text again, and the emphasis it already made is text too. */
    @Test
    fun bionicSwitchedOffAndOnAgainDoesNotEmphasiseTheEmphasis() {
        loadDocument()
        eval("window.rkReader.setSettings({ bionic: true })")
        eval("window.rkReader.setSettings({ bionic: false })")
        eval("window.rkReader.setSettings({ bionic: true })")
        assertEquals("0", eval("document.querySelectorAll('.rk-bionic .rk-bionic').length"))
    }

    /** The text it walks is decoded, so a chapter's escaped markup must stay text. */
    @Test
    fun bionicLeavesEscapedMarkupAsText() {
        loadDocument()
        eval("window.rkReader.setSettings({ bionic: true })")
        eval("window.rkReader.appendChapter('99', '<p>x&lt;y and y&gt;z &lt;i&gt; here</p>', null, $SEAM)")
        // Compared in the page, since the bridge's JSON escapes the very brackets being checked.
        assertEquals(
            "true",
            eval("document.querySelector('[data-rk-chapter-id=\"99\"]').textContent === 'x<y and y>z <i> here'"),
        )
    }

    /**
     * The page and the native renderer emphasise the same letters, which each implements on its own:
     * the page's bold runs are checked against NovelBionicSpans itself, word rule and length table both.
     */
    @Test
    fun bionicBoldsTheLettersTheNativeRendererDoes() {
        val text = (1..60).joinToString(" ") { "a".repeat(it) } + " x1 1x 12 a1b2 café naïve 3rd"
        val native = SpannableString(text).also(NovelBionicSpans::apply).let { spanned ->
            spanned.getSpans(0, spanned.length, StyleSpan::class.java)
                .sortedBy(spanned::getSpanStart)
                .map { text.substring(spanned.getSpanStart(it), spanned.getSpanEnd(it)) }
        }
        loadDocument()
        eval("window.rkReader.setSettings({ bionic: true })")
        eval("window.rkReader.appendChapter('99', ${JSONObject.quote("<p>$text</p>")}, null, $SEAM)")
        assertEquals(
            native,
            evalList("[...document.querySelectorAll('[data-rk-chapter-id=\"99\"] b')].map(b => b.textContent)"),
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
        // Far enough sideways and from the right half, but it travelled further down than across.
        assertNoStep("a mostly vertical drag stepped a chapter") {
            swipe(fromX = width - 20, toX = width - 20 - 200, y = 100, toY = 500)
        }

        steps.clear()
        stepped = CountDownLatch(1)
        swipe(fromX = 20, toX = 20 + 200, y = 400)
        assertTrue("a long swipe from the near half did not step back", stepped.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals("it stepped the wrong way", listOf(false), steps)
    }

    /** A chapter's own script can build touch events, and the page's listeners would send its step. */
    @Test
    fun aSwipeAScriptBuildsStepsNoChapter() {
        loadDocument()
        val width = eval("window.innerWidth").toInt()
        assertNoStep("a swipe built by a page script stepped a chapter") {
            scriptSwipe(fromX = width - 20, toX = width - 20 - 200, y = 400)
        }
    }

    /** Runs [gesture] and gives the bridge, which calls back off the main thread, time to arrive. */
    private fun assertNoStep(message: String, gesture: () -> Unit) {
        steps.clear()
        stepped = CountDownLatch(1)
        gesture()
        assertTrue(message, !stepped.await(1, TimeUnit.SECONDS))
    }

    /** A one-finger drag from ([fromX], [y]) to ([toX], [toY]) in CSS pixels, delivered through the
     *  WebView as a finger's is: the page ignores touch events a script builds. */
    private fun swipe(fromX: Int, toX: Int, y: Int, toY: Int = y) {
        val scale = eval("window.devicePixelRatio").toFloat()
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, fromX * scale, y * scale, down, down)
            (1..DRAG_STEPS).forEach { step ->
                val fraction = step / DRAG_STEPS.toFloat()
                touch(
                    MotionEvent.ACTION_MOVE,
                    (fromX + (toX - fromX) * fraction) * scale,
                    (y + (toY - y) * fraction) * scale,
                    down,
                    down + step * DRAG_STEP_MS,
                )
            }
            touch(MotionEvent.ACTION_UP, toX * scale, toY * scale, down, down + (DRAG_STEPS + 1) * DRAG_STEP_MS)
        }
    }

    /** The same drag built by a script on the page, as a chapter's own script could build it. */
    private fun scriptSwipe(fromX: Int, toX: Int, y: Int) {
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

    /** A finger's tap in the middle of the element [target] evaluates to, scrolled on screen first, at
     *  once: the page scrolls smoothly, and a rect read mid-animation puts the tap on something else. */
    private fun tap(target: String) {
        eval("($target).scrollIntoView({ block: 'center', behavior: 'instant' })")
        val scale = eval("window.devicePixelRatio").toFloat()
        val x = eval("(function (r) { return r.left + r.width / 2; })(($target).getBoundingClientRect())")
        val y = eval("(function (r) { return r.top + r.height / 2; })(($target).getBoundingClientRect())")
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, x.toFloat() * scale, y.toFloat() * scale, down, down)
            touch(MotionEvent.ACTION_UP, x.toFloat() * scale, y.toFloat() * scale, down, down + DRAG_STEP_MS)
        }
    }

    private fun touch(action: Int, x: Float, y: Float, downAt: Long, at: Long) {
        val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(event)
        event.recycle()
    }

    // endregion

    private fun loadDocument(html: String = document()) {
        val finished = CountDownLatch(1)
        ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.addJavascriptInterface(Bridge(), NovelWebBridge.NAME)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = finished.countDown()

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val gate = imageGate ?: return null
                    gate.await(TIMEOUT_S, TimeUnit.SECONDS)
                    val png = if (request.url.toString().contains(TALL_IMAGE)) tallPng() else onePixelPng()
                    return WebResourceResponse("image/png", null, ByteArrayInputStream(png))
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
        assertTrue("the document never finished loading", finished.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertTrue(
            "the page never reported ready, so its script did not run to the end",
            ready.await(TIMEOUT_S, TimeUnit.SECONDS),
        )
    }

    private fun chapterCount(): String = eval("document.querySelectorAll('.rk-chapter').length")

    /** Where [chapterId]'s chapter begins on screen, in CSS pixels. */
    private fun chapterTop(chapterId: String): Double =
        eval("document.querySelector('[data-rk-chapter-id=\"$chapterId\"]').getBoundingClientRect().top").toDouble()

    /** Waits for the page to report [chapterId]'s end, since an image's decode and the frame after it
     *  are not bounded by a settle on a loaded device. Returns either way; the caller asserts. */
    // Waits for the page to report the chapter's progress, which a loaded device can take longer than
    // a settle to send. Returns either way; the caller asserts.
    private fun awaitProgress(chapterId: String) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (!progressByChapter.containsKey(chapterId) && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun awaitEndSeen(chapterId: String) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (chapterId !in endsSeen && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun awaitFits(chapterId: String) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (!fitsReports.containsKey(chapterId) && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun onePixelPng(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** Tall and narrow, so `max-width: 100%` with `height: auto` draws it several screens high. */
    private fun tallPng(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(400, 2000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** How far through [chapterId] the reader is, by the measure `state()` reports and `seekWithin`
     *  inverts, so a landing is read in the same unit the percent was saved in. */
    private fun chapterFraction(chapterId: String): Double = eval(
        "var el = document.querySelector('[data-rk-chapter-id=\"$chapterId\"]');" +
            "var r = el.getBoundingClientRect();" +
            "var start = r.top + window.scrollY;" +
            "var usable = Math.max(r.height - window.innerHeight, 1);" +
            "String(Math.min(Math.max(window.scrollY - start, 0) / usable, 1))",
    ).toDouble()

    /** [js] once it evaluates to [expected], or its last value at the timeout; the caller asserts. */
    private fun awaitEval(js: String, expected: String): String {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        var value = eval(js)
        while (value != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            value = eval(js)
        }
        return value
    }

    /** [js] evaluated to an array of strings. */
    private fun evalList(js: String): List<String> {
        val array = JSONArray(eval(js))
        return List(array.length()) { array.getString(it) }
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
        assertTrue("javascript never returned: $js", done.await(TIMEOUT_S, TimeUnit.SECONDS))
        return result
    }
}
