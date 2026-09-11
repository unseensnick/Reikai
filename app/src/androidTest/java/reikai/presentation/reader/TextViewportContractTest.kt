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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.domain.reader.ChapterProgress
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.reader.text.NovelChapterSeamView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    ) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = "Chapter $number",
        url = "/chapter/$id",
        html = html,
        baseUrl = null,
        progressPercent = 0,
        chapterNumber = number,
        downloaded = downloaded,
        isLast = isLast,
    )

    private fun long(marker: String) =
        (1..120).joinToString("") { "<p>$marker $it. " + "lorem ipsum dolor sit amet ".repeat(8) + "</p>" }

    /** Opens [chapter] and waits for its first fit report, each renderer's sign that it has rendered. */
    private fun open(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings = readerTestSettings) {
        runBlocking(Dispatchers.Main) { viewport.load(chapter, settings) }
        awaitWhile { !fits.containsKey(chapter.chapterId) }
        settle()
    }

    private fun prepend(chapter: NovelReaderViewModel.LoadedChapter) {
        runBlocking(Dispatchers.Main) { viewport.window.prepend(chapter, readerTestSettings) }
        settle()
    }

    private fun append(chapter: NovelReaderViewModel.LoadedChapter) {
        runBlocking(Dispatchers.Main) { viewport.window.append(chapter, readerTestSettings) }
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
    }
}
