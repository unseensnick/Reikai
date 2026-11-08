package reikai.presentation.reader

import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.core.view.isVisible
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
import reikai.presentation.reader.text.ChapterImageSpan
import reikai.presentation.reader.text.NovelChapterSeamView
import reikai.presentation.reader.text.pngOf
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

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
    private val endsSeen = Collections.synchronizedList(mutableListOf<Long>())
    private val opened = Collections.synchronizedList(mutableListOf<Any>())
    private val menuToggles = AtomicInteger()

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
        onToggleMenu = { menuToggles.incrementAndGet() },
        onStepChapter = { steps += it },
        onVisibleChapter = { visible += it },
        onRetryBoundary = {},
        cutoutTopDp = { cutoutDp },
        onChapterFits = { id, fit -> fits[id] = fit },
        onChapterEndSeen = { endsSeen += it },
        onReaderScrolled = {},
        onReaderTurned = { events += "turn" },
        onPageTurned = { turned.incrementAndGet() },
        onPageShown = { page, count -> shownPage.set(page to count) },
        onPictureOpened = { opened += it },
    )

    /** Whether the viewport built last shows two pages side by side, which a [rebuild] keeps. */
    private var spreadBuilt = false

    /** The view the renderer draws its column in, inside a spread's host when there is one. */
    private val column: View get() = (view as? NovelSpreadHost)?.getChildAt(0) ?: view

    private fun build(layout: NovelPageLayout, spread: Boolean = false, selectable: Boolean = false) {
        spreadBuilt = spread
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            viewport = when (renderer) {
                Renderer.NATIVE -> NovelTextViewport(
                    context = activity,
                    fontManager = activity.appGraph.novelFontManager,
                    textSelectable = selectable,
                    pageLayout = layout,
                    callbacks = callbacks,
                    autoScrollSpeed = InMemoryPreferenceStore().getFloat("speed", 1f),
                    autoScrollInterval = InMemoryPreferenceStore().getInt("interval", 5),
                    spread = spread,
                )
                Renderer.WEB -> NovelWebViewport(
                    context = activity,
                    fontManager = activity.appGraph.novelFontManager,
                    imageRequests = activity.appGraph.novelImageRequests,
                    textSelectable = selectable,
                    pageLayout = layout,
                    callbacks = callbacks,
                    useOriginalFonts = false,
                    sourceCssPriority = false,
                    autoScrollSpeed = InMemoryPreferenceStore().getFloat("speed", 1f),
                    autoScrollInterval = InMemoryPreferenceStore().getInt("interval", 5),
                    spread = spread,
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

    /** TransitionText caps its whole column at 460dp, the warning card with it, as the native seam draws it. */
    @Test
    fun theMissingChaptersWarningKeepsTheSeamsWidthCap() {
        assumeTrue("only the page styles its own seam", renderer == Renderer.WEB)
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"), title = LONG_TITLE)
        append(GAP_ID, short("second"), title = LONG_TITLE)
        assumeTrue("needs a column wider than the cap", evalString("String(window.innerWidth)").toFloat() > 600f)

        val width = evalString("String(document.querySelector('.rk-seam-warning').getBoundingClientRect().width)")

        assertTrue("[$renderer] the warning is ${width}px wide", width.toFloat() <= 460f)
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

    private fun openTallPicture() {
        val picture = "data:image/png;base64," + Base64.encodeToString(pngOf(300, 9_000), Base64.NO_WRAP)
        open(1L, short("before") + "<p><img src=\"$picture\"></p>" + short("after"))
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

    // region whole pictures

    /** As in a printed book: a picture that fits a page but not what is left of this one starts the next page. */
    @Test
    fun aPictureThatDoesNotFitTheRestOfThePageStartsTheNextWhole() = firstPictureShowsWhole(spread = false)

    @Test
    fun aPictureThatDoesNotFitTheRestOfASpreadsPageStartsTheNextWhole() = firstPictureShowsWhole(spread = true)

    /** A picture taller than a page is drawn smaller, whole on one page, and every page still turns once. */
    @Test
    fun aPictureTallerThanAPageShowsWholeOnOnePage() = tallPictureShowsWholeOnce(spread = false)

    @Test
    fun aPictureTallerThanAPageShowsWholeOnOneOfASpreadsPages() = tallPictureShowsWholeOnce(spread = true)

    private fun firstPictureShowsWhole(spread: Boolean) {
        build(NovelPageLayout.PAGED_LTR, spread = spread)
        open(1L, paragraphs(3))
        val height = pictureSourceHeightFor(PAGE_SHARE_PICTURE, spread)
        reopen(1L, paragraphs(3) + "<p><img src=\"${borderedPicture(600, height)}\"></p>" + short("after"))

        awaitTurnCopyGone()
        var shown = picturesOnPages().filterNotNull()
        while (shown.isEmpty() && lastPercentOf(1L) < 100) {
            forwardTap()
            awaitTurnCopyGone()
            shown = picturesOnPages().filterNotNull()
        }

        assertEquals("[$renderer] the picture's first page (${shownPage.get()}) shows it whole", listOf(true), shown)
    }

    private fun tallPictureShowsWholeOnce(spread: Boolean) {
        build(NovelPageLayout.PAGED_LTR, spread = spread)
        open(1L, short("before") + "<p><img src=\"${borderedPicture(300, 9_000)}\"></p>" + short("after"))
        val seen = picturesOnPages().filterNotNull().toMutableList()

        val pages = pagesTurning {
            forwardTap()
            awaitTurnCopyGone()
            seen += picturesOnPages().filterNotNull()
        }

        val expected = if (spread) spreadNumbers(shownPage.get()!!.second) else (0 until shownPage.get()!!.second).toList()
        assertEquals("[$renderer] pages shown, and the picture on them", expected to listOf(true), pages to seen)
    }

    /** Paged, a tap turns the page wherever it lands, so a picture opens on a long press, and that turns nothing. */
    @Test
    fun aLongPressOnAPictureOpensItAndTurnsNoPage() {
        build(NovelPageLayout.PAGED_LTR)
        val picture = borderedPicture(600, 400)
        open(1L, "<p><img src=\"$picture\"></p>" + short("after"))
        val turns = turned.get()

        longPress(shownPictureCentre())

        assertEquals("[$renderer] pictures opened, and pages turned", listOf<Any>(picture) to turns, opened.toList() to turned.get())
    }

    @Test
    fun aTapOnAPictureTurnsThePageWhenPaged() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, paragraphs(1))
        // Most of a page tall, so the forward tap below lands on it, on the page after the opening paragraph.
        val height = pictureSourceHeightFor(PAGE_SHARE_TALL_PICTURE, spread = false)
        reopen(1L, paragraphs(1) + "<p><img src=\"${borderedPicture(600, height)}\"></p>" + short("after"))
        forwardTap()
        shownPictureCentre()

        forwardTap()

        assertEquals("[$renderer] pictures opened", emptyList<Any>(), opened.toList())
    }

    @Test
    fun aTapOnAPictureOpensItWhileScrolling() {
        build(NovelPageLayout.SCROLL)
        val picture = borderedPicture(600, 400)
        openScrolled(1L, "<p><img src=\"$picture\"></p>" + short("after"))

        val (x, y) = shownPictureCentre()
        tap(x, y)
        awaitWhile { opened.isEmpty() }

        assertEquals("[$renderer] pictures opened", listOf<Any>(picture), opened.toList())
    }

    /** A picture has no text to select, so with selection on its long press still opens it and selects nothing. */
    @Test
    fun aLongPressOnAPictureSelectsNothingWithSelectionOn() {
        build(NovelPageLayout.PAGED_LTR, selectable = true)
        val picture = borderedPicture(600, 400)
        open(1L, "<p><img src=\"$picture\"></p>" + short("after"))

        longPress(shownPictureCentre())
        settle()

        assertEquals("[$renderer] opened, and text selected", listOf<Any>(picture) to "", opened.toList() to selectedText())
    }

    private fun longPress(at: Pair<Float, Float>) {
        val (x, y) = at
        val down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync { touch(MotionEvent.ACTION_DOWN, x, y, down, down) }
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout() * 2L)
        instrumentation.runOnMainSync { touch(MotionEvent.ACTION_UP, x, y, down, SystemClock.uptimeMillis()) }
        awaitWhile { opened.isEmpty() }
        settle()
    }

    private fun selectedText(): String = when (renderer) {
        Renderer.NATIVE -> {
            var selected = ""
            instrumentation.runOnMainSync {
                val chunks = (column as RecyclerView).children.flatMap { item ->
                    (item as ViewGroup).children.filterIsInstance<ViewGroup>().flatMap { it.children.filterIsInstance<TextView>() }
                }
                selected = chunks.filter { it.hasSelection() }
                    .joinToString("") { it.text.subSequence(it.selectionStart, it.selectionEnd) }
            }
            selected
        }
        Renderer.WEB -> evalString("String(window.getSelection())")
    }

    /** [pictureCentre] once the picture has landed and drawn. */
    private fun shownPictureCentre(): Pair<Float, Float> {
        var centre: Pair<Float, Float>? = null
        awaitWhile {
            centre = pictureCentre()
            centre == null
        }
        return checkNotNull(centre) { "[$renderer] no picture on screen" }
    }

    /** The middle of [borderedPng]'s picture on screen, in the viewport's own coordinates; null when none shows. */
    private fun pictureCentre(): Pair<Float, Float>? {
        val at = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(at) }
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val height = minOf(view.height, shot.height - at[1])
        val pixels = IntArray(view.width * height)
        shot.getPixels(pixels, 0, view.width, at[0], at[1], view.width, height)
        shot.recycle()
        val blue = pixels.indices.filter { isPictureBlue(pixels[it]) }
        if (blue.size < MIN_PICTURE_PIXELS) return null
        val xs = blue.map { it % view.width }
        val ys = blue.map { it / view.width }
        return (xs.min() + xs.max()) / 2f to (ys.min() + ys.max()) / 2f
    }

    private fun borderedPicture(width: Int, height: Int) =
        "data:image/png;base64," + Base64.encodeToString(borderedPng(width, height), Base64.NO_WRAP)

    /** A source height that draws a 600-wide picture at [share] of a page's height in the column it gets. */
    private fun pictureSourceHeightFor(share: Float, spread: Boolean): Int {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val margins = openedWith.margins
        val pageHeight = view.height - (margins.top + margins.bottom) * density
        val column = if (spread) {
            view.width / 2f - (margins.left + NovelSpreadHost.INNER_MARGIN_DP) * density
        } else {
            view.width - (margins.left + margins.right) * density
        }
        val drawnWidth = minOf(column, 600 * density)
        return (share * pageHeight * 600 / drawnWidth).toInt()
    }

    /**
     * For each page on screen (one, or a spread's two), whether [borderedPng]'s picture shows on it: null for not at
     * all, true when both its top and its bottom border do, read down the middle of the picture.
     */
    private fun picturesOnPages(): List<Boolean?> {
        val at = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(at) }
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val pages = if (spreadBuilt) 2 else 1
        val width = view.width / pages
        val height = minOf(view.height, shot.height - at[1])
        val result = (0 until pages).map { page ->
            val pixels = IntArray(width * height)
            shot.getPixels(pixels, 0, width, at[0] + page * width, at[1], width, height)
            val xs = pixels.indices.filter { isPictureBlue(pixels[it]) }.map { it % width }
            if (xs.size < MIN_PICTURE_PIXELS) return@map null
            val middle = (xs.min() + xs.max()) / 2
            val yellow = (0 until height).map { y -> isYellow(pixels[y * width + middle]) }
            yellow.indices.count { yellow[it] && (it == 0 || !yellow[it - 1]) } >= 2
        }
        shot.recycle()
        return result
    }

    private fun isPictureBlue(pixel: Int) = colourDistance(pixel, Color.rgb(40, 90, 160)) < INK_DISTANCE

    private fun isYellow(pixel: Int) = Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) < 90

    // endregion

    // region spreads

    /** As manga's dual page view: each turn moves two pages, and the number shown is the spread's second. */
    @Test
    fun aSpreadTurnsTwoPagesAndShowsItsSecondsNumber() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, long("first"))

        val pages = pagesTurning { forwardTap() }

        assertEquals("[$renderer] pages shown", spreadNumbers(shownPage.get()!!.second), pages)
    }

    /** Its second page is read too, so the opening spread is past the chapter's start. */
    @Test
    fun aSpreadReportsHowFarItsSecondPageReads() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, long("first"))

        assertTrue("[$renderer] the first spread reported its start", lastPercentOf(1L) > 0)
    }

    /** A chapter whose last page is a spread's second: read whole there, or it is never marked read. */
    @Test
    fun theLastSpreadReadsTheChapterWhole() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        openEvenChapter(1L)

        readToTheEnd(1L)

        assertEquals("[$renderer] the last spread's share", 100, lastPercentOf(1L))
    }

    @Test
    fun theLastSpreadSeesTheChaptersEnd() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        openEvenChapter(1L)
        val seenAtOpen = 1L in endsSeen

        readToTheEnd(1L)
        settle()

        assertEquals(
            "[$renderer] the end seen at the open, then on the last spread",
            false to true,
            seenAtOpen to (1L in endsSeen),
        )
    }

    /** As Mihon's transition page, which a spread never pairs: the card is drawn across both pages. */
    @Test
    fun theSeamIsOneCardAcrossTheSpread() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)

        forwardTap()

        assertEquals("[$renderer] the card's width", view.width, shownCardWidth())
    }

    /** Mihon's card rule in a spread: the card is no page, so the number and place stay the page turned from. */
    @Test
    fun theSeamsSpreadKeepsTheClosedChapter() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        val lastSpread = shownPage.get()
        val lastLine = topLines.last()

        forwardTap()

        assertEquals(
            "[$renderer] the card's number and line",
            lastSpread to lastLine,
            shownPage.get() to topLines.last(),
        )
    }

    @Test
    fun theSpreadAfterTheSeamIsTheChaptersFirstText() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)

        repeat(2) { forwardTap() }

        assertEquals("[$renderer] the line and the card", (2L to 0) to null, topLines.last() to shownCardWidth())
    }

    @Test
    fun backAcrossTheSeamReturnsToTheClosedChaptersLastSpread() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        val lastSpread = shownPage.get()
        repeat(2) { forwardTap() }
        val firstSpread = shownPage.get()

        backTap()
        val onCard = shownPage.get()
        backTap()

        assertEquals("[$renderer] on the card, then before it", firstSpread to lastSpread, onCard to shownPage.get())
    }

    /** As a rotation rebuilds it on the card: back to the page the reader turned from. */
    @Test
    fun aNewRendererOnTheSeamsSpreadLandsOnTheClosedChaptersLastSpread() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        val lastSpread = shownPage.get()
        forwardTap()
        val (id, line) = topLines.last()

        rebuild(NovelPageLayout.PAGED_LTR)
        open(id, short("first"), lastPercentOf(id), topLine = line)
        append(2L, long("second"))

        assertEquals("[$renderer] the rebuilt spread", lastSpread, shownPage.get())
    }

    /** As a rotation does: a new renderer lands on the spread the reader was on. */
    @Test
    fun aNewRendererKeepsTheSpread() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, long("first"))
        repeat(3) { forwardTap() }
        val spread = shownPage.get()
        val line = lastTopLine()

        rebuild(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"), lastPercentOf(1L), topLine = line)

        assertEquals("[$renderer] the rebuilt spread", spread, shownPage.get())
    }

    /** When wide across a rotation: one page at a time and back keeps the spread the reader was on. */
    @Test
    fun onePageAndBackKeepsTheSpread() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, long("first"))
        repeat(3) { forwardTap() }
        val spread = shownPage.get()

        rebuild(NovelPageLayout.PAGED_LTR, spread = false)
        open(1L, long("first"), lastPercentOf(1L), topLine = lastTopLine())
        rebuild(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, long("first"), lastPercentOf(1L), topLine = lastTopLine())

        assertEquals("[$renderer] the spread after one page and back", spread, shownPage.get())
    }

    /** The zones are the spread's: beside the gutter is the middle of the screen, not the edge of a page. */
    @Test
    fun aSpreadsTapZonesSpanBothPages() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        val zones = NovelTapZones(NovelTapLayout.RIGHT_AND_LEFT, TappingInvertMode.NONE, 12)
        open(1L, long("first"), settings = readerTestSettings.copy(tapZones = zones))
        val turns = turned.get()

        tap(view.width * 0.45f, view.height * 0.5f)
        settle()

        assertEquals("[$renderer] menu toggles and turns", 1 to turns, menuToggles.get() to turned.get())
    }

    /** Right to left the spread's first page is on the right, and the left edge reads on, as manga's does. */
    @Test
    fun rightToLeftSpreadsReadOnFromTheLeft() {
        build(NovelPageLayout.PAGED_RTL, spread = true)
        val zones = NovelTapZones(NovelTapLayout.RIGHT_AND_LEFT, TappingInvertMode.NONE, 12)
        open(1L, long("first"), settings = readerTestSettings.copy(tapZones = zones))
        val before = lastPercent()

        tapAndAwaitTurn(view.width * 0.1f, view.height * 0.5f)

        assertTrue("[$renderer] the left edge read on", lastPercent() > before)
    }

    /** A picture taller than a page continues across a spread, and no turn through it stalls or skips. */
    @Test
    fun turningThroughATallPictureShowsEverySpreadOnce() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        openTallPicture()

        val pages = pagesTurning { forwardTap() }

        assertEquals("[$renderer] pages shown", spreadNumbers(shownPage.get()!!.second), pages)
    }

    @Test
    fun turningBackThroughATallPictureShowsEverySpreadOnce() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        openTallPicture()
        pagesTurning { forwardTap() }

        val pages = pagesTurning(stopAt = 1) { backTap() }

        assertEquals("[$renderer] pages shown", spreadNumbers(shownPage.get()!!.second).reversed(), pages)
    }

    /** A line saved on a spread's second page is the reader's place there, not the spread's first line. */
    @Test
    fun aLineOnASpreadsSecondPageStaysTheReadersPlace() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, long("first"))
        forwardTap()
        val spreadStart = checkNotNull(lastTopLine())
        forwardTap()
        val onItsSecondPage = checkNotNull(lastTopLine()) - SAVED_LINE_INTO_PAGE

        rebuild(NovelPageLayout.PAGED_LTR)
        open(1L, long("first"), lastPercentOf(1L), topLine = onItsSecondPage)

        assertTrue("[$renderer] the place fell back to the spread's start", checkNotNull(lastTopLine()) > spreadStart)
    }

    /**
     * The chapter above leaving shortens the page, and the browser pulls a column at its end back up. That pull is
     * not the reader's: counted as theirs, the page's place holder scrolled the column a second time and the snap
     * after it showed the right spread again, so the wrong place only shows on the way, as a scroll position.
     */
    @Test
    fun droppingTheChapterAboveAtTheEndNeverShowsAnotherPlace() {
        assumeTrue("only the page's scroll is pulled back by the browser", renderer == Renderer.WEB)
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, long("first"))
        append(2L, short("second"))
        readToTheEnd(1L)
        repeat(2) { forwardTap() }
        readToTheEnd(2L)
        evalString(
            "window.rkScrolls = []; addEventListener('scroll', function () { rkScrolls.push(Math.round(scrollY)); }); ''",
        )

        onMain { viewport.window.evict(1L) }
        repeat(3) { settle() }

        val seen = JSONArray(evalString("JSON.stringify(rkScrolls)")).let { json ->
            List(json.length()) { json.getInt(it) }
        }
        val final = evalString("String(Math.round(scrollY))").toInt()
        // A pixel either way is a fractional scroll rounding apart; the column moved elsewhere is a page off.
        assertEquals("[$renderer] other places through the drop", emptyList<Int>(), seen.filter { abs(it - final) > 1 })
    }

    /**
     * A spread draws its column twice, and a WebView draws through a render-thread functor that a Vulkan driver
     * crashed in once with it drawn twice a frame. On a layer it draws once and the layer twice; the crash is too
     * rare to reproduce on demand, so the layer itself is what is pinned.
     */
    @Test
    fun aSpreadDrawsThePageOnceAFrame() {
        assumeTrue("only the page draws through a functor", renderer == Renderer.WEB)
        build(NovelPageLayout.PAGED_LTR, spread = true)

        var layer = View.LAYER_TYPE_NONE
        instrumentation.runOnMainSync { layer = column.layerType }

        assertEquals("[$renderer] the column's layer", View.LAYER_TYPE_HARDWARE, layer)
    }

    /**
     * A spread with no second page hides the half below its page, which still holds the column on: the card and a
     * chapter short enough to fit one page. That chapter's end is not on screen, so it is not seen.
     */
    @Test
    fun aLoneLastPageDoesNotSeeTheNextChaptersEnd() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        openOddChapter(1L)
        append(2L, paragraphs(2))

        readToTheEnd(1L)
        settle()

        assertTrue("[$renderer] the next chapter's end was seen from a lone page", 2L !in endsSeen)
    }

    /** One page at a time the card is a page of its own too, with the next chapter under the mask below it. */
    @Test
    fun theCardDoesNotSeeTheNextChaptersEndOnOnePage() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, paragraphs(2))
        readToTheEnd(1L)

        forwardTap()
        settle()

        assertTrue("[$renderer] the next chapter's end was seen from the card", 2L !in endsSeen)
    }

    /** The card stands alone too, and the chapter below it is not on screen until the reader turns onto it. */
    @Test
    fun theCardDoesNotSeeTheNextChaptersEnd() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        open(1L, short("first"))
        append(2L, paragraphs(2))
        readToTheEnd(1L)

        forwardTap()
        settle()

        assertTrue("[$renderer] the next chapter's end was seen from the card", 2L !in endsSeen)
    }

    /**
     * A picture is sized to the page's width when the chapter renders, which for the chapter opened is before the
     * list has a width of its own. Sized to the screen's instead, a spread's picture was twice its page and cut.
     */
    @Test
    fun aPictureFitsItsPageInASpread() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        val picture = "data:image/png;base64," + Base64.encodeToString(borderedPng(1_600, 1_200), Base64.NO_WRAP)

        open(1L, "<p><img src=\"$picture\"></p>" + short("after"))
        awaitTurnCopyGone()

        assertEquals("[$renderer] the picture's left and right edges on its page", 2, yellowEdgesOnFirstPage())
    }

    /** A dark picture inside a yellow border, whose two side edges show only if the whole width is drawn. */
    private fun borderedPng(width: Int, height: Int): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.rgb(40, 90, 160))
        val border = android.graphics.Paint().apply {
            color = Color.YELLOW
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = BORDER_PX.toFloat()
        }
        canvas.drawRect(BORDER_PX / 2f, BORDER_PX / 2f, width - BORDER_PX / 2f, height - BORDER_PX / 2f, border)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
    }.toByteArray()

    /** How many separate runs of yellow cross the first page's half, a third of the way down the picture's top. */
    private fun yellowEdgesOnFirstPage(): Int {
        val at = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(at) }
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val y = (at[1] + openedWith.margins.top * density + view.height * PICTURE_ROW).toInt()
        val row = IntArray(view.width / 2)
        shot.getPixels(row, 0, row.size, at[0], y, row.size, 1)
        shot.recycle()
        val yellow = row.map { Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) < 90 }
        return yellow.indices.count { yellow[it] && (it == 0 || !yellow[it - 1]) }
    }

    /** A chapter's lone last page is a spread's first half, the left reading left to right. */
    @Test
    fun aLoneLastPageTakesTheLeftHalf() {
        build(NovelPageLayout.PAGED_LTR, spread = true)
        openOddChapter(1L)

        readToTheEnd(1L)

        assertEquals("[$renderer] text on the left half, then the right", true to false, inkOnHalves())
    }

    /** Right to left the first half is the right one, as manga's dual page view lays a spread. */
    @Test
    fun aLoneLastPageTakesTheRightHalfRightToLeft() {
        build(NovelPageLayout.PAGED_RTL, spread = true)
        val zones = NovelTapZones(NovelTapLayout.RIGHT_AND_LEFT, TappingInvertMode.NONE, 12)
        openOddChapter(1L, readerTestSettings.copy(tapZones = zones))

        readToTheEnd(1L, forward = { tapAndAwaitTurn(view.width * 0.1f, view.height * 0.5f) })

        assertEquals("[$renderer] text on the left half, then the right", false to true, inkOnHalves())
    }

    /** Opens [id] as a chapter of an odd page count past its first spread, whose last page stands alone. */
    private fun openOddChapter(id: Long, settings: NovelReaderSettings = readerTestSettings) {
        (4..60).forEach { paragraphs ->
            reopen(id, paragraphs(paragraphs), settings)
            if (shownPage.get()!!.second.let { it >= 3 && it % 2 == 1 }) return
        }
        error("[$renderer] no chapter of an odd page count")
    }

    /** Whether each half of the spread on screen draws anything but the page colour, by a screenshot. */
    private fun inkOnHalves(): Pair<Boolean, Boolean> {
        awaitTurnCopyGone()
        val at = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(at) }
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val background = shot.getPixel(at[0] + 1, at[1] + view.height / 2)
        fun inked(from: Int, to: Int): Boolean {
            val row = IntArray(to - from)
            // Clear of the status bar's icons and the gesture handle, which an edge-to-edge reader draws under.
            val rows = (at[1] + view.height * INK_TOP).toInt() until (at[1] + view.height * INK_BOTTOM).toInt()
            return (rows step 4).any { y ->
                shot.getPixels(row, 0, row.size, from, y, row.size, 1)
                row.any { colourDistance(it, background) > INK_DISTANCE }
            }
        }
        val mid = at[0] + view.width / 2
        val halves = inked(at[0], mid) to inked(mid, at[0] + view.width)
        shot.recycle()
        return halves
    }

    /** A WebView turn shows a copy of the spread turned from until a few frames after the new one paints, a second at
     *  most (NovelSpreadHost.hold), which a slow screen can still be showing when the turn is reported. */
    private fun awaitTurnCopyGone() = repeat(4) { settle() }

    private fun colourDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /** The number each spread of a chapter [count] pages long shows: its second page's, or a lone last page's. */
    private fun spreadNumbers(count: Int): List<Int> = (0 until count step 2).map { minOf(it + 1, count - 1) }

    /** Opens [id] as a chapter of an even page count past its first spread, whose last page is a spread's second. */
    private fun openEvenChapter(id: Long) {
        (4..60).forEach { paragraphs ->
            reopen(id, paragraphs(paragraphs))
            if (shownPage.get()!!.second.let { it >= 4 && it % 2 == 0 }) return
        }
        error("[$renderer] no chapter of an even page count")
    }

    /** Opens [id] again with its earlier reports dropped, so none read before the new open's own is a stale one. */
    private fun reopen(id: Long, html: String, settings: NovelReaderSettings = readerTestSettings) {
        synchronized(progress) { progress.removeAll { it.first == id } }
        open(id, html, settings = settings)
        lastPercentOf(id)
    }

    private fun paragraphs(count: Int) =
        (1..count).joinToString("") { "<p>even $it. " + "lorem <b>ipsum</b> dolor sit amet ".repeat(8) + "</p>" }

    /** The width of the card drawn across the spread, null while none is. */
    private fun shownCardWidth(): Int? {
        var width: Int? = null
        instrumentation.runOnMainSync {
            width =
                (view as ViewGroup).children.filterIsInstance<NovelChapterSeamView>().firstOrNull {
                    it.isVisible
                }?.width
        }
        return width
    }

    // endregion

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
        endsSeen.remove(id)
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
    private fun rebuild(layout: NovelPageLayout, spread: Boolean = spreadBuilt) {
        instrumentation.runOnMainSync { (viewport as ReaderViewport).destroy() }
        scenario.close()
        build(layout, spread)
    }

    private fun append(id: Long, html: String, title: String = "Chapter $id") {
        onMain { viewport.window.append(chapter(id, html, 0, null, title = title)) }
        awaitWhile { !fits.containsKey(id) }
        settle()
    }

    /** Turns forward until chapter [id] reads 100, checking every page on the way, and returns its percents. */
    private fun readToTheEnd(id: Long, forward: () -> Unit = { forwardTap() }): List<Int> {
        val percents = mutableListOf(lastPercentOf(id))
        while (percents.last() < 100 && percents.size < MAX_PAGES) {
            forward()
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
                val recycler = column as RecyclerView
                recycler.children.forEach { item ->
                    (item as ViewGroup).children.filterIsInstance<ViewGroup>().forEach { container ->
                        container.children.filterIsInstance<TextView>().forEach { chunk ->
                            val layout = chunk.layout ?: return@forEach
                            val base = item.top + container.top + chunk.top + chunk.totalPaddingTop
                            // A picture's box is the picture, without the margins its line keeps, as the page's is.
                            val pictures = (chunk.text as? android.text.Spanned)?.let { text ->
                                text.getSpans(0, text.length, ChapterImageSpan::class.java)
                                    .associateBy { layout.getLineForOffset(text.getSpanStart(it)) }
                            }.orEmpty()
                            for (line in 0..<layout.lineCount) {
                                val picture = pictures[line]
                                val lineTop = (base + layout.getLineTop(line) + (picture?.topPx ?: 0)).toFloat()
                                val lineBottom = picture?.let { lineTop + it.drawable.bounds.height() }
                                    ?: (base + layout.getLineBottom(line)).toFloat()
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

        /** Past antialiasing on a dark page, short of any glyph's ink. */
        const val INK_DISTANCE = 60

        /** The band of the screen a page's ink is looked for in, as shares of its height. */
        const val INK_TOP = 0.05f

        /** A picture border thick enough to survive being drawn at a third of its size. */
        const val BORDER_PX = 24

        /** Down the screen from the reading top, as a share of its height: inside a wide picture at a page's top. */
        const val PICTURE_ROW = 0.08f

        const val INK_BOTTOM = 0.92f

        /** Of a page's height: more than the text above it leaves, less than a whole page. */
        const val PAGE_SHARE_PICTURE = 0.8f

        /** Of a page's height: reaching past where [forwardTap] lands. */
        const val PAGE_SHARE_TALL_PICTURE = 0.95f

        /** Fewer than a picture drawn at its smallest has, more than antialiasing leaves. */
        const val MIN_PICTURE_PIXELS = 400

        /** Numbered past the chapters between it and chapter 1, so its seam warns of them. */
        const val GAP_ID = 5L

        /** Several lines on any test screen, so the seam is at its tallest. */
        const val LONG_TITLE = "Volume 3 Chapter 128: The night the lanterns of the old capital went dark and " +
            "nobody in the eastern ward could remember why (part two of three)"
    }
}
