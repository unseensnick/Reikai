package reikai.presentation.reader

import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import mihon.app.di.appGraph
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelTapLayout
import reikai.domain.reader.ChapterProgress
import reikai.presentation.reader.text.pngOf
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Paged reading in both renderers, driven the way a reader drives it: taps, keys, a drag, the rail, a font
 * change, a seam and a restored place. The page geometry is read off each renderer's own mask, so a line cut
 * by a page edge fails here whichever side measured it wrong.
 */
@RunWith(Parameterized::class)
class PagedReadingTest(private val renderer: Renderer) {

    enum class Renderer { NATIVE, WEB }

    @get:Rule
    val animationsOff = AnimationsOffRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var viewport: TextViewport
    private val view: View get() = viewport.view

    private val fits = Collections.synchronizedMap(mutableMapOf<Long, Boolean>())
    private val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
    private val settled = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
    private val topLines = Collections.synchronizedList(mutableListOf<Pair<Long, Int?>>())
    private val visible = Collections.synchronizedList(mutableListOf<Long>())
    private val steps = Collections.synchronizedList(mutableListOf<Boolean>())

    /** The reader's turns and the progress reports, in the order the viewport sent them. */
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val turned = AtomicInteger()
    private val shownPage = AtomicReference<Pair<Int, Int>?>(null)
    private val lastShare = AtomicReference<ChapterProgress.Percent?>(null)
    private var volumeKeys = false
    private var cutoutDp = 0
    private var openedAt = 0L

    /** The settings the last chapter opened with, whose top margin is a page's. */
    private var openedWith = readerTestSettings

    private val callbacks = NovelViewportCallbacks(
        volumeKeysActive = { volumeKeys },
        onProgressChanged = { id, percent ->
            events += "progress $id"
            progress += id to percent.wholePercent.toInt()
            lastShare.set(percent)
        },
        onProgressSettled = { id, percent -> settled += id to percent.wholePercent.toInt() },
        onTopLine = { id, line -> topLines += id to line },
        onToggleMenu = {},
        onStepChapter = { steps += it },
        onVisibleChapter = { visible += it },
        onRetryBoundary = {},
        cutoutTopDp = { cutoutDp },
        onChapterFits = { id, fit -> fits[id] = fit },
        onChapterEndSeen = {},
        onReaderScrolled = {},
        onReaderTurned = { events += "turn" },
        onPageTurned = { turned.incrementAndGet() },
        onPageShown = { page, count -> shownPage.set(page to count) },
    )

