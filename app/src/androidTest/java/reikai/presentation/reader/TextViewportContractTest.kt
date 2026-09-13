package reikai.presentation.reader

import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Log
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.domain.reader.ChapterProgress
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.reader.text.DrawableWrapper
import reikai.presentation.reader.text.NovelChapterSeamView
import reikai.presentation.reader.text.PngServer
import reikai.presentation.reader.text.ReadAloudMark
import reikai.presentation.reader.text.pngOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The rules both novel renderers answer through [TextViewport], run once against each real viewport
 * rather than restated per renderer: what the marker between two chapters and the one after the last
 * say and when each is drawn, when a chapter's end counts as seen, and which drags step to another
 * chapter. Each renderer implements these on its own (Kotlin and `reader.js`), so this is what keeps
 * one from drifting while the other stays right.
 */
@RunWith(Parameterized::class)
class TextViewportContractTest(private val renderer: Renderer) {

    enum class Renderer { NATIVE, WEB }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var viewport: TextViewport
    private val view: View get() = (viewport as ReaderViewport).view

    /** Set by a case that destroys the viewport itself, which the teardown must not do again. */
    private var destroyed = false

    /** The last fit answer per chapter, which is also each renderer's sign that a chapter has rendered. */
    private val fits = ConcurrentHashMap<Long, Boolean>()
    private val endsSeen = CopyOnWriteArrayList<Long>()
    private val steps = CopyOnWriteArrayList<Boolean>()

