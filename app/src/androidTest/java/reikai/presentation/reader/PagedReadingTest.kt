package reikai.presentation.reader

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private val view: View get() = (viewport as ReaderViewport).view

    private val fits = Collections.synchronizedMap(mutableMapOf<Long, Boolean>())
    private val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
    private val settled = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
    private val topLines = Collections.synchronizedList(mutableListOf<Pair<Long, Int?>>())
    private val visible = Collections.synchronizedList(mutableListOf<Long>())
    private val steps = Collections.synchronizedList(mutableListOf<Boolean>())
    private val turned = AtomicInteger()
    private var volumeKeys = false

    /** The settings the last chapter opened with, whose top margin is a page's. */
    private var openedWith = readerTestSettings

    private val callbacks = NovelViewportCallbacks(
        volumeKeysActive = { volumeKeys },
        onProgressChanged = { id, percent -> progress += id to percent },
        onProgressSettled = { id, percent -> settled += id to percent },
        onTopLine = { id, line -> topLines += id to line },
        onToggleMenu = {},
        onStepChapter = { steps += it },
        onVisibleChapter = { visible += it },
        onRetryBoundary = {},
        cutoutTopDp = { 0 },
        onChapterFits = { id, fit -> fits[id] = fit },
        onChapterEndSeen = {},
        onReaderScrolled = {},
        onPageTurned = { turned.incrementAndGet() },
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
        runBlocking(Dispatchers.Main) {
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
    fun theSeamBetweenTwoChaptersIsAPageOfItsOwn() {
        build(NovelPageLayout.PAGED_LTR)
        open(1L, short("first"))
        append(2L, long("second"))
        readToTheEnd(1L)
        forwardTap()
        assertEquals("[$renderer] the seam belongs to the chapter it opens", 2L, visible.last())
        assertEquals("[$renderer] no text at the top of the seam's page", 2L to null, topLines.last())
        forwardTap()
        assertEquals("[$renderer] the page after the seam is the chapter's first", 2L to 0, topLines.last())
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

    /** The chapter before joins after the first page shows and moves it, which the reader must not watch. */
    @Test
    fun theOpeningVeilStaysUpWhileTheChapterBeforeIsJoining() {
        assumeTrue("only the page veils its opening", renderer == Renderer.WEB)
        build(NovelPageLayout.PAGED_LTR)
        openJoining(2L, percent = 50)

        assertTrue("[$renderer] the veil dropped before the chapter before joined", veiled())
    }

    @Test
    fun theOpeningVeilDropsOnceTheChapterBeforeHasJoinedAndThePageSettled() {
        assumeTrue("only the page veils its opening", renderer == Renderer.WEB)
        build(NovelPageLayout.PAGED_LTR)
        openJoining(2L, percent = 50)

        runBlocking(Dispatchers.Main) {
            viewport.window.prepend(chapter(1L, long("first"), 0, null))
            viewport.window.setPreviousJoining(false)
        }
        awaitWhile { veiled() }

        assertTrue("[$renderer] the veil is still up once the chapter before joined", !veiled())
    }

    /** Opens [id] as the provider does while the chapter before is still loading, and waits for its first page. */
    private fun openJoining(id: Long, percent: Int) {
        fits.remove(id)
        runBlocking(Dispatchers.Main) {
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
    ) {
        fits.remove(id)
        openedWith = settings
        runBlocking(Dispatchers.Main) { viewport.load(chapter(id, html, percent, topLine), settings) }
        awaitWhile { !fits.containsKey(id) }
        assertTrue("[$renderer] chapter $id never rendered", fits.containsKey(id))
        awaitWhile { !pageShown() }
        settle()
        assertTrue("[$renderer] no page shown", pageShown())
        assertWholeLines()
    }

    private fun append(id: Long, html: String) {
        runBlocking(Dispatchers.Main) { viewport.window.append(chapter(id, html, 0, null)) }
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
            openedWith.margins.top * instrumentation.targetContext.resources.displayMetrics.density
        Renderer.WEB -> evalString(
            "String(parseFloat(getComputedStyle(document.querySelector('.rk-chapter')).paddingTop))",
        ).toFloat()
    }

    /** No line on screen is cut by either edge of the page: the margin above it or the mask below it. */
    private fun assertWholeLines() {
        val bottom = checkNotNull(maskBottom()) { "[$renderer] no page shown" }
        val top = readingTop()
        lineBoxesOnScreen().forEach { (lineTop, lineBottom) ->
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
                        var lh = parseFloat(getComputedStyle(n.parentElement).lineHeight);
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

    private fun chapter(id: Long, html: String, percent: Int, topLine: Int?) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = "Chapter $id",
        url = "/chapter/$id",
        html = html,
        baseUrl = null,
        progressPercent = percent,
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

        /** A saved line a little way into its page, so landing on the page's start would not pass by luck. */
        const val SAVED_LINE_INTO_PAGE = 40
    }
}
