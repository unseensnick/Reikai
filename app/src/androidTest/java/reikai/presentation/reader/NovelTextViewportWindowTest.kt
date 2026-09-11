package reikai.presentation.reader

import android.graphics.Color
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.domain.reader.ChapterProgress
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.reader.text.NovelChapterSeamView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Where the native renderer leaves the reader when the window grows around the chapter just opened.
 * Driven through the real viewport rather than a stand-in recycler, because the defect this pins came
 * from the order the viewport inserts and renders in, which a simulation decides for itself.
 */
@RunWith(AndroidJUnit4::class)
class NovelTextViewportWindowTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var viewport: NovelTextViewport

    /** The last fit answer per chapter, which is also the sign that a chapter has rendered. */
    private val fits = ConcurrentHashMap<Long, Boolean>()

    /** The last percent reported per chapter. */
    private val progress = ConcurrentHashMap<Long, Int>()

    /** What the host would read as the cutout inset, in dp. */
    @Volatile
    private var cutout = 0

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

        const val CUTOUT_DP = 24
    }

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            viewport = NovelTextViewport(
                context = activity,
                textSelectable = false,
                volumeKeysActive = { false },
                volumeKeysInverted = false,
                volumeKeyScrollFraction = 0.75f,
                onProgressChanged = { id, percent -> progress[id] = percent },
                onProgressSettled = { _, _ -> },
                onToggleMenu = {},
                onStepChapter = {},
                onVisibleChapter = {},
                onRetryBoundary = {},
                cutoutTopDp = { cutout },
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
        open(SHORT, "<p>short</p>")
        prepend(PREVIOUS, long("previous"))
        assertTrue("the short chapter is not on screen: ${shownAt("short")}", onScreen("short"))
    }

    /** The same short chapter with one after it: the host adds that one first, so there is room to
     *  keep the short chapter's first line at the top when the one before it arrives. */
    @Test
    fun aShortChapterOpenedBetweenTwoKeepsItsFirstLineAtTheTop() {
        open(SHORT, "<p>short</p>")
        val before = shownAt("short")
        append(NEXT, long("next"))
        prepend(PREVIOUS, long("previous"))
        assertEquals(before, shownAt("short"))
    }

    /** The marker naming both chapters appears on the lower one once the upper one has joined. */
    @Test
    fun aChapterGainingOneAboveItShowsTheSeamBetweenThem() {
        open(LONG, long("current"))
        val alone = seamsShown()
        prepend(PREVIOUS, long("previous"))
        assertEquals(0 to 1, alone to seamsShown())
    }

    /** Finished first, next second: swapped, every boundary would name the chapter just left twice
     *  over, and a count of seams cannot tell. The WebView page pins the same order. */
    @Test
    fun theSeamNamesTheChapterThatFinishedAboveTheOneBelow() {
        open(LONG, long("current"))
        prepend(PREVIOUS, long("previous"))
        assertEquals(listOf("Chapter $PREVIOUS" to "Chapter $LONG"), seamTitles())
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
    fun twoQuickTextSizeStepsKeepTheReaderWhereTheyWere() {
        open(LONG, long("current"))
        append(NEXT, long("next"))
        prepend(PREVIOUS, long("previous"))
        instrumentation.runOnMainSync { viewport.seekTo(ChapterProgress.Percent(6_000)) }
        settle()
        val before = progress.getValue(LONG)
        dragTextSize()
        val after = progress.getValue(LONG)
        assertTrue("the reader moved from $before% to $after%", abs(after - before) <= 1)
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
                viewport.append(chapter(NEXT, long("next")), readerTestSettings)
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

    /** An open during an Activity recreation runs before the window has insets and reads zero, so the
     *  inset has to land when the insets do. */
    @Test
    fun aCutoutInsetThatArrivesAfterTheOpenReachesTheColumn() {
        open(LONG, long("current"))
        cutout = CUTOUT_DP
        instrumentation.runOnMainSync {
            ViewCompat.dispatchApplyWindowInsets(viewport.view, WindowInsetsCompat.Builder().build())
        }
        settle()
        assertEquals(dp(readerTestSettings.margins.top) + dp(CUTOUT_DP), columnTopPadding("current"))
    }

    /** A chapter that fits on screen has no room to seek within, so the rail lands on its start, as
     *  the WebView page's seek does; it used to leave the reader where they were. */
    @Test
    fun aSeekInsideAChapterThatFitsLandsOnItsFirstLine() {
        open(SHORT, "<p>short</p>")
        append(NEXT, long("next"))
        // Into the column's top margin only, so the short chapter is still the one on screen.
        instrumentation.runOnMainSync { viewport.view.scrollBy(0, dp(readerTestSettings.margins.top) / 2) }
        settle()
        instrumentation.runOnMainSync { viewport.seekTo(ChapterProgress.Percent(5_000)) }
        settle()
        assertEquals(dp(readerTestSettings.margins.top), shownAt("short")?.top)
    }

    private fun long(marker: String) =
        (1..120).joinToString("") { "<p>$marker $it. " + "lorem ipsum dolor sit amet ".repeat(8) + "</p>" }

    private fun chapter(id: Long, html: String) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = "Chapter $id",
        url = "/chapter/$id",
        html = html,
        baseUrl = null,
        progressPercent = 0,
    )

    private fun open(id: Long, html: String, settings: NovelReaderSettings = readerTestSettings) {
        runBlocking(Dispatchers.Main) {
            viewport.load(chapter(id, html), settings)
        }
        awaitRendered(id)
    }

    private fun append(id: Long, html: String) {
        runBlocking(Dispatchers.Main) { viewport.append(chapter(id, html), readerTestSettings) }
        awaitRendered(id, required = false)
    }

    private fun prepend(id: Long, html: String) {
        runBlocking(Dispatchers.Main) { viewport.prepend(chapter(id, html), readerTestSettings) }
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
        instrumentation.runOnMainSync {
            val line = textViews(viewport.view).firstOrNull { it.text.contains(text) } ?: return@runOnMainSync
            val layout = line.layout ?: return@runOnMainSync
            val offset = line.text.indexOf(text)
            val lineIndex = layout.getLineForOffset(offset)
            val origin = IntArray(2).also(viewport.view::getLocationOnScreen)
            val at = IntArray(2).also(line::getLocationOnScreen)
            val top = at[1] - origin[1] + line.paddingTop + layout.getLineTop(lineIndex)
            val bottom = at[1] - origin[1] + line.paddingTop + layout.getLineBottom(lineIndex)
            rect = Rect(0, top, viewport.view.width, bottom)
        }
        return rect
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

    private fun seamTitles(): List<Pair<String, String>?> {
        var titles = emptyList<Pair<String, String>?>()
        instrumentation.runOnMainSync {
            titles =
                descendants(viewport.view).filterIsInstance<NovelChapterSeamView>().filter {
                    it.isVisible
                }.map { it.titles }
        }
        return titles
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

    /** The top padding of the column holding the chapter whose text contains [text]. */
    private fun columnTopPadding(text: String): Int? {
        var padding: Int? = null
        instrumentation.runOnMainSync {
            padding = (textViews(viewport.view).firstOrNull { it.text.contains(text) }?.parent as? View)?.paddingTop
        }
        return padding
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
