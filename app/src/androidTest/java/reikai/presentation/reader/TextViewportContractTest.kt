package reikai.presentation.reader

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.ParagraphStyle
import android.util.Log
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import mihon.app.di.appGraph
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
import reikai.domain.novel.tts.TtsHighlightStyle
import reikai.domain.reader.ChapterProgress
import reikai.presentation.reader.text.CHAPTER_IMAGE_WAIT_MS
import reikai.presentation.reader.text.ChapterImageSpan
import reikai.presentation.reader.text.DrawableWrapper
import reikai.presentation.reader.text.ImageFailureDrawable
import reikai.presentation.reader.text.NovelChapterSeamView
import reikai.presentation.reader.text.PngServer
import reikai.presentation.reader.text.ReadAloudMark
import reikai.presentation.reader.text.RubySpan
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

    /** Whether a volume key is the reader's, which the host decides; off unless a case turns it on. */
    private var volumeKeysOn = false

    /** The last fit answer per chapter, which is also each renderer's sign that a chapter has rendered. */
    private val fits = ConcurrentHashMap<Long, Boolean>()
    private val endsSeen = CopyOnWriteArrayList<Long>()
    private val steps = CopyOnWriteArrayList<Boolean>()

    /** Every chapter the viewport told the host the reader is in. */
    private val visibleChapters = CopyOnWriteArrayList<Long>()

    /** The cutout inset the host would report, which a case can change after an open. */
    @Volatile
    private var cutout = 0

    /** Every progress report either callback sent, in order, so a case can read what was reported. */
    private val reports = CopyOnWriteArrayList<ProgressReport>()

    /** Every top line reported, in order. */
    private val topLines = CopyOnWriteArrayList<Pair<Long, Int?>>()
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
                fontManager = activity.appGraph.novelFontManager,
                textSelectable = textSelectable,
                volumeKeysActive = { volumeKeysOn },
                onProgressChanged = { id, percent -> reports += ProgressReport(id, percent, settled = false) },
                onProgressSettled = { id, percent -> reports += ProgressReport(id, percent, settled = true) },
                onTopLine = { id, line -> topLines += id to line },
                onToggleMenu = {},
                onStepChapter = { steps += it },
                onVisibleChapter = { visibleChapters += it },
                onRetryBoundary = {},
                cutoutTopDp = { cutout },
                onChapterFits = { id, fit -> fits[id] = fit },
                onChapterEndSeen = { endsSeen += it },
            )
            Renderer.WEB -> NovelWebViewport(
                context = activity,
                fontManager = activity.appGraph.novelFontManager,
                textSelectable = textSelectable,
                volumeKeysActive = { volumeKeysOn },
                useOriginalFonts = false,
                sourceCssPriority = false,
                onProgressChanged = { id, percent -> reports += ProgressReport(id, percent, settled = false) },
                onProgressSettled = { id, percent -> reports += ProgressReport(id, percent, settled = true) },
                onTopLine = { id, line -> topLines += id to line },
                onToggleMenu = {},
                onStepChapter = { steps += it },
                onVisibleChapter = { visibleChapters += it },
                onRetryBoundary = {},
                cutoutTopDp = { cutout },
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

    /**
     * A reader stopped inside a seam is in the chapter it introduces, since the screen below it is
     * entirely that chapter. Measured from the seam's middle: its last pixel is within the tolerance a
     * seek to the chapter's start is given, so it would pass without the rule.
     */
    @Test
    fun aReaderInsideASeamIsInTheChapterBelowIt() {
        open(chapter(FIRST, long("first")))
        append(chapter(SECOND, long("second")))
        bringSeamOnScreen()
        visibleChapters.clear()
        scrollToSeamsMiddle()
        assertEquals(SECOND, visibleChapters.lastOrNull())
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

    /** The model can drop a report (an open still landing), so reopening is its one chance to hear it. */
    @Test
    fun reopeningAChapterSaysItsEndWasSeenAgain() {
        open(chapter(FIRST, "<p>short</p>"))
        awaitWhile { endsSeen.isEmpty() }
        endsSeen.clear()
        open(chapter(FIRST, "<p>short</p>"))
        awaitWhile { endsSeen.isEmpty() }
        assertEquals(listOf(FIRST), endsSeen.toList())
    }

    @Test
    fun reopeningAChapterSaysWhetherItFitsAgain() {
        open(chapter(FIRST, "<p>short</p>"))
        fits.clear()
        open(chapter(FIRST, "<p>short</p>"))
        assertEquals(true, fits[FIRST])
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

    // region swiping between chapters (one rule, which both renderers implement)

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

    /** A text-size drag redraws on every step, so the question can outlive the redraw it waited on. */
    @Test
    fun paragraphsAskedDuringATextSizeDragAreAnsweredOnceTheLastStepHasRedrawn() {
        open(chapter(FIRST, "<p>one</p><p>two</p>"))
        val answer = runBlocking(Dispatchers.Main) {
            viewport.applySettings(readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 2))
            val asked = async(start = CoroutineStart.UNDISPATCHED) { viewport.readAloud.paragraphs(FIRST) }
            viewport.applySettings(readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 4))
            withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_S)) { asked.await() }
        }
        assertEquals(listOf("one", "two"), answer)
    }

    @Test
    fun theFirstVisibleParagraphOfAChapterJustOpenedIsItsFirst() {
        open(chapter(FIRST, long("first")))
        assertEquals(ReadAloudPosition(FIRST, 0), firstVisibleParagraph())
    }

    /** The in-reader sheet changes the volume keys mid-session, so a press reads the settings pushed last. */
    @Test
    fun aVolumeKeyScrollsTheWayTheLatestSettingsSay() {
        volumeKeysOn = true
        open(chapter(FIRST, long("first")))
        instrumentation.runOnMainSync { (viewport as ReaderViewport).seekTo(ChapterProgress.Percent(5_000)) }
        awaitScrollStill()
        instrumentation.runOnMainSync { viewport.applySettings(readerTestSettings.copy(volumeButtonsInverted = true)) }
        settle()
        val before = scrollOffset()
        instrumentation.runOnMainSync {
            (viewport as ReaderViewport).handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        }
        awaitScrollStill()
        val after = scrollOffset()
        assertTrue("an inverted volume-down moved the page from $before to $after", after < before)
    }

    /** A chapter that fits on screen has no room to seek within, so the rail lands on its start rather
     *  than leaving the reader where they were. */
    @Test
    fun aSeekInsideAChapterThatFitsLandsOnItsFirstLine() {
        open(chapter(FIRST, "<p>first 1. short</p>"))
        append(chapter(SECOND, long("second")))
        val start = checkNotNull(topLine()) { "no line at the top of the screen" }
        // Into the column's top margin only, so the short chapter is still the one on screen.
        scrollBy(readerTestSettings.margins.top / 2)
        instrumentation.runOnMainSync { (viewport as ReaderViewport).seekTo(ChapterProgress.Percent(5_000)) }
        awaitScrollStill()
        val landed = checkNotNull(topLine()) { "no line at the top of the screen" }
        assertTrue(
            "the short chapter's first line sat at ${start.y} and a seek left it at ${landed.y}",
            landed.paragraph == start.paragraph && abs(landed.y - start.y) <= EDGE_SLACK_PX,
        )
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

    /** The chrome covering the top of the text is not the screen, so reading from here starts below it. */
    @Test
    fun theFirstVisibleParagraphIsTheFirstBelowTheCoveredTop() {
        open(chapter(FIRST, long("first")), readerTestSettings.copy(ttsKeepInView = false))
        highlight(ReadAloudPosition(FIRST, 2))
        val (top, bottom) = checkNotNull(awaitMark())
        obscure(top = devicePx((top + bottom) / 2), bottom = 0)
        assertEquals(ReadAloudPosition(FIRST, 2), firstVisibleParagraph())
    }

    @Test
    fun followingAParagraphOffScreenPutsItsTopBelowTheCoveredTopWhenSetTo() {
        open(chapter(FIRST, long("first")), readerTestSettings.copy(ttsScrollToTop = true))
        obscure(top = devicePx(COVERED_UNITS), bottom = 0)
        highlight(ReadAloudPosition(FIRST, FAR_PARAGRAPH))
        awaitScrollStill()
        val (top, _) = checkNotNull(awaitMark())
        assertEquals(COVERED_UNITS, top, FOLLOW_SLACK_PX)
    }

    /** Wholly on the screen but half under the covered bottom, so it is followed, here to the top. */
    @Test
    fun aParagraphPartlyUnderTheCoveredBottomIsScrolledTo() {
        open(chapter(FIRST, long("first")), readerTestSettings.copy(ttsScrollToTop = true))
        highlight(ReadAloudPosition(FIRST, 2))
        val (top, bottom) = checkNotNull(awaitMark())
        obscure(top = 0, bottom = devicePx(viewportHeight() - (top + bottom) / 2))
        highlight(ReadAloudPosition(FIRST, 2))
        awaitScrollStill()
        val (followedTop, _) = checkNotNull(awaitMark())
        assertEquals(0f, followedTop, FOLLOW_SLACK_PX)
    }

    @Test
    fun followingAParagraphOffScreenCentresItBetweenTheCoveredEdges() {
        open(chapter(FIRST, long("first")))
        obscure(top = devicePx(COVERED_UNITS), bottom = devicePx(COVERED_UNITS * 2))
        highlight(ReadAloudPosition(FIRST, FAR_PARAGRAPH))
        awaitScrollStill()
        val (top, bottom) = checkNotNull(awaitMark())
        assertEquals((COVERED_UNITS + viewportHeight() - COVERED_UNITS * 2) / 2, (top + bottom) / 2, FOLLOW_SLACK_PX)
    }

    /**
     * The chrome coming up over the paragraph being spoken moves nothing: the reader may be reading it.
     * Part way in, so a follow the cover set off would have room to scroll either way.
     */
    @Test
    fun coveringTheTextMovesNothing() {
        open(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT))
        awaitScrollStill()
        highlight(checkNotNull(firstVisibleParagraph()))
        awaitScrollStill()
        val before = scrollOffset() to awaitMark()
        obscure(top = devicePx(viewportHeight() / 2), bottom = devicePx(viewportHeight() / 3))
        Thread.sleep(QUIET_MS)
        assertEquals(before, scrollOffset() to awaitMark())
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

    /** Line height and paragraph spacing well above one, so a mark drawn over the line box shows. */
    @Test
    fun aOneLineParagraphsMarkCoversItsGlyphsAndNotItsSpacing() {
        open(chapter(FIRST, SPACED), spacedSettings)
        highlight(ReadAloudPosition(FIRST, SPACED_ONE_LINE))
        awaitDrawnMark()
        val text = paragraphLines(SPACED_PARAGRAPHS[SPACED_ONE_LINE]).single()
        val next = paragraphLines(SPACED_PARAGRAPHS[SPACED_ONE_LINE + 1]).first()
        val painted = painted()
        Log.i(TAG, "$renderer one line: glyphs $text, next line top ${next.top}, painted $painted")
        assertTrue(
            "painted $painted over glyphs $text, next line at ${next.top}",
            painted.height <= text.height() * GLYPH_SLACK &&
                painted.bottom <= next.top &&
                painted.right <= text.right + EDGE_SLACK_PX,
        )
    }

    @Test
    fun aBackgroundMarkLeavesTheGapBetweenLinesUnpainted() {
        open(chapter(FIRST, SPACED), spacedSettings)
        highlight(ReadAloudPosition(FIRST, SPACED_LONG))
        awaitDrawnMark()
        val lines = paragraphLines(SPACED_PARAGRAPHS[SPACED_LONG])
        val painted = painted()
        val gaps = lines.zipWithNext { above, below ->
            (above.bottom.roundToInt() + 1) until below.top.roundToInt() - 1
        }
        val paintedGapRows = gaps.flatMap { gap -> painted.rows.filter { it in gap } }
        val unpaintedLines = lines.filter { line -> painted.rows.none { it >= line.top && it < line.bottom } }
        Log.i(TAG, "$renderer lines: glyphs $lines, painted $painted, painted gap rows ${paintedGapRows.size}")
        assertTrue(
            "lines $lines, painted gap rows $paintedGapRows, lines left unpainted $unpaintedLines",
            lines.size >= 3 && paintedGapRows.isEmpty() && unpaintedLines.isEmpty(),
        )
    }

    /** The first word of a paragraph three lines long, so a mark over the whole paragraph would show. */
    @Test
    fun aSentenceMarkCoversOnlyThatSentencesLine() {
        open(chapter(FIRST, SPACED), spacedSettings)
        highlight(ReadAloudPosition(FIRST, SPACED_LONG), 0 until "lorem".length)
        awaitDrawnMark()
        val lines = paragraphLines(SPACED_PARAGRAPHS[SPACED_LONG])
        val painted = painted()
        Log.i(TAG, "$renderer sentence: lines $lines, painted $painted")
        assertTrue(
            "painted $painted over lines $lines",
            lines.size >= 3 &&
                painted.height <= lines.first().height() * GLYPH_SLACK &&
                painted.bottom <= lines[1].top &&
                painted.right < lines.first().right - EDGE_SLACK_PX,
        )
    }

    @Test
    fun anOutlineEnclosesTheParagraphsTextWithinItsPad() {
        open(chapter(FIRST, SPACED), spacedSettings.copy(ttsHighlightStyle = TtsHighlightStyle.OUTLINE))
        highlight(ReadAloudPosition(FIRST, SPACED_LONG))
        awaitPainted()
        val outward = outlineOutward(SPACED_PARAGRAPHS[SPACED_LONG])
        assertTrue("outline reaches out by $outward", outward.all { abs(it - dp(OUTLINE_PAD_DP)) <= EDGE_SLACK_PX })
    }

    /** Native splits a chapter's text into chunk views, and a view clips what it draws to its own bounds. */
    @Test
    fun anOutlineAroundTheFirstParagraphOfALaterChunkKeepsItsPad() {
        open(chapter(FIRST, long("first")), tightOutline)
        val paragraph = checkNotNull(paragraphs(FIRST))[CHUNK_START_PARAGRAPH]
        assertAtChunkEdge { chunks -> chunks[1].text.startsWith(paragraph) }
        highlight(ReadAloudPosition(FIRST, CHUNK_START_PARAGRAPH))
        awaitPainted()
        val outward = outlineOutward(paragraph)
        assertTrue("outline reaches out by $outward", outward.all { abs(it - dp(OUTLINE_PAD_DP)) <= EDGE_SLACK_PX })
    }

    @Test
    fun anOutlineAroundTheLastParagraphOfAChunkKeepsItsPad() {
        open(chapter(FIRST, long("first")), tightOutline)
        val paragraph = checkNotNull(paragraphs(FIRST))[CHUNK_START_PARAGRAPH - 1]
        assertAtChunkEdge { chunks -> chunks[0].text.trimEnd().endsWith(paragraph) }
        highlight(ReadAloudPosition(FIRST, CHUNK_START_PARAGRAPH - 1))
        awaitPainted()
        val outward = outlineOutward(paragraph)
        assertTrue("outline reaches out by $outward", outward.all { abs(it - dp(OUTLINE_PAD_DP)) <= EDGE_SLACK_PX })
    }

    /**
     * With no top margin nothing inside the chapter makes room for the pad, which reaches over the seam above.
     * A chapter below another: the first chapter's pad would sit above the page, off screen in either renderer.
     */
    @Test
    fun anOutlineAroundAChaptersFirstParagraphWithNoTopMarginKeepsItsPad() {
        open(chapter(FIRST, "<p>Before.</p>"), tightOutline.copy(margins = tightOutline.margins.copy(top = 0)))
        append(chapter(SECOND, SPACED))
        highlight(ReadAloudPosition(SECOND, 0))
        awaitPainted()
        val outward = outlineOutward(SPACED_PARAGRAPHS[0])
        assertTrue("outline reaches out by $outward", outward.all { abs(it - dp(OUTLINE_PAD_DP)) <= EDGE_SLACK_PX })
    }

    /** Following off, so only the scroll this case makes moves the page. */
    @Test
    fun anOutlineMovesWithItsTextAsThePageScrolls() {
        open(chapter(FIRST, long("first")), tightOutline.copy(ttsKeepInView = false))
        val paragraph = checkNotNull(paragraphs(FIRST))[SCROLLED_PARAGRAPH]
        highlight(ReadAloudPosition(FIRST, SCROLLED_PARAGRAPH))
        awaitPainted()
        val before = paragraphLines(paragraph).first().top
        scrollBy(SCROLL_DP)
        val moved = before - paragraphLines(paragraph).first().top
        val outward = outlineOutward(paragraph)
        assertTrue(
            "text moved by $moved for a ${dp(SCROLL_DP)}px scroll, outline reaches out by $outward",
            abs(moved - dp(SCROLL_DP)) <= FOLLOW_SLACK_PX &&
                outward.all { abs(it - dp(OUTLINE_PAD_DP)) <= EDGE_SLACK_PX },
        )
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

    /**
     * A rebuilt renderer, as a rotation makes, lands the line the reader had at the top back at the top,
     * even once the text is laid out differently: a larger text size here, where the same percent is
     * somewhere else. The line reported after landing starts the line holding the one handed over.
     */
    @Test
    fun aChapterRebuiltAtALineLandsThatLineAtTheTop() {
        open(chapter(FIRST, long("first")))
        scrollBy(REBUILT_SCROLL_DP)
        Thread.sleep(QUIET_MS)
        val line = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        topLines.clear()
        fits.clear()
        open(
            chapter(FIRST, long("first")).copy(topLine = line),
            readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 6),
        )
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        val landed = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        assertTrue("handed $line, landed $landed", line - landed in 0..LINE_CHARS)
    }

    /**
     * A line is held across pictures landing above it, so a rebuilt renderer lands on it at once rather than
     * leaving the chapter's start on screen until they arrive, and the line stays once they do.
     */
    @Test
    fun aChapterRebuiltAtALineLandsBeforeAPictureAboveItArrives() {
        open(chapter(FIRST, long("first")))
        scrollBy(REBUILT_SCROLL_DP)
        Thread.sleep(QUIET_MS)
        val line = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        val picture = PngServer(pngOf(SLOW_PICTURE_PX, SLOW_PICTURE_PX)).also { server = it }
        val html = "<p><img src=\"${picture.url("slow", delayMs = SLOW_PICTURE_MS)}\"></p>" + long("first")
        topLines.clear()
        runBlocking(Dispatchers.Main) { viewport.load(chapter(FIRST, html).copy(topLine = line), readerTestSettings) }
        Thread.sleep(BEFORE_PICTURE_MS)
        val beforePicture = topLines.lastOrNull { it.first == FIRST }?.second
        Thread.sleep(SLOW_PICTURE_MS)
        val afterPicture = topLines.lastOrNull { it.first == FIRST }?.second
        assertTrue(
            "handed $line, reported $beforePicture before the picture and $afterPicture after",
            listOf(beforePicture, afterPicture).all { it != null && line - it in 0..LINE_CHARS },
        )
    }

    /**
     * A rotation lands the page before the cutout inset reaches it. The line is measured at the screen's top
     * rather than below the inset, or the report after the inset arrived named the line under the landed
     * one, and every rotation moved the reader a line further on.
     */
    @Test
    fun aChapterRebuiltAtALineStillReportsThatLineOnceTheInsetArrives() {
        open(chapter(FIRST, long("first")))
        scrollBy(REBUILT_SCROLL_DP)
        Thread.sleep(QUIET_MS)
        val line = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        topLines.clear()
        fits.clear()
        open(chapter(FIRST, long("first")).copy(topLine = line))
        awaitScrollStill()
        cutout = TALL_CUTOUT_DP
        deliverInsets()
        settle()
        Thread.sleep(QUIET_MS)
        val landed = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        assertTrue("handed $line, reported $landed", line - landed in 0..LINE_CHARS)
    }

    /**
     * A line on a chapter's last screen needs the chapter below for room, which joins after a rebuilt
     * renderer has landed. The line lands as far as it can and then exactly once that chapter joins.
     */
    @Test
    fun aChapterRebuiltAtALineOnItsLastScreenLandsThatLineOnceTheNextChapterJoins() {
        val line = lineOnTheLastScreen()
        open(chapter(FIRST, long("first")).copy(topLine = line))
        append(chapter(SECOND, long("second")))
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        val landed = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        assertTrue("handed $line, landed $landed", line - landed in 0..LINE_CHARS)
    }

    /** With nothing joining below, as for the novel's last chapter, the reader is never left unreported. */
    @Test
    fun aChapterRebuiltAtALineOnItsLastScreenWithNothingBelowStillReports() {
        val line = lineOnTheLastScreen()
        open(chapter(FIRST, long("first")).copy(topLine = line))
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        val landedReported = topLines.any { it.first == FIRST } && reports.any { it.chapterId == FIRST }
        topLines.clear()
        scrollBy(-SCROLL_DP)
        Thread.sleep(QUIET_MS)
        assertTrue(
            "reported on landing: $landedReported, after a scroll: ${topLines.toList()}",
            landedReported && topLines.any { it.first == FIRST },
        )
    }

    /** The second landing waits for room, and a reader who has moved off the line by then keeps their place. */
    @Test
    fun aReaderWhoMovesOffAShortLandingIsNotMovedWhenTheNextChapterJoins() {
        val line = lineOnTheLastScreen()
        open(chapter(FIRST, long("first")).copy(topLine = line))
        awaitScrollStill()
        scrollBy(-SCROLL_DP)
        Thread.sleep(QUIET_MS)
        val moved = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        append(chapter(SECOND, long("second")))
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        val after = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        assertTrue(
            "moved to $moved, then $after once the next chapter joined",
            moved == after && line - after > LINE_CHARS,
        )
    }

    /**
     * The top line of a place past where [FIRST] alone can scroll to, reached with the next chapter below:
     * its end on screen, then a little further. The window is cleared for the rebuild that follows.
     */
    private fun lineOnTheLastScreen(): Int {
        open(chapter(FIRST, long("first")))
        append(chapter(SECOND, long("second")))
        instrumentation.runOnMainSync { (viewport as ReaderViewport).seekTo(ChapterProgress.Percent(10_000)) }
        settle()
        scrollBy(LAST_SCREEN_SCROLL_DP)
        Thread.sleep(QUIET_MS)
        val line = checkNotNull(topLines.lastOrNull { it.first == FIRST }?.second)
        topLines.clear()
        reports.clear()
        fits.clear()
        return line
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

    /** Footnotes sit above a chapter's pictures as often as below them, and the saved place waits for those. */
    @Test
    fun aFootnoteJumpWhileTheSavedPlaceWaitsForPicturesStaysAtTheNote() {
        runBlocking(Dispatchers.Main) {
            viewport.load(
                illustratedChapter(
                    SAVED_PERCENT,
                    STALLED_IMAGE_DELAYS_MS,
                    // Above the pictures, so they arriving cannot push the note down whatever the landing does.
                    before = "<p><a href=\"#note\">$JUMP_LINK</a></p>${long("filler")}<p id=\"note\">$NOTE</p>",
                ),
                readerTestSettings,
            )
        }
        awaitWhile { !textShown() }
        settle()
        tapOn(JUMP_LINK)
        awaitNoteAtTop()
        awaitIllustratedLanding()
        assertEquals(viewTop().toFloat(), textBox(NOTE).top, textSizePx() * 3)
    }

    /** A page that fits never scrolls again, so a position it said before the host listened was never said. */
    @Test
    fun aChapterThatFitsOpenedAtASavedPlaceReportsWhereItIs() {
        open(chapter(FIRST, "<p>A short chapter.</p>", progressPercent = SAVED_PERCENT))
        awaitScrollStill()
        assertEquals(0, reports.lastOrNull { it.chapterId == FIRST }?.percent)
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

    /**
     * A picture whose request never answers holds the saved place back only for the image wait, after
     * which it lands on the chapter as it measures without the picture. Waiting on for it left the reader
     * at the chapter's top, reporting nothing.
     */
    @Test
    fun aSavedPlaceLandsWithinTheImageWaitWhenAPictureNeverArrives() {
        val openedAt = SystemClock.uptimeMillis()
        runBlocking(Dispatchers.Main) {
            viewport.load(illustratedChapter(SAVED_PERCENT, held = true), readerTestSettings)
        }
        awaitWhile { reports.none { it.chapterId == FIRST } }
        val reportedAfter = SystemClock.uptimeMillis() - openedAt
        assertTrue(
            "the first report came ${reportedAfter}ms after the open",
            reportedAfter <= CHAPTER_IMAGE_WAIT_MS + IMAGE_WAIT_GRACE_MS,
        )
        awaitScrollStill()
        assertEquals(SAVED_PERCENT.toFloat(), lastReported(FIRST), LANDING_SLACK_PERCENT)
    }

    /**
     * A top line the chapter's text does not reach, as a count from other text can be, lands on the saved
     * share as the page lands it. Landing at the chapter's end reported all of it, which reads the chapter.
     */
    @Test
    fun aTopLinePastTheChaptersEndLandsOnTheSavedShare() {
        open(chapter(FIRST, long("first"), progressPercent = SAVED_PERCENT).copy(topLine = PAST_THE_END))
        awaitScrollStill()
        Thread.sleep(QUIET_MS)
        assertEquals(SAVED_PERCENT.toFloat(), firstReported(FIRST), LANDING_SLACK_PERCENT)
    }

    /** Until its pictures land a long illustrated chapter measures short, and would be read on opening. */
    @Test
    fun anIllustratedChapterSaysNothingAboutItsEndWhileItsPicturesLoad() {
        val pictures = PngServer(pngOf(400, 1600), held = true).also { server = it }
        runBlocking(Dispatchers.Main) {
            viewport.load(chapter(FIRST, "<p>$SHORT_PARAGRAPH</p><img src=\"${pictures.url}\">"), readerTestSettings)
        }
        awaitWhile { !textShown() }
        settle()
        Thread.sleep(QUIET_MS)
        val whileLoading = endsSeen.toList()
        pictures.release()
        awaitWhile { !fits.containsKey(FIRST) }
        settle()
        assertEquals(emptyList<Long>() to emptyList<Long>(), whileLoading to endsSeen.toList())
    }

    /** Held the same way, since a forward step reads a chapter that fits. */
    @Test
    fun anIllustratedChapterSaysWhetherItFitsOnlyOnceItsPicturesArrive() {
        val pictures = PngServer(pngOf(400, 1600), held = true).also { server = it }
        runBlocking(Dispatchers.Main) {
            viewport.load(chapter(FIRST, "<p>$SHORT_PARAGRAPH</p><img src=\"${pictures.url}\">"), readerTestSettings)
        }
        awaitWhile { !textShown() }
        settle()
        Thread.sleep(QUIET_MS)
        val whileLoading = fits[FIRST]
        pictures.release()
        awaitWhile { !fits.containsKey(FIRST) }
        assertEquals(null to false, whileLoading to fits[FIRST])
    }

    /**
     * A failed picture's box is the reader's own words, which the top line counts no more than the text
     * renderer does: a line above or below it is named by the same count either way.
     */
    @Test
    fun aFailedPictureAboveTheTopLineAddsNothingToItsCount() {
        val picture = PngServer(pngOf(SMALL_IMAGE_PX, SMALL_IMAGE_PX), failFirst = Int.MAX_VALUE, held = true)
            .also { server = it }
        runBlocking(Dispatchers.Main) {
            viewport.load(
                chapter(FIRST, "<p>$SHORT_PARAGRAPH</p><img src=\"${picture.url}\">" + long("first")),
                readerTestSettings,
            )
        }
        awaitWhile { !textShown() }
        // Past the wait on the pictures, so the opening landing is done with before the reader scrolls.
        Thread.sleep(CHAPTER_IMAGE_WAIT_MS + IMAGE_WAIT_GRACE_MS)
        scrollBy(REBUILT_SCROLL_DP)
        Thread.sleep(QUIET_MS)
        val before = topLines.lastOrNull { it.first == FIRST }?.second
        picture.release()
        awaitWhile { imageFailure() == null }
        awaitScrollStill()
        scrollBy(SCROLL_DP)
        scrollBy(-SCROLL_DP)
        Thread.sleep(QUIET_MS)
        val after = topLines.lastOrNull { it.first == FIRST }?.second
        assertTrue("no line was reported before the box", before != null)
        assertEquals(before, after)
    }

    /** Further in, where a landing measured against the placeholders would fall further short. */
    @Test
    fun anIllustratedChapterOpenedNearItsEndLandsAtItsSavedPosition() {
        openIllustrated(LATE_PERCENT)
        assertEquals(LATE_PERCENT.toFloat(), landedPercent(), LANDING_SLACK_PERCENT)
    }

    // endregion

    // region typography

    /** The line height setting is a multiple of the text size, as CSS reads it, in both renderers. */
    @Test
    fun aParagraphsLinesAreTheLineHeightTimesTheTextSizeApart() {
        open(chapter(FIRST, "<p>$WRAPPING_PARAGRAPH</p>"))
        val tops = paragraphLines(WRAPPING_PARAGRAPH).map { it.top }
        assertTrue("the paragraph wraps", tops.size >= 3)
        assertEquals(readerTestSettings.lineHeight * textSizePx(), tops[2] - tops[1], TYPE_SLACK_PX)
    }

    @Test
    fun paragraphsAreOneLineAndTheParagraphSpacingApart() {
        open(chapter(FIRST, "<p>$SHORT_PARAGRAPH</p><p>$WRAPPING_PARAGRAPH</p>"))
        val gap = paragraphLines(WRAPPING_PARAGRAPH).first().top - paragraphLines(SHORT_PARAGRAPH).last().top
        val expected = (readerTestSettings.lineHeight + readerTestSettings.paragraphSpacing) * textSizePx()
        assertEquals(expected, gap, TYPE_SLACK_PX)
    }

    @Test
    fun aLinkIsDrawnInTheTextColour() {
        open(chapter(FIRST, "<p>See <a href=\"https://example.com/\">the site</a>.</p>"))
        val expected = android.graphics.Color.parseColor(readerTestSettings.textColor)
        assertEquals(Integer.toHexString(expected), Integer.toHexString(linkColour()))
    }

    @Test
    fun aSuperscriptIsSetSmallerThanItsText() {
        open(chapter(FIRST, "<p>Mass 88<sup>77</sup> kilograms.</p>"))
        assertTrue(runWidth("77") < runWidth("88") * SCRIPT_MAX_RATIO)
    }

    /** A picture narrower than the column is drawn at its own size, as a page draws one. */
    @Test
    fun aSmallImageKeepsItsOwnWidth() {
        val picture = PngServer(pngOf(SMALL_IMAGE_PX, SMALL_IMAGE_PX)).also { server = it }
        open(chapter(FIRST, "<p>$SHORT_PARAGRAPH</p><img src=\"${picture.url}\">"))
        awaitWhile { firstImageWidth() <= 0f }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertEquals(SMALL_IMAGE_PX * density, firstImageWidth(), TYPE_SLACK_PX)
    }

    /** The manga reader's failed page, in the picture's place: a box saying so, with Retry. */
    @Test
    fun aPictureThatFailsIsShownAsAFailureWithRetry() {
        val picture = PngServer(pngOf(SMALL_IMAGE_PX, SMALL_IMAGE_PX), failFirst = Int.MAX_VALUE).also { server = it }
        open(chapter(FIRST, "<p>$SHORT_PARAGRAPH</p><img src=\"${picture.url}\"><p>$BELOW_RULE</p>"))
        awaitWhile { imageFailure() == null }
        assertEquals(true, imageFailure())
    }

    @Test
    fun aTapOnAFailedPicturesRetryLoadsIt() {
        val picture = PngServer(pngOf(SMALL_IMAGE_PX, SMALL_IMAGE_PX), failFirst = Int.MAX_VALUE).also { server = it }
        open(chapter(FIRST, "<p>$SHORT_PARAGRAPH</p><img src=\"${picture.url}\"><p>$BELOW_RULE</p>"))
        awaitWhile { imageFailure() == null }
        picture.recover()
        // Nothing but the tap may ask again: a render landing late would load it with no Retry at all.
        Thread.sleep(QUIET_MS)
        assertEquals("the picture loaded before the tap", true, imageFailure())
        val failed = failedPictureHolder()
        // The box changed the chapter's height, so Retry is found once the reader has stopped moving.
        awaitScrollStill()
        val retry = retryCentre()
        val at = viewLocation()
        tap(retry.x - at[0], retry.y - at[1])
        awaitWhile { !loadedInPlace(failed) }
        assertTrue("the picture never loaded where it failed", loadedInPlace(failed))
    }

    /** An inline picture has nowhere to be asked for again. */
    @Test
    fun anInlinePictureThatCannotBeReadOffersNoRetry() {
        open(chapter(FIRST, "<p>$SHORT_PARAGRAPH</p><img src=\"data:image/png;base64,AAAA\"><p>$BELOW_RULE</p>"))
        awaitWhile { imageFailure() == null }
        assertEquals(false, imageFailure())
    }

    /** Tighter than the font, the text's own glyphs reach past its line, and the picture still clears them. */
    @Test
    fun aPictureStaysClearOfTheTextAboveItAtATightLineHeight() {
        val picture = PngServer(pngOf(SMALL_IMAGE_PX, SMALL_IMAGE_PX)).also { server = it }
        open(chapter(FIRST, "<p>$ABOVE_RULE</p><img src=\"${picture.url}\"><p>$BELOW_RULE</p>"), tightLines)
        awaitWhile { firstImageWidth() <= 0f }
        settle()
        assertTrue(
            "the picture starts above the text's glyphs",
            pictureTops().first() >= glyphBottom(ABOVE_RULE) - TYPE_SLACK_PX,
        )
    }

    /** An img's margin, 1em, collapses between two in a row, whatever the line spacing. */
    @Test
    fun twoPicturesInARowStandAnEmApart() {
        val picture = PngServer(pngOf(SMALL_IMAGE_PX, SMALL_IMAGE_PX)).also { server = it }
        val images = "<img src=\"${picture.url("a")}\"><img src=\"${picture.url("b")}\">"
        open(chapter(FIRST, "<p>$ABOVE_RULE</p>$images<p>$BELOW_RULE</p>"), tightLines)
        awaitWhile { pictureTops().size < 2 || firstImageWidth() <= 0f }
        settle()
        val (first, second) = pictureTops()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertEquals(SMALL_IMAGE_PX * density + textSizePx(), second - first, TYPE_SLACK_PX * 2)
    }

    /** Against bold body text, since both renderers set a heading bold. */
    @Test
    fun aTopLevelHeadingIsSetAtTwiceTheTextSize() {
        open(chapter(FIRST, "<p><b>$HEADING_RUN</b></p>"))
        val body = runWidth(HEADING_RUN)
        open(chapter(SECOND, "<h1>$HEADING_RUN</h1>"))
        assertEquals(body * 2, runWidth(HEADING_RUN), body * 2 * SIZE_SLACK)
    }

    @Test
    fun aSixthLevelHeadingIsSetSmallerThanTheText() {
        open(chapter(FIRST, "<p><b>$HEADING_RUN</b></p>"))
        val body = runWidth(HEADING_RUN)
        open(chapter(SECOND, "<h6>$HEADING_RUN</h6>"))
        assertEquals(body * 0.67f, runWidth(HEADING_RUN), body * 0.67f * SIZE_SLACK)
    }

    @Test
    fun aRuleStandsAtLeastALineBetweenTheParagraphsAroundIt() {
        open(chapter(FIRST, "<p>$ABOVE_RULE</p><p>$BELOW_RULE</p>"))
        val plain = textBox(BELOW_RULE).top - textBox(ABOVE_RULE).top
        open(chapter(SECOND, "<p>$ABOVE_RULE</p><hr><p>$BELOW_RULE</p>"))
        val ruled = textBox(BELOW_RULE).top - textBox(ABOVE_RULE).top
        assertTrue("$ruled against $plain", ruled - plain >= readerTestSettings.lineHeight * textSizePx())
    }

    /** The space alone once passed while the line itself had been dropped. */
    @Test
    fun aRuleIsDrawnAcrossTheColumn() {
        open(chapter(FIRST, "<p>$ABOVE_RULE</p><hr><p>$BELOW_RULE</p>"))
        val from = textBox(ABOVE_RULE).bottom.roundToInt()
        val to = textBox(BELOW_RULE).top.roundToInt()
        val background = android.graphics.Color.parseColor(readerTestSettings.backgroundColor)
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val row = IntArray(shot.width)
        val drawn = (from until to).any { y ->
            shot.getPixels(row, 0, shot.width, 0, y, shot.width, 1)
            row.count { it != background } > shot.width / 2
        }
        shot.recycle()
        assertTrue("no rule between y $from and $to", drawn)
    }

    /** Wider than its reading, so a reading set beside the base would widen the run by its own width. */
    @Test
    fun aRubyReadingIsSetAboveItsBaseRatherThanBesideIt() {
        // The plain run comes first, since the box found is the first one reading it.
        val ruby = "<ruby>$RUBY_REFERENCE<rt>reading</rt></ruby>"
        open(chapter(FIRST, "<p>A $RUBY_REFERENCE alone.</p><p>xx${ruby}yy after it.</p>"))
        assertEquals(runWidth(RUBY_REFERENCE), rubyWidth(), TYPE_SLACK_PX)
    }

    /** A reading wider than its base, so the ruby is as wide as the reading is set, which is half size. */
    @Test
    fun aRubyReadingIsSetAtHalfTheTextSize() {
        open(chapter(FIRST, "<p>A $LONG_READING alone.</p><p>xx<ruby>W<rt>$LONG_READING</rt></ruby>yy after it.</p>"))
        assertEquals(runWidth(LONG_READING) / 2, readingWidth(), TYPE_SLACK_PX * 2)
    }

    /** Sources number their footnotes per chapter, so the chapter above often repeats the same id. */
    @Test
    fun aLinkToAPlaceInTheChapterScrollsWithinItsOwnChapter() {
        val target = "<p id=\"note\">$NOTE</p>"
        open(chapter(SECOND, "<p><a href=\"#note\">$JUMP_LINK</a></p>${long("filler")}$target${long("after")}"))
        prepend(chapter(FIRST, "${long("before")}<p id=\"note\">$OTHER_NOTE</p>"))
        awaitScrollStill()
        val link = textBox(JUMP_LINK)
        val at = IntArray(2).also { a -> instrumentation.runOnMainSync { view.getLocationOnScreen(a) } }
        tap(link.centerX() - at[0], link.centerY() - at[1])
        awaitWhile { abs(textBox(NOTE).top - at[1]) > textSizePx() * 3 }
        awaitScrollStill()
        assertEquals(at[1].toFloat(), textBox(NOTE).top, textSizePx() * 3)
    }

    @Test
    fun aLinkToAPlaceInTheChapterScrollsThere() {
        val target = "<p id=\"note\">$NOTE</p>"
        open(chapter(FIRST, "<p><a href=\"#note\">$JUMP_LINK</a></p>${long("filler")}$target${long("after")}"))
        val link = textBox(JUMP_LINK)
        val at = IntArray(2).also { a -> instrumentation.runOnMainSync { view.getLocationOnScreen(a) } }
        tap(link.centerX() - at[0], link.centerY() - at[1])
        awaitWhile { abs(textBox(NOTE).top - at[1]) > textSizePx() * 3 }
        // Past any scroll the same tap could start as a tap zone, which is how a link tap once undid its jump.
        awaitScrollStill()
        assertEquals(at[1].toFloat(), textBox(NOTE).top, textSizePx() * 3)
    }

    /** A "back to top" link names no target, and jsoup throwing on it cost native every other link. */
    @Test
    fun aBareHashLinkLeavesTheOtherLinksInTheChapterWorking() {
        val target = "<p id=\"note\">$NOTE</p>"
        open(
            chapter(
                FIRST,
                "<p><a href=\"#\">top</a></p><p><a href=\"#note\">$JUMP_LINK</a></p>${long(
                    "filler",
                )}$target${long("after")}",
            ),
        )
        tapOn(JUMP_LINK)
        awaitNoteAtTop()
        assertEquals(viewTop().toFloat(), textBox(NOTE).top, textSizePx() * 3)
    }

    @Test
    fun aLinkNamingItsTargetWithAnEscapeScrollsThere() {
        val target = "<p id=\"note one\">$NOTE</p>"
        open(chapter(FIRST, "<p><a href=\"#note%20one\">$JUMP_LINK</a></p>${long("filler")}$target${long("after")}"))
        tapOn(JUMP_LINK)
        awaitNoteAtTop()
        assertEquals(viewTop().toFloat(), textBox(NOTE).top, textSizePx() * 3)
    }

    /** Not a valid escape, which the page used to throw on after it had already cancelled the tap. */
    @Test
    fun aLinkWhoseNameHoldsAPercentSignScrollsThere() {
        val target = "<p id=\"100%\">$NOTE</p>"
        open(chapter(FIRST, "<p><a href=\"#100%\">$JUMP_LINK</a></p>${long("filler")}$target${long("after")}"))
        tapOn(JUMP_LINK)
        awaitNoteAtTop()
        assertEquals(viewTop().toFloat(), textBox(NOTE).top, textSizePx() * 3)
    }

    @Test
    fun aLinkToAPlaceTheChapterDoesNotHaveOpensNothing() {
        val monitor = blockBrowser()
        open(chapter(FIRST, "<p><a href=\"#missing\">$JUMP_LINK</a></p>${long("filler")}", baseUrl = BASE_URL))
        tapOn(JUMP_LINK)
        settle()
        instrumentation.removeMonitor(monitor)
        assertEquals(0, monitor.hits)
    }

    /** An empty href resolves to the chapter's own page on the source's site. */
    @Test
    fun aLinkWithAnEmptyHrefOpensNothing() {
        val monitor = blockBrowser()
        open(chapter(FIRST, "<p><a href=\"\">$JUMP_LINK</a></p>${long("filler")}", baseUrl = BASE_URL))
        tapOn(JUMP_LINK)
        settle()
        instrumentation.removeMonitor(monitor)
        assertEquals(0, monitor.hits)
    }

    @Test
    fun aTapInTheBlankPastALineEndingLinkDoesNotFollowIt() {
        val target = "<p id=\"note\">$NOTE</p>"
        open(chapter(FIRST, "<p>Then <a href=\"#note\">$JUMP_LINK</a></p>${long("filler")}$target${long("after")}"))
        val link = textBox(JUMP_LINK)
        val at = viewLocation()
        tap(minOf(link.right + textSizePx() * 3, (at[0] + view.width - 1).toFloat()) - at[0], link.centerY() - at[1])
        settle()
        awaitScrollStill()
        assertEquals(link.top, textBox(JUMP_LINK).top, textSizePx())
    }

    private fun tapOn(text: String) {
        val link = textBox(text)
        val at = viewLocation()
        tap(link.centerX() - at[0], link.centerY() - at[1])
    }

    /** Past any scroll the tap could also start as a tap zone. */
    private fun awaitNoteAtTop() {
        awaitWhile { abs(textBox(NOTE).top - viewTop()) > textSizePx() * 3 }
        awaitScrollStill()
    }

    private fun viewLocation(): IntArray = IntArray(2).also { a ->
        instrumentation.runOnMainSync { view.getLocationOnScreen(a) }
    }

    private fun viewTop(): Int = viewLocation()[1]

    /** Catches, and stops, any web page the tap would open in the browser. */
    private fun blockBrowser(): Instrumentation.ActivityMonitor {
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("http")
            addDataScheme("https")
        }
        return instrumentation.addMonitor(filter, null, true)
    }

    private fun tap(x: Float, y: Float) {
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, x, y, down, down)
            touch(MotionEvent.ACTION_UP, x, y, down, down + DRAG_STEP_MS)
        }
    }

    /** The on-screen box of the first run reading [text] in the chapter, a line's height tall. */
    private fun textBox(text: String): RectF = when (renderer) {
        Renderer.NATIVE -> {
            lateinit var box: RectF
            instrumentation.runOnMainSync {
                val chunk = textViews().first { it.text.contains(text) }
                val start = chunk.text.indexOf(text)
                val layout = chunk.layout
                val line = layout.getLineForOffset(start)
                val at = IntArray(2).also(chunk::getLocationOnScreen)
                val x = (at[0] + chunk.totalPaddingLeft).toFloat()
                val y = (at[1] + chunk.totalPaddingTop).toFloat()
                box = RectF(
                    x + layout.getPrimaryHorizontal(start),
                    y + layout.getLineTop(line),
                    x + layout.getPrimaryHorizontal(start + text.length),
                    y + layout.getLineBottom(line),
                )
            }
            box
        }
        Renderer.WEB -> {
            val at = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(at) }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val rect = JSONArray(
                eval(
                    "(function () { var t = ${JSONObject.quote(text)};" +
                        " var root = document.getElementById('rk-chapters');" +
                        " var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);" +
                        " var n; while ((n = w.nextNode())) { var i = n.data.indexOf(t); if (i < 0) continue;" +
                        " var r = document.createRange(); r.setStart(n, i); r.setEnd(n, i + t.length);" +
                        " var b = r.getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom]; }" +
                        " return [0, 0, 0, 0]; })()",
                ),
            )
            RectF(
                at[0] + rect.getDouble(0).toFloat() * density,
                at[1] + rect.getDouble(1).toFloat() * density,
                at[0] + rect.getDouble(2).toFloat() * density,
                at[1] + rect.getDouble(3).toFloat() * density,
            )
        }
    }

    /**
     * How wide the first run reading [text] is set. Native measures the characters with their spans,
     * since a precomputed layout reports positions only at word boundaries.
     */
    private fun runWidth(text: String): Float = when (renderer) {
        Renderer.NATIVE -> {
            var width = 0f
            instrumentation.runOnMainSync {
                val chunk = textViews().first { it.text.contains(text) }
                val start = chunk.text.indexOf(text)
                width = withoutParagraphStyles(chunk.text as Spanned, start, start + text.length, chunk)
            }
            width
        }
        Renderer.WEB -> textBox(text).width()
    }

    /** The run's own width: a paragraph's indent is otherwise counted into the run that opens its line. */
    private fun withoutParagraphStyles(text: Spanned, start: Int, end: Int, chunk: TextView): Float {
        val run = SpannableStringBuilder(text.subSequence(start, end))
        run.getSpans(0, run.length, ParagraphStyle::class.java).forEach(run::removeSpan)
        return Layout.getDesiredWidth(run, chunk.paint)
    }

    /**
     * How wide the chapter's ruby reading is set. The page's own box, since a browser lets a reading overhang
     * the letters beside its base; native draws the reading over its base, as wide as the ruby when wider.
     */
    private fun readingWidth(): Float = when (renderer) {
        Renderer.NATIVE -> rubyWidth()
        Renderer.WEB -> eval("document.querySelector('.rk-chapter rt').getBoundingClientRect().width").toFloat() *
            instrumentation.targetContext.resources.displayMetrics.density
    }

    /** How wide the chapter's ruby is set, its reading included. */
    private fun rubyWidth(): Float = when (renderer) {
        Renderer.NATIVE -> {
            var width = 0f
            instrumentation.runOnMainSync {
                val chunk = textViews().first { view ->
                    (view.text as Spanned).getSpans(0, view.text.length, RubySpan::class.java).isNotEmpty()
                }
                val text = chunk.text as Spanned
                val ruby = text.getSpans(0, text.length, RubySpan::class.java).single()
                width = withoutParagraphStyles(text, text.getSpanStart(ruby), text.getSpanEnd(ruby), chunk)
            }
            width
        }
        Renderer.WEB -> {
            val density = instrumentation.targetContext.resources.displayMetrics.density
            eval("document.querySelector('.rk-chapter ruby').getBoundingClientRect().width").toFloat() * density
        }
    }

    /** Every text view the viewport shows, whatever it reads. */
    private fun textViews(): List<TextView> = descendants(view).filterIsInstance<TextView>().filter { it.isShown }

    /** Stored by LNReader's Black preset with its alpha last, as CSS reads eight digits. */
    @Test
    fun anEightDigitTextColourIsDrawnWithItsAlphaLast() {
        open(chapter(FIRST, "<p>Some text.</p>"), readerTestSettings.copy(textColor = "#FFFFFFB3"))
        assertEquals(Integer.toHexString(0xB3FFFFFF.toInt()), Integer.toHexString(textColour()))
    }

    /** A restored value that names no colour: native drew white while the page drew the dark default. */
    @Test
    fun anUnreadableBackgroundColourDrawsTheDefaultDarkPage() {
        open(chapter(FIRST, "<p>Some text.</p>"), readerTestSettings.copy(backgroundColor = "not a colour"))
        assertEquals(
            Integer.toHexString(android.graphics.Color.parseColor(readerDarkPreset.background)),
            Integer.toHexString(pageColour()),
        )
    }

    private fun pageColour(): Int = when (renderer) {
        Renderer.NATIVE -> {
            var colour = 0
            instrumentation.runOnMainSync {
                colour = (descendants(view).filterIsInstance<RecyclerView>().first().background as ColorDrawable).color
            }
            colour
        }
        Renderer.WEB -> {
            val rgb = eval("getComputedStyle(document.body).backgroundColor")
                .trim('"').substringAfter("(").removeSuffix(")")
                .split(",").map { it.trim().toInt() }
            android.graphics.Color.rgb(rgb[0], rgb[1], rgb[2])
        }
    }

    private fun textColour(): Int = when (renderer) {
        Renderer.NATIVE -> {
            var colour = 0
            instrumentation.runOnMainSync { colour = textViews().first().currentTextColor }
            colour
        }
        Renderer.WEB -> {
            val parts = eval("getComputedStyle(document.querySelector('.rk-chapter p')).color")
                .trim('"').substringAfter("(").removeSuffix(")")
                .split(",").map { it.trim() }
            val alpha = parts.getOrNull(3)?.toFloat()?.let { (it * 255).roundToInt() } ?: 255
            android.graphics.Color.argb(alpha, parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }

    private fun linkColour(): Int = when (renderer) {
        Renderer.NATIVE -> {
            var colour = 0
            instrumentation.runOnMainSync { colour = textViews().first().linkTextColors.defaultColor }
            colour
        }
        Renderer.WEB -> {
            val rgb = eval("getComputedStyle(document.querySelector('.rk-chapter a')).color")
                .trim('"')
                .removePrefix("rgb(").removeSuffix(")")
                .split(",").map { it.trim().toInt() }
            android.graphics.Color.rgb(rgb[0], rgb[1], rgb[2])
        }
    }

    /** The test settings' text size on screen, which each renderer scales by the system font size. */
    private fun textSizePx(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        readerTestSettings.fontSize.toFloat(),
        instrumentation.targetContext.resources.displayMetrics,
    )

    // endregion

    // region keeping the reader's place

    /** The host adds the chapter after a short one first, so there is room to keep the short chapter's
     *  first line where it was when the one before it arrives. */
    @Test
    fun aShortChapterOpenedBetweenTwoKeepsItsFirstLineInPlace() {
        open(chapter(SECOND, "<p>second 1. short</p>"))
        val before = checkNotNull(topLine()) { "no line at the top of the screen" }
        append(chapter(THIRD, long("third")))
        prepend(chapter(FIRST, long("first")))
        settle()
        val after = checkNotNull(topLine()) { "no line at the top of the screen" }
        assertTrue(
            "the short chapter's first line was $before and is $after",
            after.paragraph == before.paragraph && abs(after.y - before.y) <= EDGE_SLACK_PX,
        )
    }

    /** An open during an Activity recreation runs before the window has insets and reads zero, so the
     *  inset has to land when the insets do. */
    @Test
    fun aCutoutInsetThatArrivesAfterTheOpenReachesThePage() {
        open(chapter(FIRST, long("first")))
        cutout = CUTOUT_DP
        deliverInsets()
        settle()
        assertEquals(inPagePixels(CUTOUT_DP), topInset(), TYPE_SLACK_PX)
    }

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

    /** Pictures landing above the reader grow the chapter there, and the line on screen stays put. */
    @Test
    fun theLineAtTheTopStaysThereWhenPicturesAboveItArrive() {
        assertTopLineHeldAsPicturesArrive()
    }

    /** Selectable text re-sets its text rather than a precomputed layout when pictures land. */
    @Test
    fun theLineAtTheTopStaysThereWhenPicturesAboveItArriveWithTextSelectable() {
        useSelectableText()
        assertTopLineHeldAsPicturesArrive()
    }

    private fun assertTopLineHeldAsPicturesArrive() {
        runBlocking(Dispatchers.Main) {
            viewport.load(illustratedChapter(0, held = true), readerTestSettings)
        }
        awaitWhile { !textShown() }
        // Dragged rather than scrolled, so the opening landing takes the reader as having moved on.
        repeat(PICTURE_PASS_DRAGS) {
            drag(
                fromX = view.width / 2f,
                toX = view.width / 2f,
                fromY = view.height * 0.9f,
                toY = view.height * 0.1f,
                holdMs = FLING_FREE_HOLD_MS,
            )
            awaitScrollStill()
        }
        val before = straddledTopLine()
        val paragraph = before.paragraph.filter(Char::isDigit).toInt()
        assertTrue(
            "the reader at ${before.paragraph} is above every picture",
            paragraph > IMAGE_AFTER_PARAGRAPH.first(),
        )
        assertTrue("the pictures arrived before the reader moved", !imagesArrived())
        checkNotNull(server).release()
        awaitIllustratedLanding()
        val after = lineTop(before.paragraph, before.offset)
        assertEquals("the line at ${before.paragraph} +${before.offset}", before.y, after, HOLD_SLACK_PX)
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
        before: String = "",
        after: String = "",
        held: Boolean = false,
    ): NovelReaderViewModel.LoadedChapter {
        val pictures = PngServer(pngOf(400, 1600), held = held).also { server = it }
        val urls = delaysMs.mapIndexed { index, delay -> pictures.url("picture$index", delay) }
        val html = (1..120).joinToString("") { paragraph ->
            val picture = IMAGE_AFTER_PARAGRAPH.indexOf(paragraph).takeIf {
                it >= 0
            }?.let { "<img src=\"${urls[it]}\">" }
            "<p>first $paragraph. " + "lorem ipsum dolor sit amet ".repeat(8) + "</p>" + picture.orEmpty()
        }
        return chapter(FIRST, before + html + after, progressPercent = percent)
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

    /** Where each picture's drawn box starts on screen, in chapter order. */
    private fun pictureTops(): List<Float> = when (renderer) {
        Renderer.NATIVE -> {
            val tops = mutableListOf<Float>()
            instrumentation.runOnMainSync {
                textViews().forEach { chunk ->
                    val text = chunk.text as? Spanned ?: return@forEach
                    val at = IntArray(2).also(chunk::getLocationOnScreen)
                    text.getSpans(0, text.length, ChapterImageSpan::class.java).sortedBy(text::getSpanStart).forEach {
                        val line = chunk.layout.getLineForOffset(text.getSpanStart(it))
                        tops += at[1] + chunk.totalPaddingTop + chunk.layout.getLineTop(line) + it.topPx.toFloat()
                    }
                }
            }
            tops
        }
        Renderer.WEB -> {
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val top = viewTop()
            val rects =
                JSONArray(eval("[...document.images].map(function (m) { return m.getBoundingClientRect().top; })"))
            (0 until rects.length()).map { top + rects.getDouble(it).toFloat() * density }
        }
    }

    /** The bottom of the glyphs of the first run reading [text], below which nothing of it is drawn. */
    private fun glyphBottom(text: String): Float = when (renderer) {
        Renderer.NATIVE -> {
            var bottom = 0f
            instrumentation.runOnMainSync {
                val chunk = textViews().first { it.text.contains(text) }
                val line = chunk.layout.getLineForOffset(chunk.text.indexOf(text))
                val at = IntArray(2).also(chunk::getLocationOnScreen)
                bottom =
                    at[1] + chunk.totalPaddingTop + chunk.layout.getLineBaseline(line) + chunk.paint.fontMetrics.descent
            }
            bottom
        }
        Renderer.WEB -> textBox(text).bottom
    }

    /** Null while no picture has failed, else whether its box offers Retry. */
    private fun imageFailure(): Boolean? = when (renderer) {
        Renderer.NATIVE -> {
            var failure: Boolean? = null
            instrumentation.runOnMainSync {
                val chunk = textViews().firstOrNull { chunk ->
                    (chunk.text as? Spanned)?.getSpans(0, chunk.text.length, ImageSpan::class.java)?.isNotEmpty() ==
                        true
                } ?: return@runOnMainSync
                val text = chunk.text as Spanned
                val image = text.getSpans(0, text.length, ImageSpan::class.java).first()
                if ((image.drawable as DrawableWrapper).innerDrawable !is ImageFailureDrawable) return@runOnMainSync
                failure = text.getSpans(text.getSpanStart(image), text.getSpanEnd(image), ClickableSpan::class.java)
                    .isNotEmpty()
            }
            failure
        }
        Renderer.WEB -> when (
            eval(
                "(function () { var b = document.querySelector('.rk-image-failure');" +
                    " return b ? String(!!b.querySelector('.rk-failure-retry')) : 'none'; })()",
            ).trim('"')
        ) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /**
     * The native renderer's holder of the failed picture. A chapter rebuilt makes new holders, which fetch
     * the picture on their own, so only a load into this one is Retry's. The page has no rebuild to tell
     * apart, and answers null.
     */
    private fun failedPictureHolder(): DrawableWrapper? {
        if (renderer == Renderer.WEB) return null
        var holder: DrawableWrapper? = null
        instrumentation.runOnMainSync { holder = imageSpans().first().drawable as DrawableWrapper }
        return holder
    }

    private fun loadedInPlace(holder: DrawableWrapper?): Boolean {
        if (holder == null) return firstImageWidth() > 0f
        var loaded = false
        instrumentation.runOnMainSync {
            loaded = holder.innerDrawable.let { it != null && it !is ImageFailureDrawable && it !is ColorDrawable }
        }
        return loaded
    }

    /** Where a tap reaches the failed picture's Retry, on screen. */
    private fun retryCentre(): PointF = when (renderer) {
        Renderer.NATIVE -> {
            lateinit var centre: PointF
            instrumentation.runOnMainSync {
                val chunk = textViews().first { chunk ->
                    (chunk.text as? Spanned)?.getSpans(0, chunk.text.length, ImageSpan::class.java)?.isNotEmpty() ==
                        true
                }
                val text = chunk.text as Spanned
                val start = text.getSpanStart(text.getSpans(0, text.length, ImageSpan::class.java).first())
                val line = chunk.layout.getLineForOffset(start)
                val at = IntArray(2).also(chunk::getLocationOnScreen)
                centre = PointF(
                    at[0] + chunk.totalPaddingLeft + chunk.layout.getPrimaryHorizontal(start) +
                        chunk.layout.getLineWidth(line) / 2,
                    at[1] + chunk.totalPaddingTop +
                        (chunk.layout.getLineTop(line) + chunk.layout.getLineBottom(line)) / 2f,
                )
            }
            centre
        }
        Renderer.WEB -> {
            val at = viewLocation()
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val rect = JSONArray(
                eval(
                    "(function () { var b = document.querySelector('.rk-image-failure .rk-failure-retry')" +
                        ".getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom]; })()",
                ),
            )
            PointF(
                at[0] + (rect.getDouble(0) + rect.getDouble(2)).toFloat() / 2 * density,
                at[1] + (rect.getDouble(1) + rect.getDouble(3)).toFloat() / 2 * density,
            )
        }
    }

    /** The first picture's drawn width in screen pixels, once it has arrived, else 0. */
    private fun firstImageWidth(): Float = when (renderer) {
        Renderer.NATIVE -> {
            var width = 0f
            instrumentation.runOnMainSync {
                val picture = imageSpans().firstOrNull()?.drawable as? DrawableWrapper
                if (picture != null && picture.innerDrawable !is ColorDrawable) width = picture.bounds.width().toFloat()
            }
            width
        }
        Renderer.WEB -> eval(
            "(function () { var m = document.images[0]; return m && m.complete && m.naturalWidth > 0 ?" +
                " m.getBoundingClientRect().width * devicePixelRatio : 0; })()",
        ).toFloat()
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

    /** Bounded, since the web renderer answers only once the page does, and a page that never does would hang. */
    private fun paragraphs(chapterId: Long): List<String>? = runBlocking(Dispatchers.Main) {
        withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_S)) { viewport.readAloud.paragraphs(chapterId) }
    }

    private fun firstVisibleParagraph(): ReadAloudPosition? = runBlocking(Dispatchers.Main) {
        withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_S)) { viewport.readAloud.firstVisibleParagraph() }
    }

    private fun highlight(position: ReadAloudPosition?, range: IntRange? = null) {
        instrumentation.runOnMainSync { viewport.readAloud.highlight(position, range) }
    }

    private fun obscure(top: Int, bottom: Int) {
        instrumentation.runOnMainSync { viewport.setObscured(top, bottom) }
        settle()
    }

    /** [value] in the renderer's own pixels as the device pixels the host measures the chrome in. */
    private fun devicePx(value: Float): Int = when (renderer) {
        Renderer.NATIVE -> value.roundToInt()
        Renderer.WEB -> (value * instrumentation.targetContext.resources.displayMetrics.density).roundToInt()
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

    /** Until the mark is drawn and the follow that may bring it on screen has stopped. */
    private fun awaitDrawnMark() {
        checkNotNull(awaitMark())
        awaitScrollStill()
        settle()
    }

    /** Until the mark is on screen and the follow that may bring it there has stopped. Read off the screen,
     *  since an outline puts nothing into native's text for [awaitMark] to find. */
    private fun awaitPainted() {
        awaitWhile { paintedOrNull() == null }
        awaitScrollStill()
        settle()
    }

    /** How far the painted outline reaches past the paragraph reading [text]: left, top, right, bottom. */
    private fun outlineOutward(text: String): List<Float> {
        val lines = paragraphLines(text)
        val box = RectF(lines.first()).apply { lines.forEach(::union) }
        val painted = painted()
        Log.i(TAG, "$renderer outline: text $box, painted $painted, pad ${dp(OUTLINE_PAD_DP)}")
        return listOf(
            box.left - painted.left,
            box.top - painted.top,
            painted.right - box.right,
            painted.bottom - box.bottom,
        )
    }

    /** That the paragraph a case measures sits where it names in native's chunks. The page has none. */
    private fun assertAtChunkEdge(check: (List<TextView>) -> Boolean) {
        if (renderer != Renderer.NATIVE) return
        var holds = false
        instrumentation.runOnMainSync { holds = check(descendants(view).filterIsInstance<TextView>()) }
        assertTrue("the paragraph is not at the chunk edge the case measures", holds)
    }

    /** Brings the seam below the open chapter on screen: a native seam is laid out only near the screen. */
    private fun bringSeamOnScreen() {
        instrumentation.runOnMainSync { (viewport as ReaderViewport).seekTo(ChapterProgress.Percent(10_000)) }
        awaitScrollStill()
        if (renderer == Renderer.NATIVE) {
            instrumentation.runOnMainSync { (view as RecyclerView).scrollBy(0, view.height / 2) }
        }
        settle()
    }

    private fun scrollToSeamsMiddle() {
        when (renderer) {
            Renderer.NATIVE -> instrumentation.runOnMainSync {
                val seam = descendants(view).first { it is NovelChapterSeamView && it.isVisible }
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val at = IntArray(2).also(seam::getLocationOnScreen)
                (view as RecyclerView).scrollBy(0, at[1] - origin[1] + seam.height / 2)
            }
            Renderer.WEB -> eval(
                "var s = document.querySelector('#rk-chapters .rk-seam');" +
                    "window.scrollTo({ top: s.getBoundingClientRect().top + window.scrollY +" +
                    " s.getBoundingClientRect().height / 2, behavior: 'instant' })",
            )
        }
        settle()
    }

    /** Hands the viewport its window insets again, the way each renderer learns of a new cutout. */
    private fun deliverInsets() {
        instrumentation.runOnMainSync {
            when (renderer) {
                Renderer.NATIVE -> ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder().build())
                Renderer.WEB -> view.requestLayout()
            }
        }
    }

    /** The cutout inset above the text, in the renderer's pixels. */
    private fun topInset(): Float = when (renderer) {
        Renderer.NATIVE -> {
            var padding = 0
            instrumentation.runOnMainSync { padding = (paragraphViews().first().parent as View).paddingTop }
            padding - dp(readerTestSettings.margins.top)
        }
        Renderer.WEB -> eval(
            "parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--rk-inset-top'))",
        ).toFloat()
    }

    /** [units] dp in the renderer's pixels: device pixels natively, CSS pixels on the page. */
    private fun inPagePixels(units: Int): Float = when (renderer) {
        Renderer.NATIVE -> dp(units)
        Renderer.WEB -> units.toFloat()
    }

    /** Scrolls the page down by [units] dp at once, a CSS pixel on the page. */
    private fun scrollBy(units: Int) {
        when (renderer) {
            Renderer.NATIVE -> instrumentation.runOnMainSync {
                (view as RecyclerView).scrollBy(0, dp(units).roundToInt())
            }
            Renderer.WEB -> eval("window.scrollBy({ top: $units, behavior: 'instant' })")
        }
        settle()
    }

    /** The screen rows painted in the test's highlight colour, and the columns they reach. */
    private data class Painted(val rows: List<Int>, val left: Int, val right: Int) {
        val top get() = rows.first()
        val bottom get() = rows.last() + 1
        val height get() = bottom - top

        override fun toString() = "Painted($left, $top, $right, $bottom over ${rows.size} rows)"
    }

    /**
     * Read off a screenshot rather than from either renderer's own geometry, so it is what the reader
     * sees. Only pixels exactly the colour count, which leaves out glyphs and antialiased edges.
     */
    private fun painted(): Painted = checkNotNull(paintedOrNull()) {
        "nothing on screen is painted in the mark's colour"
    }

    private fun paintedOrNull(): Painted? {
        val color = spacedSettings.ttsHighlightColor
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val at = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(at) }
        val bottom = minOf(shot.height, at[1] + view.height)
        val right = minOf(shot.width, at[0] + view.width)
        val row = IntArray(shot.width)
        val rows = mutableListOf<Int>()
        var left = Int.MAX_VALUE
        var furthest = Int.MIN_VALUE
        for (y in maxOf(0, at[1]) until bottom) {
            shot.getPixels(row, 0, shot.width, 0, y, shot.width, 1)
            val hits = (maxOf(0, at[0]) until right).filter { row[it] == color }
            if (hits.size < PAINTED_ROW_MIN_PIXELS) continue
            rows += y
            left = minOf(left, hits.first())
            furthest = maxOf(furthest, hits.last() + 1)
        }
        shot.recycle()
        return if (rows.isEmpty()) null else Painted(rows, left, furthest)
    }

    /** Each line of the paragraph reading [text], as the box its glyphs fill, in screen pixels. */
    private fun paragraphLines(text: String): List<RectF> = when (renderer) {
        Renderer.NATIVE -> {
            val lines = mutableListOf<RectF>()
            instrumentation.runOnMainSync {
                val chunk = descendants(view).filterIsInstance<TextView>().first { it.text.contains(text) }
                val layout = chunk.layout
                val start = chunk.text.indexOf(text)
                val metrics = chunk.paint.fontMetricsInt
                val at = IntArray(2).also(chunk::getLocationOnScreen)
                val x = (at[0] + chunk.totalPaddingLeft).toFloat()
                val y = (at[1] + chunk.totalPaddingTop).toFloat()
                (layout.getLineForOffset(start)..layout.getLineForOffset(start + text.length - 1)).forEach { line ->
                    val baseline = y + layout.getLineBaseline(line)
                    lines += RectF(
                        // From the first character rather than the line's left, which leaves out an indent.
                        x + layout.getPrimaryHorizontal(maxOf(start, layout.getLineStart(line))),
                        baseline + metrics.ascent,
                        x + layout.getLineRight(line),
                        baseline + metrics.descent,
                    )
                }
            }
            lines
        }
        Renderer.WEB -> {
            val at = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(at) }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val boxes = JSONArray(
                eval(
                    "(function () { var text = ${JSONObject.quote(text)};" +
                        " var p = Array.from(document.querySelectorAll('#rk-chapters .rk-chapter p'))" +
                        ".find(function (e) { return e.textContent.trim() === text; });" +
                        " var range = document.createRange(); range.selectNodeContents(p); var lines = {};" +
                        " Array.from(range.getClientRects()).forEach(function (r) { if (r.width <= 0) return;" +
                        " var key = Math.round(r.top); var l = lines[key];" +
                        " lines[key] = l ? [Math.min(l[0], r.left), Math.min(l[1], r.top), Math.max(l[2], r.right)," +
                        " Math.max(l[3], r.bottom)] : [r.left, r.top, r.right, r.bottom]; });" +
                        " return Object.keys(lines).map(function (k) { return lines[k]; }); })()",
                ),
            )
            (0 until boxes.length()).map { i ->
                val box = boxes.getJSONArray(i)
                RectF(
                    at[0] + box.getDouble(0).toFloat() * density,
                    at[1] + box.getDouble(1).toFloat() * density,
                    at[0] + box.getDouble(2).toFloat() * density,
                    at[1] + box.getDouble(3).toFloat() * density,
                )
            }.sortedBy { it.top }
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
        baseUrl: String? = null,
    ) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = "Chapter $number",
        url = "/chapter/$id",
        html = html,
        baseUrl = baseUrl,
        progressPercent = progressPercent,
        chapterNumber = number,
        novelId = 1L,
        sourceId = null,
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
        runBlocking(Dispatchers.Main) { viewport.window.prepend(chapter) }
        awaitChapters(before + 1)
    }

    private fun append(chapter: NovelReaderViewModel.LoadedChapter) {
        val before = chapterCount()
        runBlocking(Dispatchers.Main) { viewport.window.append(chapter) }
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
        const val THIRD = 3L

        /** A cutout inset no margin in the test settings is, so the column's top padding shows it. */
        const val CUTOUT_DP = 24

        /** An inset several lines of the test text tall, so a line measured below it is a different line. */
        const val TALL_CUTOUT_DP = 120
        const val TIMEOUT_S = 10L
        const val SETTLE_MS = 500L

        /** Long enough for a report the WebView makes off the main thread to arrive, had it been sent. */
        const val QUIET_MS = 1_000L

        /** A seek's rounding, in either renderer's pixels. */
        const val EDGE_SLACK_PX = 2f

        /** Whole-pixel line placement in native and CSS-pixel rounding in the page. */
        const val TYPE_SLACK_PX = 2f
        const val SHORT_PARAGRAPH = "A short paragraph."
        val WRAPPING_PARAGRAPH = "A paragraph long enough to wrap across several lines of the screen. ".repeat(6).trim()

        /** Under the 0.7 both renderers set a script at, over a glyph's rounding. */
        const val SCRIPT_MAX_RATIO = 0.85f
        const val ABOVE_RULE = "The paragraph above the rule."
        const val BELOW_RULE = "The paragraph below the rule."
        const val RUBY_REFERENCE = "WWWW"
        const val HEADING_RUN = "MMMMMMMM"
        const val SMALL_IMAGE_PX = 40

        /** A glyph's width rounds per size, which at a heading's size is a few percent of a run. */
        const val SIZE_SLACK = 0.03f
        const val JUMP_LINK = "Jump to the note"
        const val BASE_URL = "https://novel.test/chapter/1"
        const val NOTE = "The note the link names."
        const val OTHER_NOTE = "The note the chapter above names the same way."
        const val LONG_READING = "annotation"

        val transitionsOff = readerTestSettings.copy(alwaysShowChapterTransition = false)

        /** Tighter than any font's own height, with no paragraph spacing to hide the picture's margin in. */
        val tightLines = readerTestSettings.copy(lineHeight = 0.8f, paragraphSpacing = 0f)

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

        /** How far a case's chrome covers, in the renderer's own pixels, a whole number in either. */
        const val COVERED_UNITS = 120f
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

        /** How far down a chapter the rebuilt-line case reads from, in dp: well past its first screen. */
        const val REBUILT_SCROLL_DP = 2_000

        /** A picture tall enough to move the text below it, served slower than the page waits for pictures. */
        const val SLOW_PICTURE_PX = 400
        const val SLOW_PICTURE_MS = 6_000L

        /** Long enough for a chapter to render and land, well short of the slow picture. */
        const val BEFORE_PICTURE_MS = 2_500L

        /** More characters than one line of the test text holds. */
        const val LINE_CHARS = 120

        /** A top line further in than any test chapter's text reaches. */
        const val PAST_THE_END = 1_000_000

        /** How far past a lone chapter's end its last-screen line is read from: many lines, under a screen. */
        const val LAST_SCREEN_SCROLL_DP = 300

        /** What a landing may take past the image wait: a frame, a layout and the report after them. */
        const val IMAGE_WAIT_GRACE_MS = 1_500L
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

        /** Screens dragged through to pass the first picture's placeholder before any picture lands. */
        const val PICTURE_PASS_DRAGS = 4

        /** A paragraph's Range under either way the page can draw a mark: a highlight, or its boxes. */
        const val MARKED_BOX_JS =
            "(window.CSS && CSS.highlights && ['rk-tts-background', 'rk-tts-underline']" +
                ".map(function (n) { var h = CSS.highlights.get(n); return h && Array.from(h)[0]; })" +
                ".filter(Boolean)[0]) || document.querySelector('#rk-tts-overlay .rk-tts-box')"

        /** Spacing a mark over the whole line box would take in, and the chapter it is measured on. */
        val spacedSettings = readerTestSettings.copy(paragraphSpacing = 1.5f, lineHeight = 1.8f)
        val SPACED_PARAGRAPHS = listOf(
            "Opening line.",
            "lorem ipsum dolor sit amet ".repeat(8).trim(),
            "One short line.",
            "Closing line.",
        )
        val SPACED = SPACED_PARAGRAPHS.joinToString("") { "<p>$it</p>" }
        const val SPACED_LONG = 1
        const val SPACED_ONE_LINE = 2

        /** A font's ascent to descent against the mark over it: a line box with this spacing is far past it. */
        const val GLYPH_SLACK = 1.3f

        /** The page's `OUTLINE_PAD_PX`, one CSS pixel being one dp. */
        const val OUTLINE_PAD_DP = 4

        /** No line or paragraph spacing, so a native chunk's edge is its first or last line's glyphs. */
        val tightOutline = readerTestSettings.copy(
            lineHeight = 1f,
            paragraphSpacing = 0f,
            ttsHighlightStyle = TtsHighlightStyle.OUTLINE,
        )

        /** The first paragraph of [long]'s second chunk, some 6000 characters in; the case checks it on native. */
        const val CHUNK_START_PARAGRAPH = 27

        /** A paragraph of [long] on screen near the top, with room below it to scroll it up by [SCROLL_DP]. */
        const val SCROLLED_PARAGRAPH = 3
        const val SCROLL_DP = 150

        /** A glyph's gaps leave at least this many pixels of the colour in any row the mark covers. */
        const val PAINTED_ROW_MIN_PIXELS = 3

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
