package reikai.presentation.reader

import android.graphics.Color
import android.graphics.Rect
import android.text.PrecomputedText
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import reikai.domain.reader.ChapterProgress
import reikai.presentation.reader.text.NovelChapterSeamView
import reikai.presentation.reader.text.PngServer
import reikai.presentation.reader.text.pngOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Where the native renderer leaves the reader when the window grows around the chapter just opened.
 * Driven through the real viewport rather than a stand-in recycler, because the defect this pins came
 * from the order the viewport inserts and renders in, which a simulation decides for itself.
 */
@RunWith(AndroidJUnit4::class)
class NovelTextViewportWindowTest {

    @get:Rule
    val animationsOff = AnimationsOffRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var viewport: NovelTextViewport

    /** The line at the top of the screen, as the viewport last reported it. */
    @Volatile
    private var topLine: Int? = null

    /** The last fit answer per chapter, which is also the sign that a chapter has rendered. */
    private val fits = ConcurrentHashMap<Long, Boolean>()

    private companion object {
        const val PREVIOUS = 1L
        const val SHORT = 2L
        const val LONG = 3L
        const val NEXT = 4L

        /** A frame or so of a fling, in pixels. */
        const val SCROLLED = 100
        const val TIMEOUT_S = 10L

        /** Long enough for a chapter to render on an emulator, for one added off screen that cannot say it has. */
        const val PREPEND_WAIT_S = 3L

        /** Long enough for a settings redraw to rebuild a window of long chapters on an emulator. */
        const val REDRAW_WAIT_S = 4L

        /** A picture tall enough to move the text below it, and how long it is held back. */
        const val SLOW_PICTURE_PX = 400
        const val SLOW_PICTURE_MS = 3_000L

        /** Long enough after the slow picture for it to decode and lay out on an emulator. */
        const val PICTURE_SETTLE_MS = 2_000L
    }

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            viewport = NovelTextViewport(
                context = activity,
                fontManager = activity.appGraph.novelFontManager,
                textSelectable = false,
                volumeKeysActive = { false },
                onProgressChanged = { _, _ -> },
                onProgressSettled = { _, _ -> },
                onTopLine = { _, line -> topLine = line },
                onToggleMenu = {},
                onStepChapter = {},
                onVisibleChapter = {},
                onRetryBoundary = {},
                cutoutTopDp = { 0 },
                onChapterFits = { id, fit -> fits[id] = fit },
                onChapterEndSeen = {},
            )
            activity.setContentView(viewport.view)
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            instrumentation.runOnMainSync { viewport.destroy() }
            scenario.close()
        }
    }

    /**
     * A last chapter shorter than the screen cannot scroll to the top of it, so the chapter before it
     * arriving has to fill the space above. It used to arrive before its text had a height, become the
     * list's anchor at the top of the screen, and push the short chapter off the bottom as it grew.
     */
    @Test
    fun aShortLastChapterStaysOnScreenWhenTheChapterBeforeItArrives() {
        open(SHORT, "<p>short</p>", isLast = true)
        prepend(PREVIOUS, long("previous"))
        assertTrue("the short chapter is not on screen: ${shownAt("short")}", onScreen("short"))
    }

    /** The end marker takes the room below a short last chapter, so the chapter lands above it with the
     *  marker on screen, as manga's last page lands above its transition. */
    @Test
    fun aShortLastChapterLandsAboveItsEndMarker() {
        open(SHORT, "<p>short</p>", isLast = true)
        prepend(PREVIOUS, long("previous"))
        val text = shownAt("short")
        val marker = endMarkerTop()
        assertTrue(
            "the end marker at $marker is not on screen below the chapter at $text",
            text != null && marker != null && marker >= text.bottom && marker < viewport.view.height,
        )
    }

    /** Hiding a seam above the text the reader is in shortens the item above that text, which the
     *  layout manager does not take back, the same growth a failure turning up is. */
    @Test
    fun aSeamHiddenAboveTheTextKeepsTheLineTheReaderWasOn() {
        open(LONG, long("current"))
        prepend(PREVIOUS, long("previous"))
        scrollToTop("current 60.")
        instrumentation.runOnMainSync {
            viewport.applySettings(readerTestSettings.copy(alwaysShowChapterTransition = false))
        }
        settle()
        assertEquals(0, shownAt("current 60.")?.top)
    }

    /** A colour change measures nothing, so the chapter keeps the layout it was precomputed with. */
    @Test
    fun aColourChangeKeepsTheChaptersPrecomputedText() {
        open(LONG, long("current"))
        instrumentation.runOnMainSync {
            viewport.applySettings(readerTestSettings.copy(textColor = "#FF8800"))
        }
        settle()
        var precomputed = false
        instrumentation.runOnMainSync {
            precomputed = textViews(viewport.view).first { it.text.contains("current 1.") }.text is PrecomputedText
        }
        assertTrue("the chapter's text was copied out of its precomputed layout", precomputed)
    }

    /** The marker naming both chapters appears on the lower one once the upper one has joined. */
    @Test
    fun aChapterGainingOneAboveItShowsTheSeamBetweenThem() {
        open(LONG, long("current"))
        val alone = seamsShown()
        prepend(PREVIOUS, long("previous"))
        assertEquals(0 to 1, alone to seamsShown())
    }

    /** The window changes as the reader crosses a seam, usually mid-fling, and putting the reading
     *  chapter back after a change must not take the reader's own scroll back with it. */
    @Test
    fun aWindowChangeKeepsWhatTheReaderScrolledMeanwhile() {
        open(LONG, long("current"))
        append(NEXT, long("next"))
        val before = shownAt("current 1.")
        instrumentation.runOnMainSync {
            viewport.evict(NEXT)
            viewport.view.scrollBy(0, SCROLLED)
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        assertEquals(before?.top?.minus(SCROLLED), shownAt("current 1.")?.top)
    }

    /**
     * The seam that turns up above the text once the chapter before it joins grows the item above the
     * reader. The line is put back, and no frame may be drawn before it is: one drawn at the grown layout
     * showed the reader a paragraph of text they had already read, for as long as that frame took.
     */
    @Test
    fun noFrameShowsTheReaderMovedWhenTheSeamAboveTheTextTurnsUp() {
        open(LONG, long("current"))
        scrollToTop("current 60.")
        val drawn = drawnTopsOf("current 60.") { prepend(PREVIOUS, long("previous")) }
        assertEquals(listOf(0), drawn.filterNotNull().distinct())
    }

    /** A rebuilt renderer lands on the reader's line, and no frame may show the chapter where it lay before. */
    @Test
    fun noFrameShowsAChapterRebuiltAtALineBeforeItLands() {
        open(LONG, long("current"))
        scrollToTop("current 60.")
        val line = checkNotNull(topLine)
        val drawn = drawnTopsOf("current 60.") {
            runBlocking(Dispatchers.Main) {
                viewport.load(chapter(LONG, long("current")).copy(topLine = line), readerTestSettings)
            }
            awaitRendered(LONG)
        }
        assertEquals(listOf(0), drawn.filterNotNull().distinct())
    }

    /** A line landing does not wait for a slow picture above the line, and holds the line once it arrives. */
    @Test
    fun noFrameShowsAChapterRebuiltAtALineWhileAPictureAboveItLoads() {
        open(LONG, long("current"))
        scrollToTop("current 60.")
        val line = checkNotNull(topLine)
        PngServer(pngOf(SLOW_PICTURE_PX, SLOW_PICTURE_PX)).use { server ->
            val html = "<p><img src=\"${server.url("slow", delayMs = SLOW_PICTURE_MS)}\"></p>" + long("current")
            val drawn = drawnTopsOf("current 60.") {
                runBlocking(Dispatchers.Main) {
                    viewport.load(chapter(LONG, html).copy(topLine = line), readerTestSettings)
                }
                Thread.sleep(SLOW_PICTURE_MS + PICTURE_SETTLE_MS)
            }
            assertEquals(listOf(0), drawn.filterNotNull().distinct())
        }
    }

    /** A restyle re-measures the text above the reader in place, the other growth the line is put back after. */
    @Test
    fun noFrameShowsTheReaderMovedWhenTheLineHeightChanges() {
        open(LONG, long("current"))
        scrollToTop("current 60.")
        val drawn = drawnTopsOf("current 60.") {
            instrumentation.runOnMainSync { viewport.applySettings(readerTestSettings.copy(lineHeight = 2.2f)) }
        }
        assertEquals(listOf(0), drawn.filterNotNull().distinct())
    }

    /** The case that always worked, kept so a fix for the short one cannot cost it. */
    @Test
    fun aLongChapterOpenedAtItsStartKeepsItsFirstLineAtTheTop() {
        open(LONG, long("current"))
        val before = shownAt("current 1.")
        prepend(PREVIOUS, long("previous"))
        assertEquals(before, shownAt("current 1."))
    }

    /**
     * With paragraph spacing set, every text-size step redraws the window, and a slider sends steps
     * faster than a redraw finishes. A second one used to find the list emptied by the first and put
     * the reader back at the chapter's first line.
     */
    @Test
    fun twoQuickTextSizeStepsKeepTheLineTheReaderWasOn() {
        open(LONG, long("current"))
        append(NEXT, long("next"))
        prepend(PREVIOUS, long("previous"))
        scrollToTop("current 60.")
        dragTextSize()
        assertEquals(0, shownAt("current 60.")?.top)
    }

    /**
     * With the next chapter's seam on screen the reader is past where the chapter's last line reaches
     * the bottom of the screen, which a share of the chapter counts as all of it, so a redraw that put
     * the reader back by share moved them back by up to a screen.
     */
    @Test
    fun aTextSizeStepAtAChaptersEndKeepsTheLineTheReaderWasOn() {
        open(LONG, long("current"))
        append(NEXT, long("next"))
        instrumentation.runOnMainSync { viewport.seekTo(ChapterProgress.Percent(10_000)) }
        settle()
        scrollToTop("current 120.")
        instrumentation.runOnMainSync {
            viewport.applySettings(readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 2))
        }
        settle(REDRAW_WAIT_S)
        assertEquals(0, shownAt("current 120.")?.top)
    }

    /** The redraw a second step supersedes used to go on adding the neighbours at its own size. */
    @Test
    fun aNeighbourTakesTheLastSizeOfATextSizeDrag() {
        open(LONG, long("current"))
        append(NEXT, long("next"))
        dragTextSize()
        instrumentation.runOnMainSync { (viewport.view as RecyclerView).scrollToPosition(1) }
        settle()
        assertEquals(
            setOf(sp(readerTestSettings.fontSize + 4)),
            textViews(viewport.view).filter {
                it.text.contains("next")
            }.map { it.textSize }.toSet(),
        )
    }

    /** A restyle reaches only views that exist, and a chapter still rendering has none yet. */
    @Test
    fun aChapterStillRenderingWhenTheColourChangesTakesTheNewColour() {
        open(SHORT, "<p>short</p>")
        runBlocking(Dispatchers.Main) {
            val arriving = launch(start = CoroutineStart.UNDISPATCHED) {
                viewport.append(chapter(NEXT, long("next")))
            }
            viewport.applySettings(readerTestSettings.copy(textColor = "#ff0000"))
            arriving.join()
        }
        settle()
        assertEquals(
            setOf(Color.RED),
            textViews(viewport.view).filter {
                it.text.contains("next")
            }.map { it.currentTextColor }.toSet(),
        )
    }

    /** With no indent or spacing a text-size change restyles in place, and the text above the reader
     *  growing inside their chapter used to carry them back towards its start. */
    @Test
    fun aRestyleThatGrowsTheTextKeepsTheLineTheReaderWasOn() {
        val flat = readerTestSettings.copy(paragraphIndent = 0f, paragraphSpacing = 0f)
        open(LONG, long("current"), flat)
        scrollToTop("current 60.")
        instrumentation.runOnMainSync { viewport.applySettings(flat.copy(fontSize = flat.fontSize + 4)) }
        settle()
        assertEquals(0, shownAt("current 60.")?.top)
    }

    /** The chapter before this one failing after the reader has scrolled in draws its row above the
     *  text inside the same item, which is growth the layout manager does not take back. */
    @Test
    fun aFailureAppearingAboveTheTextKeepsTheLineTheReaderWasOn() {
        open(LONG, long("current"))
        scrollToTop("current 60.")
        instrumentation.runOnMainSync {
            viewport.setBoundaryFailures(
                NovelReaderViewModel.BoundaryFailure("offline", failedAtElapsedMs = 1L, chapterId = PREVIOUS),
                null,
            )
        }
        settle()
        assertEquals(0, shownAt("current 60.")?.top)
    }

    /** The host changes the window and the edges in one step, and each change used to post its own
     *  correction: the second read the first's scroll as the reader's and scrolled it again. */
    @Test
    fun aFailureArrivingWithAWindowChangeKeepsTheLineTheReaderWasOn() {
        open(LONG, long("current"))
        append(NEXT, long("next"))
        scrollToTop("current 60.")
        instrumentation.runOnMainSync {
            viewport.evict(NEXT)
            viewport.setBoundaryFailures(
                NovelReaderViewModel.BoundaryFailure("offline", failedAtElapsedMs = 1L, chapterId = PREVIOUS),
                null,
            )
        }
        settle()
        assertEquals(0, shownAt("current 60.")?.top)
    }

    /**
     * A chapter is split across views every few thousand characters, and the gap where one view ends
     * and the next begins must be the gap between any two paragraphs. A view ending in a newline drew
     * an empty line there, and a view's last line loses the line spacing every other line gets.
     */
    @Test
    fun aChunkSeamSpacesItsParagraphsLikeAnyOther() {
        open(LONG, long("current"))
        var seam = 0
        var inside = 0
        instrumentation.runOnMainSync {
            val (first, second) = textViews(viewport.view).filter { it.text.contains("current") }
            val lastLine = first.layout.getLineForOffset(first.text.trimEnd('\n').length - 1)
            seam = baselineOf(second, 0) - baselineOf(first, lastLine)
            val secondParagraph = first.layout.getLineForOffset(first.text.indexOf("current 2."))
            inside = baselineOf(first, secondParagraph) - baselineOf(first, secondParagraph - 1)
        }
        assertEquals(inside, seam)
    }

    /** An evicted chapter's holder waits in the pool until another chapter takes it, and it used to
     *  hold on to that chapter's text and pictures all the while. */
    @Test
    fun chaptersReplacedByAnOpenLeaveNoTextInTheRecyclerPool() {
        open(SHORT, "<p>short</p>")
        append(NEXT, "<p>next</p>")
        open(LONG, long("current"))
        assertEquals(0, pooledTextViews())
    }

    private fun long(marker: String) =
        (1..120).joinToString("") { "<p>$marker $it. " + "lorem ipsum dolor sit amet ".repeat(8) + "</p>" }

    private fun chapter(id: Long, html: String, isLast: Boolean = false) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = "Chapter $id",
        url = "/chapter/$id",
        html = html,
        baseUrl = null,
        progressPercent = 0,
        chapterNumber = id.toDouble(),
        novelId = 1L,
        sourceId = null,
        downloaded = false,
        isLast = isLast,
    )

    private fun open(
        id: Long,
        html: String,
        settings: NovelReaderSettings = readerTestSettings,
        isLast: Boolean = false,
    ) {
        runBlocking(Dispatchers.Main) {
            viewport.load(chapter(id, html, isLast), settings)
        }
        awaitRendered(id)
    }

    private fun append(id: Long, html: String) {
        runBlocking(Dispatchers.Main) { viewport.append(chapter(id, html)) }
        awaitRendered(id, required = false)
    }

    private fun prepend(id: Long, html: String) {
        runBlocking(Dispatchers.Main) { viewport.prepend(chapter(id, html)) }
        awaitRendered(id, required = false)
    }

    /** Two text-size steps with nothing in between, as a slider drag sends them. */
    private fun dragTextSize() {
        instrumentation.runOnMainSync {
            viewport.applySettings(readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 2))
            viewport.applySettings(readerTestSettings.copy(fontSize = readerTestSettings.fontSize + 4))
        }
        settle(REDRAW_WAIT_S)
    }

    /** Waits for [id]'s first fit report, then for the frames after it to settle. Not [required] for a
     *  chapter added off screen, which is never laid out and so never reports. */
    private fun awaitRendered(id: Long, required: Boolean = true) {
        val wait = if (required) TIMEOUT_S else PREPEND_WAIT_S
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(wait)
        while (!fits.containsKey(id) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("chapter $id never rendered", !required || fits.containsKey(id))
        settle()
    }

    private fun settle(seconds: Long = 0) {
        Thread.sleep(TimeUnit.SECONDS.toMillis(seconds) + 500)
        instrumentation.waitForIdleSync()
    }

    /** Scrolls until the first line starting with [text] is at the top of the viewport. */
    private fun scrollToTop(text: String) {
        val top = checkNotNull(shownAt(text)).top
        instrumentation.runOnMainSync { viewport.view.scrollBy(0, top) }
        settle()
    }

    /** Where the first line starting with [text] is drawn, relative to the viewport, or null when it is not laid out. */
    private fun shownAt(text: String): Rect? {
        var rect: Rect? = null
        instrumentation.runOnMainSync { rect = lineRect(text) }
        return rect
    }

    /** [shownAt] on the main thread, for a caller already on it. */
    private fun lineRect(text: String): Rect? {
        val line = textViews(viewport.view).firstOrNull { it.text.contains(text) } ?: return null
        val layout = line.layout ?: return null
        val offset = line.text.indexOf(text)
        val lineIndex = layout.getLineForOffset(offset)
        val origin = IntArray(2).also(viewport.view::getLocationOnScreen)
        val at = IntArray(2).also(line::getLocationOnScreen)
        val top = at[1] - origin[1] + line.paddingTop + layout.getLineTop(lineIndex)
        val bottom = at[1] - origin[1] + line.paddingTop + layout.getLineBottom(lineIndex)
        return Rect(0, top, viewport.view.width, bottom)
    }

    /** Where the line starting with [text] was in every frame drawn while [change] ran and settled. */
    private fun drawnTopsOf(text: String, change: () -> Unit): List<Int?> {
        val tops = java.util.concurrent.CopyOnWriteArrayList<Int?>()
        val listener = android.view.ViewTreeObserver.OnDrawListener { tops += lineRect(text)?.top }
        instrumentation.runOnMainSync { viewport.view.viewTreeObserver.addOnDrawListener(listener) }
        change()
        settle()
        instrumentation.runOnMainSync { viewport.view.viewTreeObserver.removeOnDrawListener(listener) }
        return tops.toList()
    }

    /** A line's baseline in its column's coordinates. */
    private fun baselineOf(view: TextView, line: Int) =
        view.top + view.totalPaddingTop + view.layout.getLineBaseline(line)

    private fun onScreen(text: String): Boolean {
        val rect = shownAt(text) ?: return false
        return rect.top >= 0 && rect.bottom <= viewport.view.height
    }

    private fun seamsShown(): Int {
        var shown = 0
        instrumentation.runOnMainSync {
            shown = descendants(viewport.view).count { it is NovelChapterSeamView && it.isVisible }
        }
        return shown
    }

    /** The end marker's top edge relative to the viewport, or null when none is drawn. */
    private fun endMarkerTop(): Int? {
        var top: Int? = null
        instrumentation.runOnMainSync {
            val marker = descendants(viewport.view).firstOrNull {
                it is NovelChapterSeamView && it.isVisible && it.seam?.nextTitle == null
            } ?: return@runOnMainSync
            val origin = IntArray(2).also(viewport.view::getLocationOnScreen)
            val at = IntArray(2).also(marker::getLocationOnScreen)
            top = at[1] - origin[1]
        }
        return top
    }

    /** Chunk views left in holders waiting in the recycler's pool, which empties it. */
    private fun pooledTextViews(): Int {
        var count = 0
        instrumentation.runOnMainSync {
            val pool = (viewport.view as RecyclerView).recycledViewPool
            count = generateSequence { pool.getRecycledView(0) }.sumOf { textViews(it.itemView).size }
        }
        return count
    }

    /** The same conversion `NovelTextStyle.applyMargins` makes. */
    private fun dp(value: Int) = (value * instrumentation.targetContext.resources.displayMetrics.density).toInt()

    private fun sp(value: Int) =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value.toFloat(),
            instrumentation.targetContext.resources.displayMetrics,
        )

    private fun descendants(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.children?.flatMap { descendants(it) }?.toList() ?: emptyList())

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> view.children.flatMap { textViews(it) }.toList()
        else -> emptyList()
    }
}
