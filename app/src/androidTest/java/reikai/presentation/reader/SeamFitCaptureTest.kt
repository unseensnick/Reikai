package reikai.presentation.reader

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import mihon.app.di.appGraph
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.domain.novel.NovelPageLayout
import reikai.domain.reader.ChapterProgress
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.util.Collections

/**
 * Not a check: a screenshot of the tallest seam (two long titles and a missing-chapters warning) on a paged
 * page in landscape, for comparing a change by eye. Runs only when the `seamCapture` argument names the shot,
 * and saves `seamfit_<renderer>_<shot>.png` in the app's files.
 */
@RunWith(Parameterized::class)
class SeamFitCaptureTest(private val renderer: PagedReadingTest.Renderer) {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var viewport: TextViewport
    private val view: View get() = viewport.view
    private val fits = Collections.synchronizedMap(mutableMapOf<Long, Boolean>())
    private val shown = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

    private val callbacks = NovelViewportCallbacks(
        volumeKeysActive = { false },
        onProgressChanged = { _, _ -> },
        onProgressSettled = { _, _ -> },
        onTopLine = { _, _ -> },
        onToggleMenu = {},
        onStepChapter = {},
        onVisibleChapter = {},
        onRetryBoundary = {},
        cutoutTopDp = { 0 },
        onChapterFits = { id, fit -> fits[id] = fit },
        onChapterEndSeen = {},
        onReaderScrolled = {},
        onReaderTurned = {},
        onPageTurned = {},
        onPageShown = { page, count -> shown += page to count },
    )

    @Test
    fun captureTheTallestSeamInLandscape() {
        val shot = InstrumentationRegistry.getArguments().getString("seamCapture")
        assumeTrue(shot != null)
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        // A large screen may ignore the request, which leaves it as it stands.
        awaitWhile(5_000) { !isLandscape() }
        settle(1_000)
        scenario.onActivity { activity ->
            viewport = when (renderer) {
                PagedReadingTest.Renderer.NATIVE -> NovelTextViewport(
                    context = activity,
                    fontManager = activity.appGraph.novelFontManager,
                    textSelectable = false,
                    pageLayout = NovelPageLayout.PAGED_LTR,
                    callbacks = callbacks,
                    autoScrollSpeed = InMemoryPreferenceStore().getFloat("speed", 1f),
                    autoScrollInterval = InMemoryPreferenceStore().getInt("interval", 5),
                )
                PagedReadingTest.Renderer.WEB -> NovelWebViewport(
                    context = activity,
                    fontManager = activity.appGraph.novelFontManager,
                    imageRequests = activity.appGraph.novelImageRequests,
                    textSelectable = false,
                    pageLayout = NovelPageLayout.PAGED_LTR,
                    callbacks = callbacks,
                    useOriginalFonts = false,
                    sourceCssPriority = false,
                    autoScrollSpeed = InMemoryPreferenceStore().getFloat("speed", 1f),
                    autoScrollInterval = InMemoryPreferenceStore().getInt("interval", 5),
                )
            }
            activity.setContentView(view)
        }
        onMain { viewport.load(chapter(1L, ChapterProgress.Percent.END), readerTestSettings) }
        awaitWhile { !fits.containsKey(1L) }
        settle(1_000)
        onMain { viewport.window.append(chapter(GAP_ID, ChapterProgress.Percent(0))) }
        awaitWhile { !fits.containsKey(GAP_ID) }
        settle(1_000)
        val before = shown.size
        tap(view.width * 0.1f, view.height * 0.8f)
        awaitWhile { shown.size == before }
        settle(1_500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(instrumentation.targetContext.filesDir, "seamfit_${renderer.name.lowercase()}_$shot.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After
    fun tearDown() {
        if (::viewport.isInitialized) instrumentation.runOnMainSync { (viewport as ReaderViewport).destroy() }
        if (::scenario.isInitialized) {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            scenario.close()
        }
    }

    private fun isLandscape(): Boolean {
        var landscape = false
        scenario.onActivity {
            landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        return landscape
    }

    private fun chapter(id: Long, progress: ChapterProgress.Percent) = NovelReaderViewModel.LoadedChapter(
        chapterId = id,
        title = TITLE,
        url = "/chapter/$id",
        html = (1..12).joinToString("") {
            "<p>Chapter $id, paragraph $it. " + "lorem ipsum dolor sit amet ".repeat(8) +
                "</p>"
        },
        baseUrl = null,
        progress = progress,
        chapterNumber = id.toDouble(),
        novelId = 1L,
        sourceId = null,
        downloaded = true,
        isLast = false,
    )

    private fun tap(x: Float, y: Float) {
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50L).forEach { (action, time) ->
                val event = MotionEvent.obtain(down, time, action, x, y, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }

    private fun awaitWhile(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
    }

    private fun settle(ms: Long) {
        Thread.sleep(ms)
        instrumentation.waitForIdleSync()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<PagedReadingTest.Renderer> = PagedReadingTest.Renderer.entries

        const val GAP_ID = 5L

        const val TITLE = "Volume 3 Chapter 128: The night the lanterns of the old capital went dark and " +
            "nobody in the eastern ward could remember why (part two of three)"
    }
}
