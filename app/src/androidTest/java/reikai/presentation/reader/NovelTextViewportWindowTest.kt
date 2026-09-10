package reikai.presentation.reader

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.presentation.reader.text.NovelChapterSeamView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
                onProgressChanged = { _, _ -> },
                onProgressSettled = { _, _ -> },
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

    private fun open(id: Long, html: String) {
        runBlocking(Dispatchers.Main) {
            viewport.load(chapter(id, html), hasPrevious = true, hasNext = false, settings = readerTestSettings)
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

    /** Waits for [id]'s first fit report, then for the frames after it to settle. Not [required] for a
     *  chapter added off screen, which is never laid out and so never reports. */
    private fun awaitRendered(id: Long, required: Boolean = true) {
        val wait = if (required) TIMEOUT_S else PREPEND_WAIT_S
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(wait)
        while (!fits.containsKey(id) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("chapter $id never rendered", !required || fits.containsKey(id))
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
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

    private fun descendants(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.children?.flatMap { descendants(it) }?.toList() ?: emptyList())

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> view.children.flatMap { textViews(it) }.toList()
        else -> emptyList()
    }
}
