package reikai.presentation.reader

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.domain.reader.ChapterProgress
import reikai.presentation.reader.text.NovelChapterSeamView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The rules both novel renderers answer through [TextViewport], run once against each real viewport
 * rather than restated per renderer: what the marker between two chapters says, when a chapter's end
 * counts as seen, and which drags step to another chapter. Each renderer implements these on its own
 * (Kotlin and `reader.js`), so this is what keeps one from drifting while the other stays right.
 */
@RunWith(Parameterized::class)
class TextViewportContractTest(private val renderer: Renderer) {

    enum class Renderer { NATIVE, WEB }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var viewport: TextViewport
    private val view: View get() = (viewport as ReaderViewport).view

    /** The last fit answer per chapter, which is also each renderer's sign that a chapter has rendered. */
    private val fits = ConcurrentHashMap<Long, Boolean>()
    private val endsSeen = CopyOnWriteArrayList<Long>()
    private val steps = CopyOnWriteArrayList<Boolean>()

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            viewport = when (renderer) {
                Renderer.NATIVE -> NovelTextViewport(
                    context = activity,
                    textSelectable = false,
                    volumeKeysActive = { false },
                    volumeKeysInverted = false,
                    volumeKeyScrollFraction = 0.75f,
                    onProgressChanged = { _, _ -> },
                    onProgressSettled = { _, _ -> },
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
                    textSelectable = false,
                    volumeKeysActive = { false },
                    volumeKeysInverted = false,
                    volumeKeyScrollFraction = 0.75f,
                    useOriginalFonts = false,
                    sourceCssPriority = false,
                    onProgressChanged = { _, _ -> },
                    onProgressSettled = { _, _ -> },
                    onToggleMenu = {},
                    onStepChapter = { steps += it },
                    onVisibleChapter = {},
                    onRetryBoundary = {},
                    statusBarHeightPx = { 0 },
                    onChapterFits = { id, fit -> fits[id] = fit },
                    onChapterEndSeen = { endsSeen += it },
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
                    .map {
                        DrawnSeam(
                            it.finishedTitle,
                            it.nextTitle,
                            it.finishedDownloaded,
                            it.nextDownloaded,
                            it.missingChapters > 0,
                        )
                    }
            }
            drawn
        }
        Renderer.WEB -> {
            val array = JSONArray(
                eval(
                    "[...document.querySelectorAll('.rk-seam')].map(function (s) {" +
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

    private fun chapter(id: Long, html: String, number: Double = id.toDouble(), downloaded: Boolean = false) =
        NovelReaderViewModel.LoadedChapter(
            chapterId = id,
            title = "Chapter $number",
            url = "/chapter/$id",
            html = html,
            baseUrl = null,
            progressPercent = 0,
            chapterNumber = number,
            downloaded = downloaded,
        )

    private fun long(marker: String) =
        (1..120).joinToString("") { "<p>$marker $it. " + "lorem ipsum dolor sit amet ".repeat(8) + "</p>" }

    /** Opens [chapter] and waits for its first fit report, each renderer's sign that it has rendered. */
    private fun open(chapter: NovelReaderViewModel.LoadedChapter) {
        runBlocking(Dispatchers.Main) { viewport.load(chapter, readerTestSettings) }
        awaitWhile { !fits.containsKey(chapter.chapterId) }
        settle()
    }

    private fun prepend(chapter: NovelReaderViewModel.LoadedChapter) {
        runBlocking(Dispatchers.Main) { viewport.window.prepend(chapter, readerTestSettings) }
        settle()
    }

    /** A one-finger drag through the view, as a finger delivers it to either renderer. */
    private fun drag(fromX: Float, toX: Float, fromY: Float = view.height / 2f, toY: Float = fromY) {
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
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
            touch(MotionEvent.ACTION_UP, toX, toY, down, down + (DRAG_STEPS + 1) * DRAG_STEP_MS)
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

        /** Past the 180dp both renderers need, and short of it. */
        const val SWIPE_DP = 220
        const val SHORT_SWIPE_DP = 60

        /** How far in from an edge a drag starts, clear of any system gesture strip. */
        const val EDGE_PX = 40f
        const val DRAG_STEPS = 8
        const val DRAG_STEP_MS = 10L
    }
}
