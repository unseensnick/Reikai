package reikai.presentation.reader.text

import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.presentation.reader.WebViewHostActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * That an image landing in the native renderer is drawn at its own height, with text selection on and
 * off. The layout the picture lands in was measured against the 200dp placeholder, and neither layout
 * re-reads a drawable's bounds on its own: a `PrecomputedText` caches the measurement it was built
 * from, and the `DynamicLayout` a selectable view uses reflows only on a text or span edit, while
 * `TextView.onMeasure` keeps an existing layout while the width is unchanged. One rule, so one case
 * over both modes rather than a pair that can drift.
 */
@RunWith(Parameterized::class)
class NovelImageLayoutTest(private val selectable: Boolean) {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var block: ChapterTextBlock
    private lateinit var renderer: NovelTextRenderer
    private lateinit var server: PngServer
    private val scope = MainScope()

    @Before
    fun setUp() {
        server = PngServer(tallPng())
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            block = ChapterTextBlock(activity) {
                TextView(activity).apply {
                    setTextIsSelectable(selectable)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
            }
            renderer = NovelTextRenderer(activity, scope) { _, _ -> }
            activity.setContentView(block.container)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (::server.isInitialized) server.close()
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun aLoadedImageIsDrawnAtItsOwnHeight() {
        runBlocking(Dispatchers.Main) {
            renderer.render(
                block = block,
                html = "<img src=\"${server.url}\">" + "<p>lorem ipsum</p>".repeat(20),
                fontSize = 18,
                paragraphSpacing = 0f,
                paragraphIndent = 0f,
                selectable = selectable,
                bionic = false,
                contentWidth = COLUMN_PX,
                baseUrl = null,
                holdAcross = { it() },
                onTextSet = {},
            ).join()
        }
        awaitWhile { block.imagesLoading }

        val picture = measure().first
        // Without this the case would pass on an image that never arrived: the placeholder's line is
        // as tall as the placeholder, which is what the layout was measured against in the first place.
        assertTrue("the image is still ${picture}px, the placeholder's own height", picture > placeholderPx() + 1)

        // The rebuild that follows the load runs off the main thread on the precomputed branch.
        awaitWhile { measure().second < picture }
        val line = measure().second
        // The picture with the page's 1em margin above and below it (ChapterImageSpan), which is never the
        // placeholder's height, the defect this pins.
        val em = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            instrumentation.targetContext.resources.displayMetrics,
        ).toInt()
        assertTrue(
            "the line holding a ${picture}px picture is ${line}px tall",
            abs(line - (picture + 2 * em)) <= 1,
        )
    }

    /**
     * The viewport lands a saved position once the block says its images are in, so that has to mean
     * every picture's line is at its own height. The second picture shares its chunk with a paragraph
     * long enough that re-measuring it takes frames, while the first chunk's lays out long before.
     */
    @Test
    fun imagesStopLoadingOnlyOnceEveryPictureIsMeasured() {
        val laidOutShort = CopyOnWriteArrayList<Int>()
        // Watched from the text being set, since the empty block before it has no loading to be done with.
        val watch = {
            block.container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!block.imagesLoading) laidOutShort += shortPictureLines()
            }
        }
        runBlocking(Dispatchers.Main) {
            renderer.render(
                block = block,
                html = "<img src=\"${server.url("first")}\">" + "<p>lorem ipsum dolor sit amet</p>".repeat(300) +
                    "<img src=\"${server.url("second")}\"><p>" + "lorem ipsum dolor sit amet ".repeat(12_000) + "</p>",
                fontSize = 18,
                paragraphSpacing = 0f,
                paragraphIndent = 0f,
                selectable = selectable,
                bionic = false,
                contentWidth = COLUMN_PX,
                baseUrl = null,
                holdAcross = { it() },
                onTextSet = { watch() },
            ).join()
        }
        // Until a layout has seen the images in, so a case with no such layout cannot pass on nothing.
        awaitWhile { laidOutShort.isEmpty() }
        assertTrue("the pictures sit in ${block.chunkViews.size} chunks", block.chunkViews.size >= 2)
        assertTrue(
            "laid out with pictures still short: $laidOutShort",
            laidOutShort.isNotEmpty() && laidOutShort.all { it == 0 },
        )
    }

    /**
     * The page centres every picture (reader.css). A picture alone in a paragraph is the usual shape, and
     * text before it is what the blank-line collapse must not move the centring past (NovelBlankLineTest).
     */
    @Test
    fun aPictureAloneInAParagraphIsCentred() {
        // Narrower than the column at any density, since a picture is drawn at its own dp width.
        server.close()
        server = PngServer(pngOf(100, 60))
        runBlocking(Dispatchers.Main) {
            renderer.render(
                block = block,
                html =
                "<p>before</p><p><img src=\"${server.url("narrow")}\"></p><p>after</p>" +
                    "<p>lorem ipsum</p>".repeat(20),
                fontSize = 18,
                paragraphSpacing = 0f,
                paragraphIndent = 0f,
                selectable = selectable,
                bionic = false,
                contentWidth = COLUMN_PX,
                baseUrl = null,
                holdAcross = { it() },
                onTextSet = {},
            ).join()
        }
        awaitWhile { block.imagesLoading }

        var left = 0f
        var width = 0
        var column = 0
        instrumentation.runOnMainSync {
            val view = block.chunkViews.first { view ->
                (view.text as? Spanned)?.let { it.getSpans(0, it.length, ImageSpan::class.java).isNotEmpty() } == true
            }
            val text = view.text as Spanned
            val span = text.getSpans(0, text.length, ImageSpan::class.java).first()
            left = view.layout.getPrimaryHorizontal(text.getSpanStart(span))
            width = span.drawable.bounds.width()
            column = view.layout.width
        }
        assertTrue("the picture is ${width}px, as wide as the ${column}px column", width < column - 2)
        assertTrue(
            "a ${width}px picture starts at ${left}px in a ${column}px column",
            abs(left - (column - width) / 2f) <= 1,
        )
    }

    /** How many image spans sit on a line shorter than their picture. */
    private fun shortPictureLines(): Int = block.chunkViews.sumOf { view ->
        val text = view.text as? Spanned ?: return@sumOf 0
        val layout = view.layout ?: return@sumOf 0
        text.getSpans(0, text.length, ImageSpan::class.java).count { span ->
            val line = layout.getLineForOffset(text.getSpanStart(span))
            layout.getLineBottom(line) - layout.getLineTop(line) < span.drawable.bounds.height()
        }
    }

    /** The image span's drawable height and the height of the line holding it, both in pixels. */
    private fun measure(): Pair<Int, Int> {
        var picture = 0
        var line = 0
        instrumentation.runOnMainSync {
            block.chunkViews.forEach { view ->
                val text = view.text as? Spanned ?: return@forEach
                val span = text.getSpans(0, text.length, ImageSpan::class.java).firstOrNull() ?: return@forEach
                picture = span.drawable.bounds.height()
                val layout = view.layout ?: return@forEach
                val index = layout.getLineForOffset(text.getSpanStart(span))
                line = layout.getLineBottom(index) - layout.getLineTop(index)
            }
        }
        return picture to line
    }

    private fun placeholderPx(): Int =
        (PLACEHOLDER_DP * instrumentation.targetContext.resources.displayMetrics.density).toInt()

    private fun awaitWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun tallPng(): ByteArray = pngOf(400, 2000)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "selectable={0}")
        fun modes(): List<Boolean> = listOf(false, true)

        /** Narrower than any test device, so the fitted picture is taller than the placeholder. */
        const val COLUMN_PX = 600

        /** NovelImageGetter's own placeholder height. */
        const val PLACEHOLDER_DP = 200

        const val TIMEOUT_S = 15L
    }
}
