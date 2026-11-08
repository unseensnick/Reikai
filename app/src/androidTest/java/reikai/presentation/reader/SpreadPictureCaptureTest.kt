package reikai.presentation.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Base64
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Not a check: screenshots of pictures in two-page reading, for looking at by eye. A picture taller than a page
 * across two spreads (and on single pages to compare), one about a page tall, a small one inline with text and a
 * wide one. Runs only when the `pictureCapture` argument names the device, and saves
 * `<device>_<renderer>_<case>.png` in the app's files.
 */
@RunWith(Parameterized::class)
class SpreadPictureCaptureTest(private val renderer: PagedReadingTest.Renderer) {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var viewport: TextViewport
    private val view: View get() = viewport.view
    private val fits = Collections.synchronizedMap(mutableMapOf<Long, Boolean>())
    private val turned = AtomicInteger()

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
        onPageTurned = { turned.incrementAndGet() },
        onPageShown = { _, _ -> },
    )

    @Test
    fun captureSpreadPictures() {
        val device = InstrumentationRegistry.getArguments().getString("pictureCapture")
        assumeTrue(device != null)
        val name = "${device}_${renderer.name.lowercase()}"

        open(spread = true, html = intro() + picture("TALL", 600, 2_400) + paragraphs("after", 6))
        shoot("${name}_a1_tall_starts")
        turn()
        shoot("${name}_a2_tall_continues")

        open(spread = true, html = intro() + picture("PAGE", 600, pageTallSourceHeight()) + paragraphs("after", 6))
        shoot("${name}_b1_page_tall")
        turn()
        shoot("${name}_b2_page_tall_next")

        val small = "<img src=\"${dataUri(drawn("SMALL", 160, 110))}\" style=\"vertical-align:middle\">"
        open(
            spread = true,
            html = intro() + "<p>Inline $small a small picture sits in this line of text, " +
                "and the text runs on after it. " + "The quick brown fox jumps over the lazy dog. ".repeat(6) + "</p>" +
                paragraphs("after", 6),
        )
        shoot("${name}_c_small_inline")

        open(spread = true, html = intro() + picture("WIDE", 1_600, 600) + paragraphs("after", 6))
        shoot("${name}_d_wide")

        open(spread = false, html = intro() + picture("TALL", 600, 2_400) + paragraphs("after", 6))
        shoot("${name}_single_a1_tall_starts")
        turn()
        shoot("${name}_single_a2_tall_continues")
        turn()
        shoot("${name}_single_a3_tall_ends")
    }

    @After
    fun tearDown() {
        if (::viewport.isInitialized) instrumentation.runOnMainSync { (viewport as ReaderViewport).destroy() }
        if (::scenario.isInitialized) scenario.close()
    }

    /** Close to the page's own shape, so the picture is about one page tall at the column's width. */
    private fun pageTallSourceHeight(): Int {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val margins = readerTestSettings.margins
        val pageHeight = view.height - (margins.top + margins.bottom) * density
        val columnWidth = view.width / 2f - (margins.left + NovelSpreadHost.INNER_MARGIN_DP) * density
        return (600 * pageHeight / columnWidth * 0.95f).toInt()
    }

    private fun open(spread: Boolean, html: String) {
        if (::viewport.isInitialized) tearDown()
        fits.clear()
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
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
                    spread = spread,
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
                    spread = spread,
                )
            }
            activity.setContentView(view)
        }
        val chapter = NovelReaderViewModel.LoadedChapter(
            chapterId = 1L,
            title = "Chapter 1",
            url = "/chapter/1",
            html = html,
            baseUrl = null,
            progress = reikai.domain.reader.ChapterProgress.Percent(0),
            chapterNumber = 1.0,
            novelId = 1L,
            sourceId = null,
            downloaded = false,
            isLast = false,
            topLine = null,
        )
        onMain { viewport.load(chapter, readerTestSettings) }
        awaitWhile { !fits.containsKey(1L) }
        settle(8)
    }

    private fun turn() {
        val turns = turned.get()
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
                val event = MotionEvent.obtain(down, down + i * 50L, action, view.width * 0.1f, view.height * 0.8f, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        awaitWhile { turned.get() == turns }
        settle(6)
    }

    private fun shoot(file: String) {
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "$file.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private fun intro() = "<h1>Chapter 1</h1>" + paragraphs("before", 2)

    private fun paragraphs(prefix: String, count: Int) = (1..count).joinToString("") {
        "<p>$prefix paragraph $it. " + "The quick brown fox jumps over the lazy dog. ".repeat(6) + "</p>"
    }

    private fun picture(label: String, width: Int, height: Int) =
        "<p><img src=\"${dataUri(drawn(label, width, height))}\"></p>"

    private fun dataUri(png: ByteArray) = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)

    /** A coloured picture with a white line and its label every 100 source pixels and a yellow border, so where a
     *  page cuts it can be read off the screen. */
    private fun drawn(label: String, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(40, 90, 160))
        val line = Paint().apply {
            color = Color.WHITE
            strokeWidth = 3f
        }
        val text = Paint().apply {
            color = Color.WHITE
            textSize = 26f
            isAntiAlias = true
        }
        (0 until height step 100).forEach { y ->
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), line)
            canvas.drawText("$label y=$y", 12f, y + 32f, text)
        }
        val border = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRect(4f, 4f, width - 4f, height - 4f, border)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { bitmap.recycle() }
    }

    private fun awaitWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_S * 1000
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
    }

    private fun settle(times: Int) = repeat(times) {
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<PagedReadingTest.Renderer> = PagedReadingTest.Renderer.entries

        const val TIMEOUT_S = 15L
    }
}