    /** Every progress report either callback sent, in order, so a case can read what was reported. */
    private val reports = CopyOnWriteArrayList<ProgressReport>()
    private var server: PngServer? = null

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            viewport = buildViewport(activity, textSelectable = false)
            activity.setContentView(view)
        }
    }

    /** Swaps in a renderer whose text can be selected, which the page lays out and hit-tests differently. */
    private fun useSelectableText() {
        scenario.onActivity { activity ->
            (viewport as ReaderViewport).destroy()
            viewport = buildViewport(activity, textSelectable = true)
            activity.setContentView(view)
        }
    }

    private fun buildViewport(activity: WebViewHostActivity, textSelectable: Boolean): TextViewport =
        when (renderer) {
            Renderer.NATIVE -> NovelTextViewport(
                context = activity,
                textSelectable = textSelectable,
                volumeKeysActive = { false },
                volumeKeysInverted = false,
                volumeKeyScrollFraction = 0.75f,
                onProgressChanged = { id, percent -> reports += ProgressReport(id, percent, settled = false) },
                onProgressSettled = { id, percent -> reports += ProgressReport(id, percent, settled = true) },
                onToggleMenu = {},
                onStepChapter = { steps += it },
                onVisibleChapter = {},
                onRetryBoundary = {},
                cutoutTopDp = { 0 },
                onChapterFits = { id, fit -> fits[id] = fit },
                onChapterEndSeen = { endsSeen += it },
            )
            Renderer.WEB -> NovelWebViewport(
                context = activity,
                textSelectable = textSelectable,
                volumeKeysActive = { false },
                volumeKeysInverted = false,
                volumeKeyScrollFraction = 0.75f,
                useOriginalFonts = false,
                sourceCssPriority = false,
                onProgressChanged = { id, percent -> reports += ProgressReport(id, percent, settled = false) },
                onProgressSettled = { id, percent -> reports += ProgressReport(id, percent, settled = true) },
                onToggleMenu = {},
                onStepChapter = { steps += it },
                onVisibleChapter = {},
                onRetryBoundary = {},
                statusBarHeightPx = { 0 },
                onChapterFits = { id, fit -> fits[id] = fit },
                onChapterEndSeen = { endsSeen += it },
            )
        }

    @After
    fun tearDown() {
        if (::viewport.isInitialized && !destroyed) {
            instrumentation.runOnMainSync { (viewport as ReaderViewport).destroy() }
        }
        if (::scenario.isInitialized) scenario.close()
        server?.close()
    }

    // region the seam

    @Test
    fun theSeamNamesTheChapterThatFinishedAboveTheOneBelow() {
        open(chapter(SECOND, long("second")))
        prepend(chapter(FIRST, long("first")))
        assertEquals(listOf(DrawnSeam("Chapter 1.0", "Chapter 2.0", false, false, false)), awaitSeams())
    }

    /** A numbering that skips chapters is warned of at the boundary, as manga's transition does. */
    @Test
    fun aSeamAcrossANumberingGapWarnsOfIt() {
        open(chapter(SECOND, long("second"), number = 14.0))
        prepend(chapter(FIRST, long("first"), number = 10.0))
        assertEquals(listOf(true), awaitSeams().map { it.warnsOfGap })
    }

    /** Each chapter carries its own mark, so the one on disk is the one marked. */
    @Test
    fun aSeamMarksTheChapterThatIsOnDisk() {
        open(chapter(SECOND, long("second")))
        prepend(chapter(FIRST, long("first"), downloaded = true))
        assertEquals(listOf(true to false), awaitSeams().map { it.finishedDownloaded to it.nextDownloaded })
    }

    /** With "Always show chapter transition" off, two consecutive chapters run into each other. */
    @Test
    fun aSeamBetweenConsecutiveChaptersIsHiddenWithTheSettingOff() {
        open(chapter(SECOND, long("second")), transitionsOff)
        prepend(chapter(FIRST, long("first")))
        Thread.sleep(QUIET_MS)
        assertEquals(emptyList<DrawnSeam>(), seams())
    }

    /** Missing chapters are warned of whatever the setting says, as manga's transition is. */
    @Test
    fun aSeamOverMissingChaptersShowsWithTheSettingOff() {
        open(chapter(SECOND, long("second"), number = 14.0), transitionsOff)
        prepend(chapter(FIRST, long("first"), number = 10.0))
        assertEquals(listOf(true), awaitSeams().map { it.warnsOfGap })
    }

    /** The setting reaches a window already on screen through the settings push. */
    @Test
    fun turningTheSettingOffHidesASeamAlreadyShown() {
        open(chapter(SECOND, long("second")))
        prepend(chapter(FIRST, long("first")))
        awaitSeams()
        instrumentation.runOnMainSync { viewport.applySettings(transitionsOff) }
        settle()
        assertEquals(emptyList<DrawnSeam>(), seams())
    }

    // endregion

    // region the end of the novel

    /** Manga's "There's no next chapter" transition, under the name of the chapter that finished. */
    @Test
    fun theNovelsLastChapterEndsWithTheNoNextChapterMarker() {
        open(chapter(FIRST, "<p>short</p>", isLast = true))
        assertEquals(listOf("Chapter 1.0"), awaitEnds())
    }

    /** The setting governs only the marker between two chapters. */
    @Test
    fun theEndMarkerShowsWithTheSettingOff() {
        open(chapter(FIRST, "<p>short</p>", isLast = true), transitionsOff)
        assertEquals(listOf("Chapter 1.0"), awaitEnds())
    }

    @Test
    fun aChapterWithOneAfterItHasNoEndMarker() {
        open(chapter(FIRST, "<p>short</p>"))
        Thread.sleep(QUIET_MS)
        assertEquals(emptyList<String>(), ends())
    }

    /**
     * A list cannot scroll its last item to the top, so a short last chapter lands low on the screen
     * with the end of the chapter before it above. The marker below it is part of that last stretch,
     * so it lands on screen, as manga's last page lands above its transition.
     */
    @Test
    fun aShortLastChapterLandsWithItsEndMarkerOnScreen() {
        open(chapter(SECOND, "<p>short</p>", isLast = true))
        prepend(chapter(FIRST, long("first")))
        awaitEnds()
        val below = endMarkerBelowScreen()
        assertTrue("the end marker's top is $below px from the screen's bottom", below < 0f)
    }

    /** A last chapter the window grows into by scrolling ends with the marker too, not only one opened.
     *  Both fit on screen, so the native one lays the arriving chapter out. */
    @Test
    fun aLastChapterAddedToTheWindowEndsWithTheMarker() {
        open(chapter(FIRST, "<p>first</p>"))
        append(chapter(SECOND, "<p>second</p>", isLast = true))
        assertEquals(listOf("Chapter 2.0"), awaitEnds())
    }

    /**
     * The marker is not part of the chapter: a chapter read to its end has its last line at the bottom
     * of the screen and the marker just below it. Counted as the chapter's own height, the seek would
     * have pulled the marker on screen and the chapter's end would count as seen only once it was.
     */
    @Test
    fun aLastChapterReadToItsEndHasItsEndMarkerJustBelowTheScreen() {
        open(chapter(FIRST, long("first"), isLast = true))
        awaitEnds()
        instrumentation.runOnMainSync { (viewport as ReaderViewport).seekTo(ChapterProgress.Percent(10_000)) }
        settle()
        val below = endMarkerBelowScreen()
        assertTrue("the end marker's top is $below px from the screen's bottom", below >= -EDGE_SLACK_PX)
    }

    // endregion

    // region a chapter's end

    /** The novel's last chapter is read when its last line reaches the screen, which a short one's does
     *  as it opens. */
    @Test
    fun aShortChapterSaysItsEndWasSeen() {
        open(chapter(FIRST, "<p>short</p>"))
        awaitWhile { endsSeen.isEmpty() }
        assertEquals(listOf(FIRST), endsSeen.toList())
    }

    @Test
    fun aLongChapterSaysNothingWhileItsEndIsOffScreen() {
        open(chapter(FIRST, long("first")))
        Thread.sleep(QUIET_MS)
        assertEquals(emptyList<Long>(), endsSeen.toList())
    }

    @Test
    fun aLongChapterSaysItsEndWasSeenOnceItsLastLineIsOnScreen() {
        open(chapter(FIRST, long("first")))
        instrumentation.runOnMainSync { (viewport as ReaderViewport).seekTo(ChapterProgress.Percent(10_000)) }
        awaitWhile { endsSeen.isEmpty() }
        assertEquals(listOf(FIRST), endsSeen.toList())
    }

    // endregion

    // region swiping between chapters (core.js's rule, which both implement)

    @Test
    fun aLongSwipeFromTheRightHalfStepsForward() {
        open(chapter(FIRST, long("first")))
        drag(fromX = view.width - EDGE_PX, toX = view.width - EDGE_PX - dp(SWIPE_DP))
        awaitWhile { steps.isEmpty() }
        assertEquals(listOf(true), steps.toList())
    }

    @Test
    fun aLongSwipeFromTheLeftHalfStepsBack() {
        open(chapter(FIRST, long("first")))
        drag(fromX = EDGE_PX, toX = EDGE_PX + dp(SWIPE_DP))
        awaitWhile { steps.isEmpty() }
        assertEquals(listOf(false), steps.toList())
    }

    @Test
    fun aSwipeShorterThanTheMinimumDoesNotStep() {
        open(chapter(FIRST, long("first")))
        drag(fromX = view.width - EDGE_PX, toX = view.width - EDGE_PX - dp(SHORT_SWIPE_DP))
        Thread.sleep(QUIET_MS)
        assertEquals(emptyList<Boolean>(), steps.toList())
    }

    /** Rightwards is the previous chapter, so a swipe that starts on the right and runs further right
     *  is the corner flick the origin rule refuses. */
    @Test
    fun aSwipeStartedOnTheHalfItMovesTowardsDoesNotStep() {
        open(chapter(FIRST, long("first")))
        drag(fromX = view.width / 2f + EDGE_PX, toX = view.width / 2f + EDGE_PX + dp(SWIPE_DP))
        Thread.sleep(QUIET_MS)
        assertEquals(emptyList<Boolean>(), steps.toList())
    }

    @Test
    fun aMostlyVerticalDragDoesNotStep() {
        open(chapter(FIRST, long("first")))
        drag(
            fromX = view.width - EDGE_PX,
            toX = view.width - EDGE_PX - dp(SWIPE_DP),
            fromY = view.height * 0.8f,
            toY = view.height * 0.8f - dp(SWIPE_DP) * 2,
        )
        Thread.sleep(QUIET_MS)
        assertEquals(emptyList<Boolean>(), steps.toList())
    }

    // endregion

    // region read-aloud

    @Test
    fun paragraphsAreTheLinesShownWithRubyReadingsLeftOut() {
        open(chapter(FIRST, MIXED))
        assertEquals(MIXED_PARAGRAPHS, paragraphs(FIRST))
    }

    @Test
    fun aChapterTheWindowDoesNotHoldHasNoParagraphs() {
        open(chapter(FIRST, "<p>first</p>"))
        assertEquals(null, paragraphs(SECOND))
    }

    @Test
    fun anAppendedChapterHasItsOwnParagraphs() {
        open(chapter(FIRST, "<p>first</p>"))
        append(chapter(SECOND, "<p>second a</p><p>second b</p>"))
        assertEquals(listOf("second a", "second b"), paragraphs(SECOND))
    }

    /** The WebView's load returns before its page is ready, so this question is held for the page. */
    @Test
    fun paragraphsAskedWhileTheChapterLoadsAreAnsweredOnceItHasRendered() {
        val answer = runBlocking(Dispatchers.Main) {
            viewport.load(chapter(FIRST, "<p>first a</p><p>first b</p>"), readerTestSettings)
            withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_S)) { viewport.readAloud.paragraphs(FIRST) }
        }
        assertEquals(listOf("first a", "first b"), answer)
    }

    /** Native's load returns with the chapter rendered and answers as it is asked, so only the page holds
     *  a question or has one in flight. */
    @Test
    fun paragraphsHeldForAPageStillLoadingAreAnsweredNullWhenAnotherChapterReplacesIt() {
        assumeTrue("only the page answers asynchronously", renderer == Renderer.WEB)
        val answer = runBlocking(Dispatchers.Main) {
            viewport.load(chapter(FIRST, long("first")), readerTestSettings)
            val asked = async(start = CoroutineStart.UNDISPATCHED) { viewport.readAloud.paragraphs(FIRST) }
            viewport.load(chapter(SECOND, "<p>second</p>"), readerTestSettings)
            withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_S)) { asked.await() }
        }
        assertEquals(null, answer)
    }

    @Test
    fun paragraphsThePageIsAnsweringAreAnsweredNullWhenAnotherChapterReplacesIt() {
        assumeTrue("only the page answers asynchronously", renderer == Renderer.WEB)
        open(chapter(FIRST, long("first")))
        val answer = runBlocking(Dispatchers.Main) {
            val asked = async(start = CoroutineStart.UNDISPATCHED) { viewport.readAloud.paragraphs(FIRST) }
            viewport.load(chapter(SECOND, "<p>second</p>"), readerTestSettings)
            withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_S)) { asked.await() }
        }
        assertEquals(null, answer)
    }

    @Test
    fun paragraphsThePageIsAnsweringAreAnsweredNullWhenTheViewportIsDestroyed() {
        assumeTrue("only the page answers asynchronously", renderer == Renderer.WEB)
        open(chapter(FIRST, long("first")))
        val answer = runBlocking(Dispatchers.Main) {
            val asked = async(start = CoroutineStart.UNDISPATCHED) { viewport.readAloud.paragraphs(FIRST) }
            (viewport as ReaderViewport).destroy()
            destroyed = true
            withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_S)) { asked.await() }
        }
        assertEquals(null, answer)
    }

    @Test
    fun theFirstVisibleParagraphOfAChapterJustOpenedIsItsFirst() {
        open(chapter(FIRST, long("first")))
        assertEquals(ReadAloudPosition(FIRST, 0), firstVisibleParagraph())
    }

    /** Measured off the marks, with following off, so each renderer answers in its own geometry. */
    @Test
    fun theFirstVisibleParagraphAfterASeekIsTheFirstOnScreen() {
        open(chapter(FIRST, long("first")), readerTestSettings.copy(ttsKeepInView = false))
        instrumentation.runOnMainSync { (viewport as ReaderViewport).seekTo(ChapterProgress.Percent(5_000)) }
        settle()
        val position = checkNotNull(firstVisibleParagraph())
        highlight(position)
        val (top, bottom) = checkNotNull(awaitMark())
        highlight(position.copy(paragraph = position.paragraph - 1))
        val (_, previousBottom) = checkNotNull(awaitMark())
        assertTrue(
            "paragraph ${position.paragraph} spans $top..$bottom, the one before ends at $previousBottom",
            position.paragraph > 0 && bottom > 0 && top < viewportHeight() && previousBottom <= EDGE_SLACK_PX,
        )
    }

    @Test
    fun followingAParagraphOffScreenPutsItsTopAtTheTopWhenSetTo() {
        open(chapter(FIRST, long("first")), readerTestSettings.copy(ttsScrollToTop = true))
        highlight(ReadAloudPosition(FIRST, FAR_PARAGRAPH))
        awaitScrollStill()
        val (top, _) = checkNotNull(awaitMark())
        assertEquals(0f, top, FOLLOW_SLACK_PX)
    }

    @Test
    fun followingAParagraphOffScreenCentresIt() {
        open(chapter(FIRST, long("first")))
        highlight(ReadAloudPosition(FIRST, FAR_PARAGRAPH))
        awaitScrollStill()
        val (top, bottom) = checkNotNull(awaitMark())
        assertEquals(viewportHeight() / 2, (top + bottom) / 2, FOLLOW_SLACK_PX)
    }

    /** Set to the top, so a follow that ignored the paragraph already being on screen would move it. */
    @Test
    fun aParagraphAlreadyOnScreenIsNotScrolledTo() {
        open(chapter(FIRST, long("first")), readerTestSettings.copy(ttsScrollToTop = true))
        val before = scrollOffset()
        highlight(ReadAloudPosition(FIRST, 2))
        Thread.sleep(QUIET_MS)
        assertEquals(before to true, scrollOffset() to (awaitMark() != null))
    }

    @Test
    fun withKeepInViewOffAParagraphOffScreenIsNotScrolledTo() {
        open(chapter(FIRST, long("first")), readerTestSettings.copy(ttsKeepInView = false))
        val before = scrollOffset()
        highlight(ReadAloudPosition(FIRST, FAR_PARAGRAPH))
        Thread.sleep(QUIET_MS)
        assertEquals(before to true, scrollOffset() to (awaitMark() != null))
    }

    @Test
    fun highlightingNothingClearsTheMark() {
        open(chapter(FIRST, long("first")))
        if (renderer == Renderer.WEB) {
            Log.i(TAG, "CSS.highlights available in this WebView: ${eval("!!(window.CSS && CSS.highlights)")}")
        }
        highlight(ReadAloudPosition(FIRST, 1))
        checkNotNull(awaitMark())
        highlight(null)
        settle()
        assertEquals(null, markBounds())
    }

    /** A new text size rebuilds the native chapter's text, which has to carry the mark over. */
    @Test
    fun theMarkSurvivesARedrawForANewTextSize() {
        open(chapter(FIRST, long("first")))
        highlight(ReadAloudPosition(FIRST, 1))
        checkNotNull(awaitMark())
        instrumentation.runOnMainSync { viewport.applySettings(readerTestSettings.copy(fontSize = LARGER_FONT)) }
        awaitWhile { markedTextSize() != largerFontPx() }
        assertEquals(largerFontPx(), markedTextSize())
    }

    // endregion

    // region opening at a saved position

    @Test
    fun aChapterOpenedPartWayLandsAtItsSavedPosition() {
        open(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT))
        awaitScrollStill()
        assertEquals(SAVED_PERCENT.toFloat(), landedPercent(), LANDING_SLACK_PERCENT)
    }

    @Test
    fun aChapterOpenedPartWayLastReportsItsSavedPosition() {
        open(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT))
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        assertEquals(SAVED_PERCENT.toFloat(), lastReported(FIRST), LANDING_SLACK_PERCENT)
    }

    /** The model moves the rail and saves on the first report, so it has to be the landed position. */
    @Test
    fun aChapterOpenedPartWayFirstReportsItsSavedPosition() {
        open(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT))
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        assertEquals(SAVED_PERCENT.toFloat(), firstReported(FIRST), LANDING_SLACK_PERCENT)
    }

    /**
     * Opened into a viewport with no height, as a view not laid out yet is, then given its height. Native
     * has tried its landing once its load returns. The page is held there until it reports a fit, which a
     * start at no height sends; one that waits for a height sends none, and the wait runs out instead.
     */
    @Test
    fun aChapterOpenedBeforeItCanBeLaidOutLandsAtItsSavedPositionOnceItCan() {
        setViewHeight(0)
        runBlocking(Dispatchers.Main) {
            viewport.load(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT), readerTestSettings)
        }
        instrumentation.waitForIdleSync()
        if (renderer == Renderer.WEB) awaitWhile { !fits.containsKey(FIRST) }
        setViewHeight(ViewGroup.LayoutParams.MATCH_PARENT)
        awaitWhile { !fits.containsKey(FIRST) }
        settle()
        awaitScrollStill()
        assertEquals(SAVED_PERCENT.toFloat(), landedPercent(), LANDING_SLACK_PERCENT)
    }

    /** A report from the chapter's top, sent before the landing, would be saved over the position. */
    @Test
    fun aChapterOpenedPartWayNeverReportsItsTop() {
        open(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT))
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        assertEquals(emptyList<ProgressReport>(), reportsShortOf(FIRST, SAVED_PERCENT))
    }

    @Test
    fun anIllustratedChapterOpenedPartWayLandsAtItsSavedPositionOnceItsImagesArrive() {
        openIllustrated(SAVED_PERCENT)
        assertEquals(SAVED_PERCENT.toFloat(), landedPercent(), LANDING_SLACK_PERCENT)
    }

    @Test
    fun anIllustratedChapterOpenedPartWayLastReportsItsSavedPosition() {
        openIllustrated(SAVED_PERCENT)
        assertEquals(SAVED_PERCENT.toFloat(), lastReported(FIRST), LANDING_SLACK_PERCENT)
    }

    @Test
    fun anIllustratedChapterOpenedPartWayNeverReportsItsTop() {
        openIllustrated(SAVED_PERCENT)
        assertEquals(emptyList<ProgressReport>(), reportsShortOf(FIRST, SAVED_PERCENT))
    }

    /** A new text size rebuilds the native chapter while its pictures are still on their way, and the
     *  position it was headed for has to survive the rebuild rather than end with it. */
    @Test
    fun anIllustratedChapterRedrawnBeforeItsImagesArriveStillLandsAtItsSavedPosition() {
        assumeTrue(
            "the page holds a settings push until it is ready, which is after its landing, so none is pending",
            renderer == Renderer.NATIVE,
        )
        runBlocking(Dispatchers.Main) { viewport.load(illustratedChapter(SAVED_PERCENT), readerTestSettings) }
        instrumentation.runOnMainSync { viewport.applySettings(readerTestSettings.copy(fontSize = LARGER_FONT)) }
        awaitIllustratedLanding()
        assertEquals(SAVED_PERCENT.toFloat(), landedPercent(), LANDING_SLACK_PERCENT)
    }

    /**
     * A reader who scrolls while the pictures are still on their way has taken over from the saved
     * position: what they scroll to is reported as they go, and the pictures arriving does not take them
     * back. The pictures outwait the page's own image cap, so its late seek is covered too.
     */
    @Test
    fun aReaderWhoScrollsBeforeThePicturesArriveIsNotMovedBackWhenTheyDo() {
        runBlocking(Dispatchers.Main) {
            viewport.load(illustratedChapter(SAVED_PERCENT, STALLED_IMAGE_DELAYS_MS), readerTestSettings)
        }
        awaitWhile { !textShown() }
        drag(
            fromX = view.width / 2f,
            toX = view.width / 2f,
            fromY = view.height * 0.8f,
            toY = view.height * 0.3f,
            holdMs = FLING_FREE_HOLD_MS,
        )
        awaitScrollStill()
        val reportedWhileWaiting = !imagesArrived() && reports.any { it.chapterId == FIRST }
        awaitIllustratedLanding()
        val landed = landedPercent()
        assertTrue(
            "reported while the pictures loaded: $reportedWhileWaiting, landed at $landed%",
            reportedWhileWaiting && landed < SAVED_PERCENT - EARLY_REPORT_MARGIN_PERCENT,
        )
    }

    /** Whether the opened chapter's text is on screen, before anything has said it rendered. */
    private fun textShown(): Boolean = when (renderer) {
        Renderer.NATIVE -> {
            var shown = false
            instrumentation.runOnMainSync {
                shown = descendants(view).filterIsInstance<TextView>().any { it.isShown && it.height > 0 }
            }
            shown
        }
        Renderer.WEB -> eval("document.querySelectorAll('#rk-chapters .rk-chapter p').length > 0") == "true"
    }

    /** Further in, where a landing measured against the placeholders would fall further short. */
    @Test
    fun anIllustratedChapterOpenedNearItsEndLandsAtItsSavedPosition() {
        openIllustrated(LATE_PERCENT)
        assertEquals(LATE_PERCENT.toFloat(), landedPercent(), LANDING_SLACK_PERCENT)
    }

    // endregion

    // region keeping the reader's place

    @Test
    fun theLineAtTheTopStaysThereWhenTheTextGrowsLarger() {
        assertTopLineHeldAcross(readerTestSettings.copy(fontSize = LARGER_FONT))
    }

    @Test
    fun theLineAtTheTopStaysThereWhenTheLinesGrowTaller() {
        assertTopLineHeldAcross(readerTestSettings.copy(lineHeight = TALLER_LINES))
    }

    @Test
    fun theLineAtTheTopStaysThereWhenParagraphsSpreadApart() {
        assertTopLineHeldAcross(readerTestSettings.copy(paragraphSpacing = WIDER_SPACING))
    }

    /** With a larger size, since bold letters as wide as regular ones move nothing to hold on their own,
     *  while the page's emphasis still replaces the text a held line is found in. */
    @Test
    fun theLineAtTheTopStaysThereWhenBionicReadingTurnsOnWithALargerSize() {
        assertTopLineHeldAcross(readerTestSettings.copy(bionicReading = true, fontSize = LARGER_FONT))
    }

    /** Opens a long chapter part way, cuts a line through the top of the screen, and applies [settings]. */
    private fun assertTopLineHeldAcross(settings: NovelReaderSettings) {
        open(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT))
        awaitScrollStill()
        val before = straddledTopLine()
        applyAndAwaitReflow(settings)
        val after = lineTop(before.paragraph, before.offset)
        assertEquals("the line at ${before.paragraph} +${before.offset}", before.y, after, HOLD_SLACK_PX)
    }

    /**
     * A reader who scrolls after a change has left the line that change held, so the next change holds
     * the line they scrolled to rather than taking them back.
     */
    @Test
    fun aChangeAfterTheReaderMovesHoldsTheLineTheyMovedTo() {
        open(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT))
        awaitScrollStill()
        straddledTopLine()
        val larger = readerTestSettings.copy(fontSize = LARGER_FONT)
        applyAndAwaitReflow(larger)
        drag(
            fromX = view.width / 2f,
            toX = view.width / 2f,
            fromY = view.height * 0.8f,
            toY = view.height * 0.3f,
            holdMs = FLING_FREE_HOLD_MS,
        )
        awaitScrollStill()
        val moved = straddledTopLine()
        applyAndAwaitReflow(larger.copy(lineHeight = TALLER_LINES))
        val after = lineTop(moved.paragraph, moved.offset)
        assertEquals("the line at ${moved.paragraph} +${moved.offset}", moved.y, after, HOLD_SLACK_PX)
    }

    /** Sent together, so the page can take the new size before it has drawn the seek: the line held has to
     *  be the one the seek landed on, not the one it left. */
    @Test
    fun aNewTextSizeSentWithASeekKeepsTheSeeksPlace() {
        seekWithANewTextSize()
        assertEquals(SAVED_PERCENT.toFloat(), landedPercent(), EARLY_REPORT_MARGIN_PERCENT.toFloat())
    }

    /** The page takes the size back within a frame, which must not report the offset it corrected away from. */
    @Test
    fun aNewTextSizeSentWithASeekReportsNoOtherPlace() {
        seekWithANewTextSize()
        Log.i(TAG, "$renderer reports: ${reports.toList()}")
        assertEquals(
            emptyList<ProgressReport>(),
            reports.filter { abs(it.percent - SAVED_PERCENT) > EARLY_REPORT_MARGIN_PERCENT },
        )
    }

    private fun seekWithANewTextSize() {
        open(chapter(FIRST, long("first")))
        reports.clear()
        instrumentation.runOnMainSync {
            (viewport as ReaderViewport).seekTo(ChapterProgress.Percent(SAVED_PERCENT * 100L))
            viewport.applySettings(readerTestSettings.copy(fontSize = LARGER_FONT))
        }
        awaitWhile { paragraphTextSize() != largerFontPx() }
        settle()
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        awaitScrollStill()
    }

    /**
     * A backward load lands while the reader is at the very top of the chapter they opened, and the
     * chapter it adds keeps growing above them as its pictures arrive, after the insert itself.
     */
    @Test
    fun aChapterArrivingAboveWhosePicturesLandLaterLeavesTheOpenedChaptersFirstLineInPlace() {
        open(chapter(SECOND, long("second")))
        val before = lineTop("second 1.", 0)
        prepend(illustratedChapter(0))
        awaitWhile { checkNotNull(server).served.get() < IMAGE_DELAYS_MS.size }
        assertEquals("pictures served", IMAGE_DELAYS_MS.size, checkNotNull(server).served.get())
        settle()
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        awaitScrollStill()
        assertEquals(before, lineTop("second 1.", 0), HOLD_SLACK_PX)
    }

    /** Selectable text lets the page hit-test characters the reader could select, which is how the line is found. */
    @Test
    fun theLineAtTheTopStaysThereWhenTheTextGrowsLargerWithTextSelectable() {
        useSelectableText()
        assertTopLineHeldAcross(readerTestSettings.copy(fontSize = LARGER_FONT))
    }

    @Test
    fun aChapterArrivingAboveLeavesTheOpenedChaptersFirstLineInPlaceWithTextSelectable() {
        useSelectableText()
        open(chapter(SECOND, long("second")))
        val before = lineTop("second 1.", 0)
        prepend(chapter(FIRST, long("first")))
        awaitScrollStill()
        assertEquals(before, lineTop("second 1.", 0), HOLD_SLACK_PX)
    }

    /** The first character of the line at the top of the screen: the marker opening its paragraph, how far
     *  into that paragraph it is, its line's top and height, in the renderer's pixels. */
    private data class TopLine(val paragraph: String, val offset: Int, val y: Float, val height: Float)

    /**
     * Scrolls so the line at the top of the screen is cut through its middle, then reads it. A line
     * starting exactly at the top, or a gap between paragraphs there, would leave which line is "at the
     * top" to rounding.
     */
    private fun straddledTopLine(): TopLine {
        val line = checkNotNull(topLine()) { "no line at the top of the screen" }
        val by = line.y + line.height / 2
        when (renderer) {
            Renderer.NATIVE -> instrumentation.runOnMainSync { (view as RecyclerView).scrollBy(0, by.roundToInt()) }
            Renderer.WEB -> eval("window.scrollBy({ top: $by, behavior: 'instant' })")
        }
        settle()
        return checkNotNull(topLine()) { "no line at the top of the screen" }
    }

    private fun topLine(): TopLine? = when (renderer) {
        Renderer.NATIVE -> {
            var line: TopLine? = null
            instrumentation.runOnMainSync {
                val origin = IntArray(2).also(view::getLocationOnScreen)
                line = paragraphViews().firstNotNullOfOrNull { chunk ->
                    val layout = chunk.layout ?: return@firstNotNullOfOrNull null
                    val at = IntArray(2).also(chunk::getLocationOnScreen)
                    val top = at[1] - origin[1] + chunk.totalPaddingTop
                    if (top + layout.height <= 0) return@firstNotNullOfOrNull null
                    val index = layout.getLineForVertical((-top).coerceAtLeast(0))
                    val offset = layout.getLineStart(index)
                    val marker = PARAGRAPH_MARKER.findAll(chunk.text).lastOrNull { it.range.first <= offset }
                        ?: return@firstNotNullOfOrNull null
                    TopLine(
                        marker.value.trimEnd(),
                        offset - marker.range.first,
                        (top + layout.getLineTop(index)).toFloat(),
                        (layout.getLineBottom(index) - layout.getLineTop(index)).toFloat(),
                    )
                }
            }
            line
        }
        Renderer.WEB -> eval(
            "(function () { var ps = document.querySelectorAll('#rk-chapters .rk-chapter p');" +
                " var range = document.createRange();" +
                " for (var i = 0; i < ps.length; i++) { if (ps[i].getBoundingClientRect().bottom <= 0) continue;" +
                " var walker = document.createTreeWalker(ps[i], NodeFilter.SHOW_TEXT), at = 0;" +
                " while (walker.nextNode()) { var node = walker.currentNode;" +
                " for (var k = 0; k < node.length; k++) { range.setStart(node, k); range.setEnd(node, k + 1);" +
                " var rect = range.getClientRects()[0];" +
                " if (rect && rect.bottom > 0) return [ps[i].textContent.match(/^\\S+ \\d+\\./)[0], at + k," +
                " rect.top, rect.height]; }" +
                " at += node.length; } } return null; })()",
        ).takeIf { it != "null" }?.let(::JSONArray)?.let {
            TopLine(it.getString(0), it.getInt(1), it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
        }
    }

    /** The top of the line holding character [offset] of the paragraph [paragraph] opens. */
    private fun lineTop(paragraph: String, offset: Int): Float = when (renderer) {
        Renderer.NATIVE -> {
            var top = Float.NaN
            instrumentation.runOnMainSync {
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val chunk = paragraphViews().first { it.text.contains("$paragraph ") }
                val start = chunk.text.indexOf("$paragraph ")
                val layout = chunk.layout
                val at = IntArray(2).also(chunk::getLocationOnScreen)
                top = (
                    at[1] - origin[1] + chunk.totalPaddingTop +
                        layout.getLineTop(layout.getLineForOffset(start + offset))
                    ).toFloat()
            }
            top
        }
        Renderer.WEB -> eval(
            "(function () { var p = [...document.querySelectorAll('#rk-chapters .rk-chapter p')]" +
                ".find(function (e) { return e.textContent.indexOf(${JSONObject.quote("$paragraph ")}) === 0; });" +
                " var walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT), left = $offset;" +
                " while (walker.nextNode()) { var node = walker.currentNode;" +
                " if (left < node.length) { var range = document.createRange(); range.setStart(node, left);" +
                " range.setEnd(node, left + 1); return range.getClientRects()[0].top; }" +
                " left -= node.length; } return null; })()",
        ).toFloatOrNull() ?: Float.NaN
    }

    /** The native chapter's text views, which are the ones holding a paragraph marker. */
    private fun paragraphViews(): List<TextView> = descendants(view).filterIsInstance<TextView>()
        .filter { it.isShown && PARAGRAPH_MARKER.containsMatchIn(it.text) }

    /** The size the chapter's text is drawn at, in the renderer's pixels, or null while none is on screen. */
    private fun paragraphTextSize(): Float? = when (renderer) {
        Renderer.NATIVE -> {
            var size: Float? = null
            instrumentation.runOnMainSync { size = paragraphViews().firstOrNull()?.textSize }
            size
        }
        Renderer.WEB -> eval(
            "parseFloat(getComputedStyle(document.querySelector('#rk-chapters .rk-chapter p')).fontSize)",
        )
            .toFloatOrNull()
    }

    /** Applies [settings] and waits for the chapter's height to take it and the page to stop moving. */
    private fun applyAndAwaitReflow(settings: NovelReaderSettings) {
        val before = chapterHeight()
        instrumentation.runOnMainSync { viewport.applySettings(settings) }
        awaitWhile { chapterHeight().let { it == null || it == before } }
        assertTrue("the chapter stayed $before tall", chapterHeight().let { it != null && it != before })
        settle()
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        awaitScrollStill()
    }

    /** The one open chapter's text height, or null while a native redraw has none on screen. */
    private fun chapterHeight(): Float? = when (renderer) {
        Renderer.NATIVE -> {
            var height: Float? = null
            instrumentation.runOnMainSync {
                height = (paragraphViews().firstOrNull()?.parent as? View)?.height?.toFloat()
            }
            height
        }
        Renderer.WEB -> eval("document.querySelector('#rk-chapters .rk-chapter').getBoundingClientRect().height")
            .toFloatOrNull()
    }

    // endregion

    private data class ProgressReport(val chapterId: Long, val percent: Int, val settled: Boolean)

    /** The last percent either callback sent for [chapterId], logging the whole sequence for a failure. */
    private fun lastReported(chapterId: Long): Float {
        Log.i(TAG, "$renderer reports: ${reports.toList()}")
        return reports.lastOrNull { it.chapterId == chapterId }?.percent?.toFloat() ?: Float.NaN
    }

    private fun firstReported(chapterId: Long): Float {
        Log.i(TAG, "$renderer reports: ${reports.toList()}")
        return reports.firstOrNull { it.chapterId == chapterId }?.percent?.toFloat() ?: Float.NaN
    }

    private fun setViewHeight(height: Int) {
        instrumentation.runOnMainSync { view.layoutParams = view.layoutParams.apply { this.height = height } }
        instrumentation.waitForIdleSync()
    }

    /** The reports for [chapterId] further short of [percent] than a landing's rounding explains. */
    private fun reportsShortOf(chapterId: Long, percent: Int): List<ProgressReport> {
        Log.i(TAG, "$renderer reports: ${reports.toList()}")
        return reports.filter { it.chapterId == chapterId && it.percent < percent - EARLY_REPORT_MARGIN_PERCENT }
    }

    /**
     * Opens a long chapter at [percent] with tall network pictures in several of its chunks, arriving
     * apart, then waits for all of them and for the chapter to stop moving. Asserted that they arrived,
     * since a case passing on placeholders would say nothing about the pictures.
     */
    private fun openIllustrated(percent: Int) {
        open(illustratedChapter(percent))
        awaitIllustratedLanding()
    }

    private fun illustratedChapter(
        percent: Int,
        delaysMs: List<Long> = IMAGE_DELAYS_MS,
    ): NovelReaderViewModel.LoadedChapter {
        val pictures = PngServer(pngOf(400, 1600)).also { server = it }
        val urls = delaysMs.mapIndexed { index, delay -> pictures.url("picture$index", delay) }
        val html = (1..120).joinToString("") { paragraph ->
            val picture = IMAGE_AFTER_PARAGRAPH.indexOf(paragraph).takeIf {
                it >= 0
            }?.let { "<img src=\"${urls[it]}\">" }
            "<p>first $paragraph. " + "lorem ipsum dolor sit amet ".repeat(8) + "</p>" + picture.orEmpty()
        }
        return chapter(FIRST, html, progressPercent = percent)
    }

    private fun awaitIllustratedLanding() {
        awaitWhile { !imagesArrived() }
        assertTrue("the pictures never arrived", imagesArrived())
        if (renderer == Renderer.NATIVE) {
            assertTrue("the pictures sit in ${pictureChunks()} chunks", pictureChunks() >= 3)
        }
        settle()
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        awaitScrollStill()
    }

    private fun imagesArrived(): Boolean = when (renderer) {
        Renderer.NATIVE -> {
            var arrived = false
            instrumentation.runOnMainSync {
                val pictures = imageSpans().map { it.drawable as DrawableWrapper }
                arrived = pictures.size == IMAGE_DELAYS_MS.size && pictures.none { it.innerDrawable is ColorDrawable }
            }
            arrived
        }
        Renderer.WEB -> eval(
            "(function () { var i = [...document.images]; return i.length === ${IMAGE_DELAYS_MS.size} &&" +
                " i.every(function (m) { return m.complete && m.naturalHeight > 0; }); })()",
        ) == "true"
    }

    private fun imageSpans(): List<ImageSpan> = descendants(view).filterIsInstance<TextView>().flatMap { chunk ->
        val text = chunk.text as? Spanned ?: return@flatMap emptyList()
        text.getSpans(0, text.length, ImageSpan::class.java).toList()
    }

    private fun pictureChunks(): Int {
        var count = 0
        instrumentation.runOnMainSync {
            count = descendants(view).filterIsInstance<TextView>().count { chunk ->
                (chunk.text as? Spanned)?.let { it.getSpans(0, it.length, ImageSpan::class.java).isNotEmpty() } == true
            }
        }
        return count
    }

    /**
     * How far through the one open chapter the screen sits, in percent, read off its laid-out text rather
     * than either renderer's own report: its top against the viewport, over the height that scrolls, the
     * measure both renderers share (ChapterScrollProgress, reader.js `state`).
     */
    private fun landedPercent(): Float {
        val (top, height, viewport) = when (renderer) {
            Renderer.NATIVE -> {
                var bounds = Triple(0f, 0f, 0f)
                instrumentation.runOnMainSync {
                    val text = descendants(view).filterIsInstance<TextView>().first().parent as View
                    val origin = IntArray(2).also(view::getLocationOnScreen)
                    val at = IntArray(2).also(text::getLocationOnScreen)
                    bounds = Triple((at[1] - origin[1]).toFloat(), text.height.toFloat(), view.height.toFloat())
                }
                bounds
            }
            Renderer.WEB -> JSONArray(
                eval(
                    "(function () { var r = document.querySelector('#rk-chapters .rk-chapter')" +
                        ".getBoundingClientRect(); return [r.top, r.height, window.innerHeight]; })()",
                ),
            ).let { Triple(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
        }
        Log.i(TAG, "$renderer landed: top $top, height $height, viewport $viewport, reports ${reports.toList()}")
        assertTrue("the chapter is $height tall on a $viewport screen", height > viewport * 2)
        return (-top).coerceIn(0f, height - viewport) / (height - viewport) * 100f
    }

    private fun paragraphs(chapterId: Long): List<String>? =
        runBlocking(Dispatchers.Main) { viewport.readAloud.paragraphs(chapterId) }

    private fun firstVisibleParagraph(): ReadAloudPosition? =
        runBlocking(Dispatchers.Main) { viewport.readAloud.firstVisibleParagraph() }

    private fun highlight(position: ReadAloudPosition?) {
        instrumentation.runOnMainSync { viewport.readAloud.highlight(position) }
    }

    /** The marked paragraph's top and bottom once there is a mark, or null at the timeout. */
    private fun awaitMark(): Pair<Float, Float>? {
        awaitWhile { markBounds() == null }
        return markBounds()
    }

    /** The marked paragraph's top and bottom from the top of the viewport, in the renderer's pixels. */
    private fun markBounds(): Pair<Float, Float>? = when (renderer) {
        Renderer.NATIVE -> {
            var bounds: Pair<Float, Float>? = null
            instrumentation.runOnMainSync {
                val (chunk, marks) = markedChunk() ?: return@runOnMainSync
                val text = chunk.text as Spanned
                val layout = chunk.layout
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val at = IntArray(2).also(chunk::getLocationOnScreen)
                val top = at[1] - origin[1] + chunk.totalPaddingTop
                bounds =
                    (top + layout.getLineTop(layout.getLineForOffset(text.getSpanStart(marks.first())))).toFloat() to
                    (top + layout.getLineBottom(layout.getLineForOffset(text.getSpanEnd(marks.first()) - 1))).toFloat()
            }
            bounds
        }
        Renderer.WEB -> {
            val array = eval(
                "(function () { var box = $MARKED_BOX_JS; if (!box) return null;" +
                    " var rect = box.getBoundingClientRect(); return [rect.top, rect.bottom]; })()",
            ).takeIf { it != "null" }?.let(::JSONArray)
            array?.let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() }
        }
    }

    /** The chunk view holding the mark, and the mark's spans. */
    private fun markedChunk(): Pair<TextView, Array<ReadAloudMark>>? = descendants(view)
        .filterIsInstance<TextView>()
        .firstNotNullOfOrNull { chunk ->
            val text = chunk.text as? Spanned ?: return@firstNotNullOfOrNull null
            text.getSpans(0, text.length, ReadAloudMark::class.java).takeIf { it.isNotEmpty() }?.let { chunk to it }
        }

    /** The size the marked text is drawn at, in the renderer's pixels. */
    private fun markedTextSize(): Float? = when (renderer) {
        Renderer.NATIVE -> {
            var size: Float? = null
            instrumentation.runOnMainSync { size = markedChunk()?.first?.textSize }
            size
        }
        Renderer.WEB -> eval(
            "(function () { var box = $MARKED_BOX_JS; if (!box || !box.startContainer) return null;" +
                " return parseFloat(getComputedStyle(box.startContainer.parentElement).fontSize); })()",
        ).toFloatOrNull()
    }

    private fun largerFontPx(): Float = when (renderer) {
        Renderer.NATIVE -> TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            LARGER_FONT.toFloat(),
            instrumentation.targetContext.resources.displayMetrics,
        )
        Renderer.WEB -> LARGER_FONT.toFloat()
    }

    private fun viewportHeight(): Float = when (renderer) {
        Renderer.NATIVE -> view.height.toFloat()
        Renderer.WEB -> eval("window.innerHeight").toFloat()
    }

    private fun scrollOffset(): Float = when (renderer) {
        Renderer.NATIVE -> {
            var offset = 0
            instrumentation.runOnMainSync { offset = (view as RecyclerView).computeVerticalScrollOffset() }
            offset.toFloat()
        }
        Renderer.WEB -> eval("window.scrollY").toFloat()
    }

    /** Until the offset has held still across a few samples, since both follows animate. */
    private fun awaitScrollStill() {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        var last = scrollOffset()
        var stillFor = 0
        while (stillFor < STILL_SAMPLES && System.currentTimeMillis() < deadline) {
            Thread.sleep(STILL_SAMPLE_MS)
            val now = scrollOffset()
            stillFor = if (now == last) stillFor + 1 else 0
            last = now
        }
    }

    /** What one seam on screen says, whichever renderer drew it. */
    private data class DrawnSeam(
        val finished: String,
        val next: String,
        val finishedDownloaded: Boolean,
        val nextDownloaded: Boolean,
        val warnsOfGap: Boolean,
    )

    /** The seams on screen once there are any, or none at the timeout; the caller asserts. */
    private fun awaitSeams(): List<DrawnSeam> {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        var seams = seams()
        while (seams.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            seams = seams()
        }
        return seams
    }

    private fun seams(): List<DrawnSeam> = when (renderer) {
        Renderer.NATIVE -> {
            var drawn = emptyList<DrawnSeam>()
            instrumentation.runOnMainSync {
                drawn = descendants(view).filterIsInstance<NovelChapterSeamView>().filter { it.isVisible }
                    .mapNotNull { it.seam }
                    .mapNotNull { seam ->
                        // One with no next chapter is the end marker, which ends() reads.
                        seam.nextTitle?.let { next ->
                            DrawnSeam(
                                seam.finishedTitle,
                                next,
                                seam.finishedDownloaded,
                                seam.nextDownloaded,
                                seam.missingChapters > 0,
                            )
                        }
                    }
            }
            drawn
        }
        Renderer.WEB -> {
            val array = JSONArray(
                eval(
                    // The chapters' own seams: the end marker sits outside their container.
                    "[...document.querySelectorAll('#rk-chapters .rk-seam')].map(function (s) {" +
                        " var p = s.querySelectorAll('.rk-seam-part');" +
                        " return [p[0].querySelector('.rk-seam-title').textContent," +
                        " p[1].querySelector('.rk-seam-title').textContent," +
                        " !!p[0].querySelector('.rk-icon-downloaded'), !!p[1].querySelector('.rk-icon-downloaded')," +
                        " !!s.querySelector('.rk-seam-warning')]; })",
                ),
            )
            List(array.length()) { index ->
                val seam = array.getJSONArray(index)
                DrawnSeam(
                    seam.getString(0),
                    seam.getString(1),
                    seam.getBoolean(2),
                    seam.getBoolean(3),
                    seam.getBoolean(4),
                )
            }
        }
    }

    /** The finished chapter each end marker names, once there is one, or none at the timeout. */
    private fun awaitEnds(): List<String> {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        var ends = ends()
        while (ends.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            ends = ends()
        }
        return ends
    }

    /** The finished chapter each end marker names, counting only one that says there is no next. */
    private fun ends(): List<String> = when (renderer) {
        Renderer.NATIVE -> {
            var drawn = emptyList<String>()
            instrumentation.runOnMainSync {
                drawn = descendants(view).filterIsInstance<NovelChapterSeamView>().filter { it.isVisible }
                    .mapNotNull { it.seam }
                    .filter { it.nextTitle == null }
                    .map { it.finishedTitle }
            }
            drawn
        }
        Renderer.WEB -> {
            val array = JSONArray(
                eval(
                    "[...document.querySelectorAll('#rk-end')]" +
                        ".filter(function (e) { return !!e.querySelector('.rk-seam-notice'); })" +
                        ".map(function (e) { return e.querySelector('.rk-seam-title').textContent; })",
                ),
            )
            List(array.length(), array::getString)
        }
    }

    /** How far below the bottom of the screen the end marker's top is, in the renderer's own pixels. */
    private fun endMarkerBelowScreen(): Float = when (renderer) {
        Renderer.NATIVE -> {
            var below = Float.NaN
            instrumentation.runOnMainSync {
                val marker = descendants(view).filterIsInstance<NovelChapterSeamView>()
                    .first { it.isVisible && it.seam?.nextTitle == null }
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val at = IntArray(2).also(marker::getLocationOnScreen)
                below = (at[1] - origin[1] - view.height).toFloat()
            }
            below
        }
        Renderer.WEB ->
            eval("document.getElementById('rk-end').getBoundingClientRect().top - window.innerHeight")
                .toFloatOrNull() ?: Float.NaN
    }

    private fun chapter(
        id: Long,
        html: String,
        number: Double = id.toDouble(),
        downloaded: Boolean = false,
        isLast: Boolean = false,
        progressPercent: Int = 0,
    ) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = "Chapter $number",
        url = "/chapter/$id",
        html = html,
        baseUrl = null,
        progressPercent = progressPercent,
        chapterNumber = number,
        downloaded = downloaded,
        isLast = isLast,
    )

    private fun long(marker: String) =
        (1..120).joinToString("") { "<p>$marker $it. " + "lorem ipsum dolor sit amet ".repeat(8) + "</p>" }

    /** Opens [chapter] and waits for its first fit report, each renderer's sign that it has rendered.
     *  Asserted, since a case that expects nothing to happen would otherwise pass on a blank screen. */
    private fun open(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings = readerTestSettings) {
        runBlocking(Dispatchers.Main) { viewport.load(chapter, settings) }
        awaitWhile { !fits.containsKey(chapter.chapterId) }
        assertTrue("chapter ${chapter.chapterId} never rendered", fits.containsKey(chapter.chapterId))
        settle()
    }

    /** Waits for the window to hold one more chapter, so a case asserting that nothing shows between
     *  two chapters cannot pass because the second never arrived. Not the fit report [open] waits
     *  for: native lays out nothing entirely above the screen, so a long chapter there sends none. */
    private fun prepend(chapter: NovelReaderViewModel.LoadedChapter) {
        val before = chapterCount()
        runBlocking(Dispatchers.Main) { viewport.window.prepend(chapter, readerTestSettings) }
        awaitChapters(before + 1)
    }

    private fun append(chapter: NovelReaderViewModel.LoadedChapter) {
        val before = chapterCount()
        runBlocking(Dispatchers.Main) { viewport.window.append(chapter, readerTestSettings) }
        awaitChapters(before + 1)
    }

    private fun awaitChapters(count: Int) {
        awaitWhile { chapterCount() < count }
        assertEquals("the window never grew", count, chapterCount())
        settle()
    }

    private fun chapterCount(): Int = when (renderer) {
        Renderer.NATIVE -> {
            var count = 0
            instrumentation.runOnMainSync {
                count = descendants(view).filterIsInstance<RecyclerView>().first().adapter?.itemCount ?: 0
            }
            count
        }
        Renderer.WEB -> eval("document.querySelectorAll('#rk-chapters .rk-chapter').length").toInt()
    }

    /** A one-finger drag through the view, as a finger delivers it to either renderer. [holdMs] keeps the
     *  finger still before it lifts, which leaves no speed for a fling to carry the page on with. */
    private fun drag(fromX: Float, toX: Float, fromY: Float = view.height / 2f, toY: Float = fromY, holdMs: Long = 0) {
        var down = 0L
        instrumentation.runOnMainSync {
            down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, fromX, fromY, down, down)
            (1..DRAG_STEPS).forEach { step ->
                val fraction = step / DRAG_STEPS.toFloat()
                touch(
                    MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * fraction,
                    fromY + (toY - fromY) * fraction,
                    down,
                    down + step * DRAG_STEP_MS,
                )
            }
            if (holdMs == 0L) touch(MotionEvent.ACTION_UP, toX, toY, down, down + (DRAG_STEPS + 1) * DRAG_STEP_MS)
        }
        if (holdMs == 0L) return
        // Held in real time, since the WebView measures a fling by when a move reaches it, and a pixel on,
        // since it drops a move that goes nowhere.
        Thread.sleep(holdMs)
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_MOVE, toX, toY + 1, down, now)
            touch(MotionEvent.ACTION_UP, toX, toY + 1, down, now + DRAG_STEP_MS)
        }
    }

    private fun touch(action: Int, x: Float, y: Float, downAt: Long, at: Long) {
        val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun awaitWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun settle() {
        Thread.sleep(SETTLE_MS)
        instrumentation.waitForIdleSync()
    }

    private fun dp(value: Int) = value * instrumentation.targetContext.resources.displayMetrics.density

    private fun descendants(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.children?.flatMap { descendants(it) }?.toList() ?: emptyList())

    private fun eval(js: String): String {
        val done = CountDownLatch(1)
        var result = "[]"
        instrumentation.runOnMainSync {
            (view as WebView).evaluateJavascript(js) { value ->
                result = value ?: "[]"
                done.countDown()
            }
        }
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
        return result
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries

        const val FIRST = 1L
        const val SECOND = 2L
        const val TIMEOUT_S = 10L
        const val SETTLE_MS = 500L

        /** Long enough for a report the WebView makes off the main thread to arrive, had it been sent. */
        const val QUIET_MS = 1_000L

        /** A seek's rounding, in either renderer's pixels. */
        const val EDGE_SLACK_PX = 2f

        val transitionsOff = readerTestSettings.copy(alwaysShowChapterTransition = false)

        /** Past the 180dp both renderers need, and short of it. */
        const val SWIPE_DP = 220
        const val SHORT_SWIPE_DP = 60

        /** How far in from an edge a drag starts, clear of any system gesture strip. */
        const val EDGE_PX = 40f
        const val DRAG_STEPS = 8
        const val DRAG_STEP_MS = 10L

        /** Longer than the stretch of recent movement either renderer measures a fling's speed over. */
        const val FLING_FREE_HOLD_MS = 500L

        const val TAG = "TextViewportContract"

        /** A paragraph of [long] several screens below its start in either renderer. */
        const val FAR_PARAGRAPH = 60

        /** A follow's rounding: native centres in whole pixels, the page in CSS ones. */
        const val FOLLOW_SLACK_PX = 3f
        const val STILL_SAMPLES = 3
        const val STILL_SAMPLE_MS = 150L
        const val LARGER_FONT = 24

        /** Past what a line's first character and its line box differ by, far short of one line. */
        const val HOLD_SLACK_PX = 3f
        const val TALLER_LINES = 2.2f
        const val WIDER_SPACING = 1.5f

        /** What opens every paragraph [long] and the illustrated chapter write. */
        val PARAGRAPH_MARKER = Regex("""(first|second) \d+\. """)

        const val SAVED_PERCENT = 40
        const val LATE_PERCENT = 80

        /**
         * A stored percent is whole, so an exact landing sits within half a point of it and its report
         * rounds the same way; two points leaves room for pixel rounding, while a landing measured before
         * the pictures arrived misses by far more (each is several screens tall against a 200dp stand-in).
         */
        const val LANDING_SLACK_PERCENT = 2f

        /** Short of the saved position by more than a landing's rounding: a report from the top. */
        const val EARLY_REPORT_MARGIN_PERCENT = 5

        /** Arriving apart, so a landing applied at the first arrival measures the chapter short. */
        val IMAGE_DELAYS_MS = listOf(300L, 900L, 1_500L, 2_100L)

        /** Past the page's three-second image cap, and inside the ten seconds a wait here allows. */
        val STALLED_IMAGE_DELAYS_MS = List(IMAGE_DELAYS_MS.size) { 6_000L }

        /** A chunk is about 6000 characters, some 27 of these paragraphs, so each picture has its own. */
        val IMAGE_AFTER_PARAGRAPH = listOf(10, 40, 70, 100)

        /** A paragraph's Range under either way the page can draw a mark: a highlight, or its boxes. */
        const val MARKED_BOX_JS =
            "(window.CSS && CSS.highlights && ['rk-tts-background', 'rk-tts-underline']" +
                ".map(function (n) { var h = CSS.highlights.get(n); return h && Array.from(h)[0]; })" +
                ".filter(Boolean)[0]) || document.querySelector('#rk-tts-overlay .rk-tts-box')"

        /** Every kind of line a chapter can hold that read-aloud has to count the same in both renderers. */
        const val MIXED = "<h2>Heading</h2><p>First para.</p><p>Line a<br>Line b</p>" +
            "<ul><li>One</li><li>Two</li></ul><blockquote><p>Quoted</p></blockquote>" +
            "<table><tr><td>c1</td><td>c2</td></tr></table>" +
            "<p>漢<ruby>字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>です。</p>"
        val MIXED_PARAGRAPHS = listOf(
            "Heading", "First para.", "Line a", "Line b", "One", "Two", "Quoted", "c1 c2", "漢字です。",
        )
    }
}