    private fun build(layout: NovelPageLayout) {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            viewport = when (renderer) {
                Renderer.NATIVE -> NovelTextViewport(
                    context = activity,
                    fontManager = activity.appGraph.novelFontManager,
                    textSelectable = false,
                    pageLayout = layout,
                    callbacks = callbacks,
                    autoScrollSpeed = InMemoryPreferenceStore().getFloat("speed", 1f),
                    autoScrollInterval = InMemoryPreferenceStore().getInt("interval", 5),
                )
                Renderer.WEB -> NovelWebViewport(
                    context = activity,
                    fontManager = activity.appGraph.novelFontManager,
                    imageRequests = activity.appGraph.novelImageRequests,
                    textSelectable = false,
                    pageLayout = layout,
                    callbacks = callbacks,
                    useOriginalFonts = false,
                    sourceCssPriority = false,
                    autoScrollSpeed = InMemoryPreferenceStore().getFloat("speed", 1f),
                    autoScrollInterval = InMemoryPreferenceStore().getInt("interval", 5),
                )
            }
            activity.setContentView(view)
        }
    }

    @After
    fun tearDown() {
        if (::viewport.isInitialized) instrumentation.runOnMainSync { (viewport as ReaderViewport).destroy() }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun everyPageShowsWholeLinesAndTheLastReadsTheWholeChapter() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        val percents = readToTheEnd(1L)
        assertEquals("[$renderer] the first page is the chapter's start", 0, percents.first())
        assertEquals("[$renderer] the last page is all of it", 100, percents.last())
        assertTrue("[$renderer] every turn moves on: $percents", percents.zipWithNext().all { (a, b) -> b > a })
    }

    /** A report of the column's start before the saved page lands reads to the model as the reader moving. */
    @Test
    fun aSavedShareReportsNoPlaceBeforeItsPage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"), percent = 50)
        val first = synchronized(progress) { progress.first { it.first == 1L }.second }
        assertTrue("[$renderer] the first report is the saved page's, not $first", first in 40..60)
    }

    @Test
    fun turningBackRetracesEveryPage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        val forward = mutableListOf(lastTopLine())
        repeat(PAGES_WALKED) {
            forwardTap()
            forward += lastTopLine()
        }
        val back = mutableListOf(lastTopLine())
        repeat(PAGES_WALKED) {
            backTap()
            back += lastTopLine()
        }
        assertEquals("[$renderer] back retraces forward", forward, back.reversed())
    }

    @Test
    fun aFontChangeKeepsTheCharacterOnScreen() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(5) { forwardTap() }
        val character = checkNotNull(lastTopLine())
        onMain {
            viewport.applySettings(readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 6))
        }
        settle()
        settle()
        assertWholeLines()
        val pageStart = checkNotNull(lastTopLine())
        assertTrue("[$renderer] the page now starts at or before $character, at $pageStart", pageStart <= character)
        forwardTap()
        val nextStart = checkNotNull(lastTopLine())
        assertTrue("[$renderer] the next page starts after $character, at $nextStart", nextStart > character)
    }

    @Test
    fun aCutoutComingAndGoingKeepsThePageAcrossFlips() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(8) { forwardTap() }
        val start = checkNotNull(lastTopLine())
        repeat(3) {
            flipCutout(53)
            assertTrue(
                "[$renderer] line $start left the page, at ${lastTopLine()}",
                checkNotNull(lastTopLine()) <= start,
            )
            flipCutout(0)
            assertEquals("[$renderer] the page moved over a flip", start, lastTopLine())
        }
    }

    @Test
    fun aTopMarginChangeThereAndBackKeepsThePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(8) { forwardTap() }
        val start = checkNotNull(lastTopLine())
        val margins = readerTestSettings.margins
        repeat(3) {
            applyAndSettle(readerTestSettings.copy(margins = margins.copy(top = margins.top + 53)))
            applyAndSettle(readerTestSettings)
            assertEquals("[$renderer] the page moved", start, lastTopLine())
        }
    }

    @Test
    fun aFontChangeThereAndBackKeepsThePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(8) { forwardTap() }
        val start = checkNotNull(lastTopLine())
        repeat(3) {
            applyAndSettle(readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 6))
            applyAndSettle(readerTestSettings)
            assertEquals("[$renderer] the page moved", start, lastTopLine())
        }
    }

    @Test
    fun aOneStepFontChangeThereAndBackKeepsEveryPage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        append(2L, long("second"))
        repeat(5) {
            forwardTap()
            val start = checkNotNull(lastTopLine())
            val page = shownPage.get()
            applyAndSettle(readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 1), settles = 8)
            applyAndSettle(readerTestSettings, settles = 8)
            assertEquals("[$renderer] page $page moved to ${shownPage.get()}", start, lastTopLine())
        }
    }

    @Test
    fun aTurnAfterAReflowStartsFromTheNewPage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(8) { forwardTap() }
        val start = checkNotNull(lastTopLine())
        flipCutout(53)
        forwardTap()
        val next = checkNotNull(lastTopLine())
        assertTrue("[$renderer] the turn went on from $start, to $next", next > start)
        flipCutout(0)
        val held = checkNotNull(lastTopLine())
        assertTrue("[$renderer] the turned-to line $next left the page, at $held", held <= next)
        forwardTap()
        assertTrue("[$renderer] the page after it starts past $next", checkNotNull(lastTopLine()) > next)
    }

    @Test
    fun aReadAloudFollowIsWhereAReflowHolds() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(2) { forwardTap() }
        val turned = checkNotNull(lastTopLine())
        onMain { viewport.readAloud.highlight(ReadAloudPosition(1L, 60)) }
        repeat(3) { settle() }
        val followed = checkNotNull(lastTopLine())
        assertTrue("[$renderer] the follow moved on from $turned, to $followed", followed > turned)
        flipCutout(53)
        assertTrue(
            "[$renderer] the reflow went back to $turned, at ${lastTopLine()}",
            checkNotNull(lastTopLine()) > turned,
        )
    }

    /** A rotation rebuilds the renderer at a new width, carrying only the reported line across. */
    @Test
    fun aRebuildAtAnotherSizeAndBackKeepsThePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(8) { forwardTap() }
        val start = checkNotNull(lastTopLine())
        val page = shownPage.get()
        val wider = readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 6)
        repeat(3) {
            open(1L, long("first"), topLine = lastTopLine(), settings = wider)
            open(1L, long("first"), topLine = lastTopLine())
            assertEquals("[$renderer] the page moved", page, shownPage.get())
            assertEquals("[$renderer] the line moved", start, lastTopLine())
        }
    }

    /** As a rotation does: a new renderer, which holds no line of its own until the landing gives it one. */
    @Test
    fun aNewRendererAtAnotherSizeAndBackKeepsThePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(8) { forwardTap() }
        val start = checkNotNull(lastTopLine())
        val wider = readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 6)
        repeat(3) {
            val line = lastTopLine()
            rebuild(NovelPageLayout.PAGED_LTR)
            open(1L, long("first"), topLine = line, settings = wider)
            val back = lastTopLine()
            rebuild(NovelPageLayout.PAGED_LTR)
            open(1L, long("first"), topLine = back)
        }
        assertEquals("[$renderer] the line moved", start, lastTopLine())
    }

    private fun applyAndSettle(settings: NovelReaderSettings, settles: Int = 3) {
        openedWith = settings
        onMain { viewport.applySettings(settings) }
        repeat(settles) { settle() }
        assertWholeLines()
    }

    /** A 180 degree flip: the inset moves with no layout, and the insets dispatch is what says so. */
    private fun flipCutout(dp: Int) {
        cutoutDp = dp
        onMain { ViewCompat.requestApplyInsets(view) }
        repeat(3) { settle() }
    }

    @Test
    fun theSeamBetweenTwoChaptersIsAPageOfItsOwn() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        val lastLine = topLines.last()
        forwardTap()
        assertEquals("[$renderer] the seam keeps the chapter just read", 1L, visible.lastOrNull() ?: 1L)
        assertEquals("[$renderer] at its end", 1L to 100, progress.last())
        assertEquals("[$renderer] on its last page's line", lastLine, topLines.last())
        forwardTap()
        assertEquals("[$renderer] the page after the seam is the chapter's first", 2L to 0, topLines.last())
        assertEquals("[$renderer] and the chapter it opens is read", 2L, visible.last())
    }

    /** As Mihon's transition page: the seam turns like a page, but the slider counts the text's pages alone. */
    @Test
    fun theSeamIsNoPageOfTheCount() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        val lastOfFirst = checkNotNull(shownPage.get())
        forwardTap()
        assertEquals("[$renderer] the seam shows the closed chapter's last page", lastOfFirst, shownPage.get())
        forwardTap()
        val firstOfSecond = checkNotNull(shownPage.get())
        open(2L, long("second"))
        val alone = checkNotNull(shownPage.get()).second
        assertEquals("[$renderer] the text's first page is the first of its own count", 0 to alone, firstOfSecond)
    }

    @Test
    fun theSliderPastTheSeamLandsOnTheTextPageItNames() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        repeat(2) { forwardTap() }
        val count = checkNotNull(shownPage.get()).second
        seekPages(2, count)
        assertEquals("[$renderer] the slider's page is on screen", 2 to count, shownPage.get())
        seekPages(0, count)
        assertEquals("[$renderer] its first page is the text's first", 2L to 0, topLines.last())
    }

    /** The slider on the seam is the chapter the reader is still in, which the counter shows. */
    @Test
    fun theSliderOnTheSeamSeeksTheChapterItCloses() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        forwardTap()
        val count = checkNotNull(shownPage.get()).second
        seekPages(0, count)
        assertEquals("[$renderer] the closed chapter's first page is on screen", 1L to 0, topLines.last())
    }

    /** Back onto the seam the reader is still on the chapter they came from, so the slider is that chapter's. */
    @Test
    fun theSliderOnTheSeamFromBelowSeeksTheChapterComeFrom() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        repeat(2) { forwardTap() }
        backTap()
        val count = checkNotNull(shownPage.get()).second
        seekPages(2, count)
        assertEquals("[$renderer] the slider's page of the chapter come from", 2L, topLines.last().first)
    }

    @Test
    fun turnsBackAndForthCrossTheSeam() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        val lastLineOfFirst = topLines.last()
        repeat(2) { forwardTap() }
        val firstOfSecond = shownPage.get()
        backTap()
        assertEquals("[$renderer] back onto the seam stays on the chapter come from", 2L to 0, progress.last())
        assertEquals("[$renderer] and its line", 2L to 0, topLines.last())
        assertEquals("[$renderer] and its number", firstOfSecond, shownPage.get())
        assertEquals("[$renderer] and names it", 2L, visible.last())
        backTap()
        assertEquals("[$renderer] then its last page", lastLineOfFirst, topLines.last())
        repeat(2) { forwardTap() }
        assertEquals("[$renderer] and forward again reaches the text", 2L to 0, topLines.last())
    }

    /** The opened chapter holds an earlier one's report until the reader moves, so a tap turn has to say so first. */
    @Test
    fun aTapTurnSaysTheReaderTurnedBeforeItReportsThePageItShows() {
        build(NovelPageLayout.PAGED_LTR)
        open(2L, long("second"))
        joinAbove()
        // Onto the seam, which reports nothing, then onto the chapter before's last page.
        backTap()
        events.clear()
        backTap()
        val turnAt = events.indexOf("turn")
        val reportAt = events.indexOf("progress 1")
        assertTrue("[$renderer] the turn was not said before the seam's report: $events", turnAt in 0..<reportAt)
    }

    @Test
    fun anAutoScrollTurnIsNotTheReaders() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        val stepped = (viewport as ReaderViewport).autoScroll as ViewportAutoScroll.Stepped
        events.clear()
        val turns = turned.get()
        instrumentation.runOnMainSync { stepped.advance() }
        awaitWhile { turned.get() == turns }
        settle()
        assertTrue("[$renderer] auto-scroll said the reader turned: $events", "turn" !in events)
    }

    /**
     * As Mihon's transition page fits its screen: a seam with long titles and a missing-chapters warning, on a
     * page shorter than its padding alone, is one page, which keeps the closed chapter, and the next turn is the
     * text's first page. Back across it is one page too.
     */
    @Test
    fun aSeamFitsOnePageHoweverShortThePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"), settings = shortPages(), title = LONG_TITLE)
        append(GAP_ID, short("second"), title = LONG_TITLE)
        readToTheEnd(1L)
        val lastLine = topLines.last()
        val lastOfFirst = shownPage.get()
        forwardTap()
        assertEquals("[$renderer] the seam keeps the last page's line", lastLine, topLines.last())
        assertEquals("[$renderer] and its number", lastOfFirst, shownPage.get())
        forwardTap()
        assertEquals("[$renderer] one page past the seam is the text's first", GAP_ID to 0, topLines.last())
        assertEquals("[$renderer] the first of its own count", 0, shownPage.get()?.first)
        backTap()
        assertEquals("[$renderer] back onto the seam stays where it came from", GAP_ID to 0, progress.last())
        backTap()
        assertEquals("[$renderer] one page back past the seam is the last page", lastLine, topLines.last())
    }

    /** Back from an opened chapter onto a seam on a short page, and a rebuild there. */
    @Test
    fun aTallSeamAboveAnOpenedChapterKeepsTheOpenedChapter() {
        build(NovelPageLayout.PAGED_LTR)
        open(2L, short("second"), settings = shortPages())
        joinAbove()
        val opened = shownPage.get()
        backTap()
        assertTrue("[$renderer] the seam reported the chapter before: $progress", progress.none { it.first == 1L })
        assertEquals("[$renderer] and kept the opened chapter's number", opened, shownPage.get())
        val (id, line) = topLines.last()
        rebuild(NovelPageLayout.PAGED_LTR)
        topLines.clear()
        open(id, short("second"), lastPercentOf(id), topLine = line, settings = shortPages())
        joinAbove()
        assertEquals(
            "[$renderer] a rebuild on the seam lands on the opened chapter's first page",
            2L to 0,
            topLines.last(),
        )
    }

    @Test
    fun theSliderOnATallSeamSeeksTheChapterItCloses() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"), settings = shortPages())
        append(2L, short("second"))
        readToTheEnd(1L)
        val lastLine = topLines.last()
        forwardTap()
        assertEquals("[$renderer] the seam keeps the closed chapter", lastLine, topLines.last())
        val count = checkNotNull(shownPage.get()).second
        seekPages(0, count)
        assertEquals("[$renderer] the closed chapter's first page is on screen", 1L to 0, topLines.last())
    }

    /** Settings whose margins leave a page shorter than the seam's own padding. */
    private fun shortPages(): NovelReaderSettings {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val margin = ((metrics.heightPixels / metrics.density - SHORT_PAGE_DP) / 2).toInt()
        return readerTestSettings.copy(margins = readerTestSettings.margins.copy(top = margin, bottom = margin))
    }

    /** Opening a chapter is for reading it, so the seam the chapter before brings when it joins stays above. */
    @Test
    fun aChapterOpenedAtItsStartShowsItsTextOnceTheOneBeforeJoins() {
        build(NovelPageLayout.PAGED_LTR)
        open(2L, long("second"))
        joinAbove()
        assertEquals("[$renderer] the open did not stay on the text", 2L to 0, topLines.last())
    }

    /** As a rotation does: the provider reopens the chapter the seam reported, at its share and with no line. */
    @Test
    fun aNewRendererOnTheSeamsPageLandsOnTheClosedChaptersLastPage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        val lastLine = topLines.last()
        val lastPage = shownPage.get()
        forwardTap()
        val (id, line) = topLines.last()
        rebuild(NovelPageLayout.PAGED_LTR)
        topLines.clear()
        open(id, short("first"), lastPercentOf(id), topLine = line)
        append(2L, long("second"))
        assertEquals("[$renderer] the rebuilt page is not the closed chapter's last", lastLine, topLines.last())
        assertEquals("[$renderer] nor its number", lastPage, shownPage.get())
    }

    /** As Mihon's pager: back onto the seam and rebuilt there, the reader is on the page they came from. */
    @Test
    fun aNewRendererOnTheSeamFromBelowLandsOnTheChapterComeFrom() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        repeat(2) { forwardTap() }
        val firstOfSecond = shownPage.get()
        backTap()
        val (id, line) = topLines.last()
        rebuild(NovelPageLayout.PAGED_LTR)
        topLines.clear()
        open(id, long("second"), lastPercentOf(id), topLine = line)
        joinAbove()
        assertEquals("[$renderer] the rebuilt page is not the first of the chapter come from", 2L to 0, topLines.last())
        assertEquals("[$renderer] nor its number", firstOfSecond, shownPage.get())
    }

    private fun joinAbove() {
        fits.remove(1L)
        onMain { viewport.window.prepend(chapter(1L, short("first"), 100, null)) }
        awaitWhile { !fits.containsKey(1L) }
        repeat(3) { settle() }
    }

    private fun seekPages(page: Int, count: Int) {
        instrumentation.runOnMainSync {
            (viewport as ReaderViewport).seekTo(ChapterProgress.Pages(lastPageRead = page, pageCount = count))
        }
        awaitWhile { shownPage.get()?.first != page }
        settle()
        assertWholeLines()
    }

    /** Opened, then back onto the seam: still the opened chapter, until the turn onto the chapter before. */
    @Test
    fun theSeamAboveAnOpenedChapterKeepsTheOpenedChapter() {
        build(NovelPageLayout.PAGED_LTR)
        open(2L, long("second"))
        joinAbove()
        val opened = shownPage.get()
        backTap()
        assertTrue("[$renderer] the seam named the chapter before: $visible", 1L !in visible)
        assertEquals("[$renderer] the seam kept the opened chapter's number", opened, shownPage.get())
        backTap()
        assertEquals("[$renderer] the chapter before's last page names it", 1L, visible.last())
        assertEquals("[$renderer] at its end", 1L to 100, progress.last())
    }

    @Test
    fun aDragNeverMovesThePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        forwardTap()
        val before = lastTopLine()
        drag(view.width / 2f, view.height * 0.8f, view.height * 0.2f)
        settle()
        assertEquals("[$renderer] a drag leaves the page", before, lastTopLine())
        assertWholeLines()
    }

    /** The failure above the first chapter starts its item at the very top, which the list cannot scroll above. */
    @Test
    fun aFailureAboveTheFirstChapterLandsBelowTheMargin() {
        build(NovelPageLayout.PAGED_LTR)
        // A top margin past the failure's own, so nothing but the room above the column keeps it clear.
        open(1L, long("first"), settings = readerTestSettings.copy(margins = readerTestSettings.margins.copy(top = 80)))
        instrumentation.runOnMainSync {
            viewport.window.setBoundaryFailures(NovelReaderViewModel.BoundaryFailure("offline", 0L, 0L), null)
        }
        settle()
        settle()
        val failureTop = checkNotNull(failureTopOnScreen()) { "[$renderer] no failure drawn" }
        assertEquals("[$renderer] the failure sits at the page's top", readingTop(), failureTop, SLACK)
        assertWholeLines()
    }

    /** A selection drag takes the gesture from the list, which then never sees the finger lift. */
    @Test
    fun aGestureTheTextTakesLeavesTheNextLandingWorking() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(4) { forwardTap() }
        val saved = checkNotNull(lastTopLine()) + SAVED_LINE_INTO_PAGE
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f, down, down)
            (view as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
        }
        topLines.clear()
        open(1L, long("first"), topLine = saved)
        val pageStart = checkNotNull(lastTopLine())
        assertTrue("[$renderer] the saved line's page did not open, at $pageStart", pageStart in 1..saved)
    }

    private fun failureTopOnScreen(): Float? = when (renderer) {
        Renderer.NATIVE -> {
            var top: Float? = null
            instrumentation.runOnMainSync {
                (view as RecyclerView).children.forEach { item ->
                    (item as ViewGroup).children
                        .firstOrNull { it is reikai.presentation.reader.text.NovelBoundaryFailureView && it.isShown }
                        ?.let { top = (item.top + it.top).toFloat() }
                }
            }
            top
        }
        Renderer.WEB -> evalString(
            "(function(){ var f = document.getElementById('rk-failure-start');" +
                " return f ? String(f.getBoundingClientRect().top) : ''; })()",
        ).toFloatOrNull()
    }

    /** A WebView draws its scrollbar itself, so the page's own stylesheet cannot hide it. */
    @Test
    fun noScrollbarShowsBesideAPage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        var shown = true
        instrumentation.runOnMainSync { shown = view.isVerticalScrollBarEnabled }
        assertTrue("[$renderer] a scrollbar shows beside the page", !shown)
    }

    /** A tap gives the chapter's view focus, and a list scrolls whatever takes focus into what it counts as visible. */
    @Test
    fun theTextTakingFocusLeavesThePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        forwardTap()
        val before = lastTopLine()
        instrumentation.runOnMainSync {
            val focusable = (view as? ViewGroup)?.getChildAt(0) ?: view
            focusable.isFocusableInTouchMode = true
            focusable.requestFocus()
        }
        settle()
        assertEquals("[$renderer] focus left the page", before, lastTopLine())
        assertWholeLines()
    }

    @Test
    fun rightToLeftPagesReadOnFromTheLeft() {
        build(NovelPageLayout.PAGED_RTL)
        val zones = NovelTapZones(NovelTapLayout.RIGHT_AND_LEFT, TappingInvertMode.NONE, 12)
        open(1L, long("first"), settings = readerTestSettings.copy(tapZones = zones))
        val before = lastPercent()
        tapAndAwaitTurn(view.width * 0.1f, view.height * 0.5f)
        assertTrue("[$renderer] the left edge read on", lastPercent() > before)
    }

    @Test
    fun aSwipeTurnsThePageTheWayThePagesLie() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        val before = lastPercent()
        val turns = turned.get()
        swipe(view.width * 0.9f, view.width * 0.1f)
        awaitWhile { turned.get() == turns }
        settle()
        assertTrue("[$renderer] a swipe to the left read on", lastPercent() > before)
        assertTrue("[$renderer] and stepped no chapter", steps.isEmpty())
    }

    /** A pager's swipe: with chapter swipes off, short, and starting on the half it moves towards. */
    @Test
    fun aShortSwipeTurnsThePageWithChapterSwipesOff() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"), settings = readerTestSettings.copy(swipeGestures = false))
        val before = lastPercent()
        val turns = turned.get()
        swipe(view.width * 0.4f, view.width * 0.2f)
        awaitWhile { turned.get() == turns }
        settle()
        assertTrue("[$renderer] a short swipe to the left read on", lastPercent() > before)
    }

    @Test
    fun aVolumeKeyTurnsAPage() {
        build(NovelPageLayout.PAGED_LTR)
        volumeKeys = true
        open(1L, long("first"))
        val before = lastPercent()
        val turns = turned.get()
        instrumentation.runOnMainSync {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
            (viewport as ReaderViewport).handleKeyEvent(down)
        }
        awaitWhile { turned.get() == turns }
        settle()
        assertTrue("[$renderer] volume down read on", lastPercent() > before)
        assertWholeLines()
    }

    @Test
    fun everyTurnAsksForOneFlash() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        val before = turned.get()
        repeat(3) { forwardTap() }
        backTap()
        assertEquals("[$renderer] one flash a turn", before + 4, turned.get())
    }

    @Test
    fun autoScrollTurnsAPageEachStep() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        val stepped = viewport.let { it as ReaderViewport }.autoScroll as ViewportAutoScroll.Stepped
        val before = lastPercent()
        val turns = turned.get()
        instrumentation.runOnMainSync { stepped.advance() }
        awaitWhile { turned.get() == turns }
        settle()
        assertTrue("[$renderer] a step read on", lastPercent() > before)
    }

    @Test
    fun aTurnPastTheLastPageStepsToTheNextChapter() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        readToTheEnd(1L)
        forwardTap(expectTurn = false)
        awaitWhile { steps.isEmpty() }
        assertEquals("[$renderer] the last page steps forward", listOf(true), steps.toList())
    }

    @Test
    fun theRailLandsOnAWholePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        val reports = settled.size
        instrumentation.runOnMainSync { (viewport as ReaderViewport).seekTo(ChapterProgress.Percent.ofWhole(50)) }
        awaitWhile { settled.size == reports }
        settle()
        val landed = settled.last().second
        assertTrue("[$renderer] half way lands near half way, at $landed", landed in 40..60)
        assertWholeLines()
    }

    /** What the page slider draws: the page on screen and the chapter's count. */
    @Test
    fun aTurnReportsTheNextPageOfTheCount() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        awaitWhile { shownPage.get() == null }
        forwardTap()
        awaitWhile { shownPage.get()?.first != 1 }
        val (page, count) = shownPage.get()!!
        assertEquals("[$renderer] the turn reported the second page", 1, page)
        assertTrue("[$renderer] of a chapter longer than two pages, $count", count > 2)
    }

    /**
     * The slide lays a copy of the old page beside the content and slides it off once the turned page is drawn.
     * A copy never drawn over is cleared by [PageSlide.GIVE_UP_MS] instead, so only a clear inside it passes.
     */
    @Test
    fun aSlidingTurnSlidesItsCopyOffAndLandsOnTheNextPage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"), settings = readerTestSettings.copy(pageTransitions = true))
        awaitWhile { shownPage.get() == null }
        val laidAt = AtomicLong(-1)
        val clearedAt = AtomicLong(-1)
        instrumentation.runOnMainSync {
            (view.parent as ViewGroup).setOnHierarchyChangeListener(
                object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View, child: View) {
                        if (child is ImageView) laidAt.set(SystemClock.uptimeMillis())
                    }

                    override fun onChildViewRemoved(parent: View, child: View) {
                        if (child is ImageView) clearedAt.set(SystemClock.uptimeMillis())
                    }
                },
            )
        }
        forwardTap()
        awaitWhile { shownPage.get()?.first != 1 }
        awaitWhile { clearedAt.get() < 0 }
        settle()
        assertEquals("[$renderer] the second page is on screen", 1, shownPage.get()?.first)
        assertTrue(
            "[$renderer] the copy was laid at ${laidAt.get()} and cleared at ${clearedAt.get()}",
            laidAt.get() >= 0 && clearedAt.get() - laidAt.get() < PageSlide.GIVE_UP_MS,
        )
    }

    @Test
    fun theSliderLandsOnThePageItNames() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        awaitWhile { shownPage.get() == null }
        val count = shownPage.get()!!.second
        instrumentation.runOnMainSync {
            (viewport as ReaderViewport).seekTo(ChapterProgress.Pages(lastPageRead = 2, pageCount = count))
        }
        awaitWhile { shownPage.get()?.first != 2 }
        settle()
        assertEquals("[$renderer] the slider's page is on screen", 2 to count, shownPage.get())
        assertWholeLines()
    }

    @Test
    fun aSavedShareOpensOnThePageItNames() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"), percent = 50)
        val landed = lastPercent()
        assertTrue("[$renderer] a saved half opens near half way, at $landed", landed in 40..60)
        assertWholeLines()
    }

    @Test
    fun aSavedLineOpensOnThePageHoldingIt() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"))
        repeat(4) { forwardTap() }
        val saved = checkNotNull(lastTopLine()) + SAVED_LINE_INTO_PAGE
        topLines.clear()
        open(1L, long("first"), topLine = saved)
        val pageStart = checkNotNull(lastTopLine())
        assertTrue("[$renderer] the page holding $saved starts at or before it, at $pageStart", pageStart <= saved)
        forwardTap()
        assertTrue("[$renderer] and the next starts after it", checkNotNull(lastTopLine()) > saved)
    }

    /** A reopen lands a stored share on its page by the scrolled column's measure, not by the page count. */
    @Test
    fun aShareSavedWhileScrollingOpensPagedOnThePageHoldingItsLine() {
        build(NovelPageLayout.SCROLL)
        openScrolled(1L, long("first"))
        scrollDown(SCROLLED_DP)
        val saved = checkNotNull(lastTopLine())
        val share = checkNotNull(lastShare.get())
        rebuild(NovelPageLayout.PAGED_LTR)
        topLines.clear()
        open(1L, long("first"), share = share)
        val pageStart = checkNotNull(lastTopLine())
        assertTrue(
            "[$renderer] the page holding $saved at $share starts at or before it, at $pageStart",
            pageStart <= saved,
        )
        forwardTap()
        assertTrue("[$renderer] and the next starts after it", checkNotNull(lastTopLine()) > saved)
    }

    /** The margin below a page hides the next page's first lines, links among them; a tap there turns the page. */
    @Test
    fun aTapOverAHiddenLinkBelowThePageTurnsIt() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, linked("first"))
        val margin = openedWith.margins.bottom * instrumentation.targetContext.resources.displayMetrics.density
        tapAndAwaitTurn(view.width * 0.1f, view.height - margin / 2)
    }

    /** A picture drawn as a background takes up the column with no text in it, so a break must not skip it. */
    @Test
    fun aTurnNeverSkipsAPictureDrawnAsABackground() {
        assumeTrue("only the page draws a background", renderer == Renderer.WEB)
        build(NovelPageLayout.PAGED_LTR)
        val art = "<div id=\"art\" style=\"height:95vh;background-image:linear-gradient(red, blue)\"></div>"
        open(1L, short("before") + art + short("after"))
        var topShown = artTopOnPage()
        var turns = 0
        while (!topShown && lastPercentOf(1L) < 100 && turns++ < MAX_PAGES) {
            forwardTap()
            topShown = artTopOnPage()
        }
        assertTrue("[$renderer] the picture's top never showed on a page", topShown)
    }

    /** A page that starts inside a picture has no line of its own; holding the one above it stalls the turns. */
    @Test
    fun turningForwardThroughATallPictureShowsEveryPageOnce() {
        build(NovelPageLayout.PAGED_LTR)
        openTallPicture()

        val pages = pagesTurning { forwardTap() }

        assertEquals("[$renderer] pages shown", (0 until shownPage.get()!!.second).toList(), pages)
    }

    @Test
    fun turningBackThroughATallPictureShowsEveryPageOnce() {
        build(NovelPageLayout.PAGED_LTR)
        openTallPicture()
        pagesTurning { forwardTap() }

        val pages = pagesTurning(stopAt = 0) { backTap() }

        assertEquals("[$renderer] pages shown", (shownPage.get()!!.second - 1 downTo 0).toList(), pages)
    }

    /** A page inside a picture saves no line of its own, so a new renderer lands by the share, on that page. */
    @Test
    fun aNewRendererOnAPageInsideATallPictureLandsOnThatPage() {
        build(NovelPageLayout.PAGED_LTR)
        openTallPicture()
        repeat(6) { forwardTap() }
        val page = shownPage.get()!!.first
        val line = lastTopLine()
        val share = checkNotNull(lastShare.get())

        rebuild(NovelPageLayout.PAGED_LTR)
        openTallPicture(topLine = line, share = share)

        assertEquals("[$renderer] page after the rebuild", page, shownPage.get()!!.first)
    }

    private fun openTallPicture(
        topLine: Int? = null,
        share: ChapterProgress.Percent = ChapterProgress.Percent.ofWhole(0),
    ) {
        val picture = "data:image/png;base64," + Base64.encodeToString(pngOf(300, 9_000), Base64.NO_WRAP)
        open(1L, short("before") + "<p><img src=\"$picture\"></p>" + short("after"), topLine = topLine, share = share)
    }

    /** The shown page indexes, turning with [turn] until index [stopAt] or the chapter's last page. */
    private fun pagesTurning(stopAt: Int? = null, turn: () -> Unit): List<Int> {
        val pages = mutableListOf(shownPage.get()!!.first)
        fun done() = pages.last() == (stopAt ?: (shownPage.get()!!.second - 1))
        while (!done() && pages.size < PICTURE_TURNS) {
            turn()
            pages += shownPage.get()!!.first
        }
        return pages
    }

    /** The chapter before joins after the first page shows and moves it, which the reader must not watch. */
    @Test
    fun theOpeningVeilStaysUpWhileTheChapterBeforeIsJoining() {
        assumeTrue("only the page veils its opening", renderer == Renderer.WEB)
        build(NovelPageLayout.PAGED_LTR)
        openJoining(2L, percent = 50)

        // Past the cap the veil drops by itself, so a slow open fails naming the cap rather than the join.
        val up = veiled()
        val after = SystemClock.uptimeMillis() - openedAt
        assertTrue("[$renderer] veil up $up at ${after}ms, cap $VEIL_CAP_MS", up && after < VEIL_CAP_MS)
    }

    @Test
    fun theOpeningVeilDropsOnceTheChapterBeforeHasJoinedAndThePageSettled() {
        assumeTrue("only the page veils its opening", renderer == Renderer.WEB)
        build(NovelPageLayout.PAGED_LTR)
        openJoining(2L, percent = 50)

        onMain {
            viewport.window.prepend(chapter(1L, long("first"), 0, null))
            viewport.window.setPreviousJoining(false)
        }
        // The cap drops the veil on its own, so only a drop before it proves the join lifted it.
        var droppedAfter = Long.MAX_VALUE
        awaitWhile { veiled().also { up -> if (!up) droppedAfter = SystemClock.uptimeMillis() - openedAt } }

        assertTrue(
            "[$renderer] the veil came down only by its cap, ${droppedAfter}ms after the open",
            droppedAfter < VEIL_CAP_MS,
        )
    }

    /** Opens [id] as the provider does while the chapter before is still loading, and waits for its first page. */
    private fun openJoining(id: Long, percent: Int) {
        fits.remove(id)
        // Before load, which starts the veil's cap, so the cap cannot fire before openedAt + VEIL_CAP_MS.
        openedAt = SystemClock.uptimeMillis()
        onMain {
            viewport.load(chapter(id, long("second"), percent, null), readerTestSettings)
            viewport.window.setPreviousJoining(true)
        }
        awaitWhile { !fits.containsKey(id) || !pageShown() }
        settle()
    }

    private fun veiled(): Boolean {
        var up = false
        instrumentation.runOnMainSync { up = (viewport as NovelWebViewport).isVeiled }
        return up
    }

    private fun artTopOnPage(): Boolean = evalString(
        "(function(){ var r = document.getElementById('art').getBoundingClientRect();" +
            " var top = parseFloat(getComputedStyle(document.querySelector('.rk-chapter')).paddingTop);" +
            " var bottom = parseFloat(document.getElementById('rk-page-mask-bottom').style.top);" +
            " return String(r.top >= top - 1 && r.top < bottom); })()",
    ) == "true"

    // region driving

    private fun open(
        id: Long,
        html: String,
        percent: Int = 0,
        topLine: Int? = null,
        settings: NovelReaderSettings = readerTestSettings,
        share: ChapterProgress.Percent = ChapterProgress.Percent.ofWhole(percent),
        title: String = "Chapter $id",
    ) {
        fits.remove(id)
        openedWith = settings
        onMain { viewport.load(chapter(id, html, percent, topLine, share, title), settings) }
        awaitWhile { !fits.containsKey(id) }
        assertTrue("[$renderer] chapter $id never rendered", fits.containsKey(id))
        awaitWhile { !pageShown() }
        settle()
        assertTrue("[$renderer] no page shown", pageShown())
        assertWholeLines()
    }

    /** Opens [id] in a scrolling column, which draws no page and so has no mask to wait on. */
    private fun openScrolled(id: Long, html: String) {
        fits.remove(id)
        onMain { viewport.load(chapter(id, html, 0, null), readerTestSettings) }
        awaitWhile { !fits.containsKey(id) }
        settle()
        assertTrue("[$renderer] chapter $id never rendered", fits.containsKey(id))
    }

    private fun scrollDown(dp: Int) {
        when (renderer) {
            Renderer.NATIVE -> instrumentation.runOnMainSync {
                val px = dp * instrumentation.targetContext.resources.displayMetrics.density
                (view as RecyclerView).scrollBy(0, px.toInt())
            }
            Renderer.WEB -> evalString("window.scrollBy({ top: $dp, behavior: 'instant' }); ''")
        }
        settle()
        settle()
    }

    /** A new viewport in [layout], as reopening the reader in another mode builds one. */
    private fun rebuild(layout: NovelPageLayout) {
        instrumentation.runOnMainSync { (viewport as ReaderViewport).destroy() }
        scenario.close()
        build(layout)
    }

    private fun append(id: Long, html: String, title: String = "Chapter $id") {
        onMain { viewport.window.append(chapter(id, html, 0, null, title = title)) }
        awaitWhile { !fits.containsKey(id) }
        settle()
    }

    /** Turns forward until chapter [id] reads 100, checking every page on the way, and returns its percents. */
    private fun readToTheEnd(id: Long): List<Int> {
        val percents = mutableListOf(lastPercentOf(id))
        while (percents.last() < 100 && percents.size < MAX_PAGES) {
            forwardTap()
            percents += lastPercentOf(id)
        }
        return percents
    }

    private fun forwardTap(expectTurn: Boolean = true) =
        tapAndAwaitTurn(view.width * 0.1f, view.height * 0.8f, expectTurn)

    private fun backTap() = tapAndAwaitTurn(view.width * 0.9f, view.height * 0.2f)

    private fun tapAndAwaitTurn(x: Float, y: Float, expectTurn: Boolean = true) {
        val turns = turned.get()
        tap(x, y)
        if (!expectTurn) return
        awaitWhile { turned.get() == turns }
        assertTrue("[$renderer] the tap turned no page", turned.get() > turns)
        settle()
        assertWholeLines()
    }

    private fun lastTopLine(): Int? = topLines.lastOrNull()?.second

    private fun lastPercent(): Int = progress.last().second

    /** The live report, since the WebView page holds its settled one back while a resize is recent. */
    private fun lastPercentOf(id: Long): Int {
        awaitWhile { progress.none { it.first == id } }
        return progress.last { it.first == id }.second
    }

    // endregion

    // region geometry

    /** Whether a page has been shown, which is when the renderer draws its mask. */
    private fun pageShown(): Boolean = maskBottom() != null

    /** Where the mask below the page starts, in the renderer's own pixels, null before a page shows. */
    private fun maskBottom(): Float? = when (renderer) {
        Renderer.NATIVE -> {
            var height: Float? = null
            instrumentation.runOnMainSync {
                val field = NovelTextViewport::class.java.getDeclaredField("shownPageHeight")
                field.isAccessible = true
                height = field.get(viewport) as Float?
            }
            height?.let { it + readingTop() }
        }
        Renderer.WEB -> evalString(
            "(function(){ var m = document.getElementById('rk-page-mask-bottom');" +
                " return m ? String(parseFloat(m.style.top)) : ''; })()",
        ).toFloatOrNull()
    }

    /** The top margin a page keeps clear, the chapter's own top padding. */
    private fun readingTop(): Float = when (renderer) {
        Renderer.NATIVE ->
            (openedWith.margins.top + cutoutDp) * instrumentation.targetContext.resources.displayMetrics.density
        Renderer.WEB -> evalString(
            "String(parseFloat(getComputedStyle(document.querySelector('.rk-chapter')).paddingTop))",
        ).toFloat()
    }

    /**
     * No line on screen is cut by either edge of the page: the margin above it or the mask below it. A box taller
     * than the page, a picture's line, can never fit one and continues across pages, so it is left out.
     */
    private fun assertWholeLines() {
        val bottom = checkNotNull(maskBottom()) { "[$renderer] no page shown" }
        val top = readingTop()
        lineBoxesOnScreen().filter { (lineTop, lineBottom) ->
            lineBottom - lineTop <= bottom - top
        }.forEach { (lineTop, lineBottom) ->
            assertTrue(
                "[$renderer] line $lineTop..$lineBottom cut by the page bottom at $bottom",
                !(lineTop < bottom - SLACK && lineBottom > bottom + SLACK),
            )
            assertTrue(
                "[$renderer] line $lineTop..$lineBottom cut by the page top at $top",
                !(lineTop < top - SLACK && lineBottom > top + SLACK),
            )
        }
    }

    /** Every text line box the renderer has on screen, in its own pixels from the screen's top. */
    private fun lineBoxesOnScreen(): List<Pair<Float, Float>> = when (renderer) {
        Renderer.NATIVE -> {
            val boxes = mutableListOf<Pair<Float, Float>>()
            instrumentation.runOnMainSync {
                val recycler = view as RecyclerView
                recycler.children.forEach { item ->
                    (item as ViewGroup).children.filterIsInstance<ViewGroup>().forEach { container ->
                        container.children.filterIsInstance<TextView>().forEach { chunk ->
                            val layout = chunk.layout ?: return@forEach
                            val base = item.top + container.top + chunk.top + chunk.totalPaddingTop
                            for (line in 0..<layout.lineCount) {
                                val lineTop = (base + layout.getLineTop(line)).toFloat()
                                val lineBottom = (base + layout.getLineBottom(line)).toFloat()
                                if (lineBottom > 0 && lineTop < recycler.height) boxes += lineTop to lineBottom
                            }
                        }
                    }
                }
            }
            boxes
        }
        Renderer.WEB -> {
            val json = JSONArray(
                evalString(
                    """
                    (function(){
                      var out = [], r = document.createRange(), n;
                      var w = document.createTreeWalker(document.getElementById('rk-chapters'), NodeFilter.SHOW_TEXT);
                      while ((n = w.nextNode())) {
                        if (!n.nodeValue.trim() || n.parentElement.closest('script, style')) continue;
                        r.selectNodeContents(n);
                        // A paged seam's card is zoomed to fit, which its computed line height leaves out.
                        var lh = parseFloat(getComputedStyle(n.parentElement).lineHeight) * (n.parentElement.currentCSSZoom || 1);
                        var rs = r.getClientRects();
                        for (var i = 0; i < rs.length; i++) {
                          if (rs[i].bottom <= 0 || rs[i].top >= window.innerHeight || rs[i].height <= 0) continue;
                          var half = lh > rs[i].height ? (lh - rs[i].height) / 2 : 0;
                          out.push([rs[i].top - half, rs[i].bottom + half]);
                        }
                      }
                      return JSON.stringify(out);
                    })()
                    """.trimIndent(),
                ),
            )
            List(json.length()) {
                json.getJSONArray(it).let { box ->
                    box.getDouble(0).toFloat() to
                        box.getDouble(1).toFloat()
                }
            }
        }
    }

    // endregion

    // region plumbing

    private fun chapter(
        id: Long,
        html: String,
        percent: Int,
        topLine: Int?,
        share: ChapterProgress.Percent = ChapterProgress.Percent.ofWhole(percent),
        title: String = "Chapter $id",
    ) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = title,
        url = "/chapter/$id",
        html = html,
        baseUrl = null,
        progress = share,
        chapterNumber = id.toDouble(),
        novelId = 1L,
        sourceId = null,
        downloaded = false,
        isLast = false,
        topLine = topLine,
    )

    private fun long(marker: String) =
        (1..120).joinToString("") { "<p>$marker $it. " + "lorem <b>ipsum</b> dolor sit amet ".repeat(8) + "</p>" }

    /** Every paragraph a link to an anchor that does not exist, which neither renderer leaves the chapter for. */
    private fun linked(marker: String) = (1..120).joinToString("") {
        "<p><a href=\"#missing\">$marker $it. " + "lorem ipsum dolor sit amet ".repeat(8) + "</a></p>"
    }

    private fun short(marker: String) =
        (1..12).joinToString("") { "<p>$marker $it. " + "lorem <b>ipsum</b> dolor sit amet ".repeat(8) + "</p>" }

    private fun tap(x: Float, y: Float) {
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, x, y, down, down)
            touch(MotionEvent.ACTION_UP, x, y, down, down + 50L)
        }
    }

    private fun drag(x: Float, fromY: Float, toY: Float) = move(x, x, fromY, toY)

    private fun swipe(fromX: Float, toX: Float) = move(fromX, toX, view.height / 2f, view.height / 2f)

    private fun move(fromX: Float, toX: Float, fromY: Float, toY: Float) {
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, fromX, fromY, down, down)
            (1..20).forEach { step ->
                val f = step / 20f
                touch(
                    MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * f,
                    fromY + (toY - fromY) * f,
                    down,
                    down + step * 10L,
                )
            }
            touch(MotionEvent.ACTION_UP, toX, toY, down, down + 210L)
        }
    }

    private fun touch(action: Int, x: Float, y: Float, down: Long, time: Long) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun awaitWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
    }

    private fun settle() {
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
    }

    private fun evalString(js: String): String {
        val done = CountDownLatch(1)
        var result = "\"\""
        instrumentation.runOnMainSync {
            (viewport as NovelWebViewport).webView.evaluateJavascript(js) { value ->
                result = value ?: "\"\""
                done.countDown()
            }
        }
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
        return if (result.startsWith("\"")) JSONArray("[$result]").getString(0) else result
    }

    // endregion

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries

        const val TIMEOUT_S = 10L

        /** How far two measures of one line may differ, in pixels. */
        const val SLACK = 2f

        const val PAGES_WALKED = 6

        /** A chapter of [long] is fewer pages than this on any screen the tests run on. */
        const val MAX_PAGES = 120

        /** Far more than a tall picture's chapter has pages, so a stalled turn ends the walk. */
        const val PICTURE_TURNS = 40

        /** A saved line a little way into its page, so landing on the page's start would not pass by luck. */
        const val SAVED_LINE_INTO_PAGE = 40

        /** Far enough down a chapter of [long] to sit some pages in, and no multiple of a page. */
        const val SCROLLED_DP = 4_321

        /** A page height under the seam's padding alone (NovelChapterSeamView.PADDING_VERTICAL_DP above and below). */
        const val SHORT_PAGE_DP = 200

        /** Numbered past the chapters between it and chapter 1, so its seam warns of them. */
        const val GAP_ID = 5L

        /** Several lines on any test screen, so the seam is at its tallest. */
        const val LONG_TITLE = "Volume 3 Chapter 128: The night the lanterns of the old capital went dark and " +
            "nobody in the eastern ward could remember why (part two of three)"
    }
}
