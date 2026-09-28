package reikai.presentation.reader.text

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import reikai.presentation.reader.WebViewHostActivity
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** How the native renderer draws a chapter picture it could not get, and the box's Retry. */
class NovelImageFailureTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val scope = MainScope()
    private var scenario: ActivityScenario<WebViewHostActivity>? = null
    private var server: PngServer? = null

    private val servers = mutableListOf<PngServer>()

    @After
    fun tearDown() {
        scope.cancel()
        server?.close()
        servers.forEach { it.close() }
        scenario?.close()
    }

    /** Re-measures run so far, begun and finished, and whether one was told every load had landed. */
    private val started = AtomicInteger()
    private val settled = AtomicInteger()

    @Volatile
    private var allLanded = false

    /**
     * A view holding two pictures, A over 0..1 and B over 2..3, under a getter whose re-measure does what
     * the renderer's does: copies the text on the main thread, suspends, then sets the copy.
     */
    private fun twoPictureGetter(urlA: String, urlB: String): Pair<NovelImageGetter, TextView> {
        lateinit var view: TextView
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java).apply {
            onActivity { activity ->
                view = TextView(activity).apply { movementMethod = LinkOnlyMovementMethod }
                activity.setContentView(view)
            }
        }
        val getter = NovelImageGetter(
            context = context,
            scope = scope,
            contentWidth = 600,
            sourceId = null,
            textSizePx = 18f,
            textColor = { 0xFF000000.toInt() },
            resolveView = { view },
            onImagesLanded = { _, swapIn, landed ->
                started.incrementAndGet()
                val copy = SpannableStringBuilder(view.text)
                delay(HOLD_MS)
                swapIn()
                view.text = copy
                if (landed) allLanded = true
                settled.incrementAndGet()
            },
        )
        val a = getter.getDrawable(urlA)
        val b = getter.getDrawable(urlB)
        instrumentation.runOnMainSync {
            view.text = SpannableStringBuilder("￼\n￼").apply {
                setSpan(ImageSpan(a), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ImageSpan(b), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return getter to view
    }

    private fun retryRanges(view: TextView): List<Pair<Int, Int>> {
        var ranges = emptyList<Pair<Int, Int>>()
        instrumentation.runOnMainSync {
            val text = view.text as Spanned
            ranges = text.getSpans(0, text.length, ClickableSpan::class.java)
                .map { text.getSpanStart(it) to text.getSpanEnd(it) }
        }
        return ranges
    }

    /** A picture that fails while another's re-measure holds a copy of the text still offers Retry. */
    @Test
    fun aPictureFailingDuringAReMeasureStillOffersRetry() {
        val a = PngServer(pngOf(10, 10)).also { servers += it }
        val b = PngServer(pngOf(10, 10), failFirst = Int.MAX_VALUE).also { servers += it }
        val (getter, view) = twoPictureGetter(a.url("a"), b.url("b", delayMs = 500))
        instrumentation.runOnMainSync { getter.startLoading() }
        awaitWhile { !allLanded }
        assertEquals(listOf(2 to 3), retryRanges(view))
    }

    /** A Retry that succeeds while another picture's re-measure holds a copy leaves no Retry behind. */
    @Test
    fun aSuccessfulRetryDuringAnotherPicturesReMeasureLeavesNoRetryBehind() {
        val held = PngServer(pngOf(10, 10), held = true).also { servers += it }
        val flaky = PngServer(pngOf(10, 10), failFirst = 1).also { servers += it }
        val (getter, view) = twoPictureGetter(held.url("a"), flaky.url("b"))
        instrumentation.runOnMainSync { getter.startLoading() }
        awaitWhile { !(retryRanges(view) == listOf(2 to 3) && settled.get() == 1) }
        held.release()
        awaitWhile { started.get() < 2 }
        instrumentation.runOnMainSync {
            val text = view.text as Spanned
            text.getSpans(0, text.length, ClickableSpan::class.java).single().onClick(view)
        }
        awaitWhile { settled.get() < 3 }
        assertEquals(emptyList<Pair<Int, Int>>(), retryRanges(view))
    }

    /** A loading picture's line mark lost with the text a re-measure replaced is marked again, so it redraws. */
    @Test
    fun aLoadingPictureWhoseMarkAReMeasureLostStillRedraws() {
        val held = PngServer(pngOf(10, 10), held = true).also { servers += it }
        val invalidations = AtomicInteger()
        lateinit var view: TextView
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java).apply {
            onActivity { activity ->
                view = object : TextView(activity) {
                    override fun invalidate() {
                        invalidations.incrementAndGet()
                        super.invalidate()
                    }
                }.apply { movementMethod = LinkOnlyMovementMethod }
                activity.setContentView(view)
            }
        }
        // Read once a window exists: the process only learns the device's animator scale from its first one.
        var animating = false
        instrumentation.runOnMainSync { animating = ValueAnimator.areAnimatorsEnabled() }
        assumeTrue("the pulse does not run with animations off", animating)
        val getter = NovelImageGetter(
            context = context,
            scope = scope,
            contentWidth = 600,
            sourceId = null,
            textSizePx = 18f,
            textColor = { 0xFF000000.toInt() },
            resolveView = { view },
            onImagesLanded = { _, _, _ -> },
        )
        val wrapper = getter.getDrawable(held.url("a"))
        lateinit var before: SpannableStringBuilder
        instrumentation.runOnMainSync {
            view.text = SpannableStringBuilder("￼").apply {
                setSpan(ImageSpan(wrapper), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            before = SpannableStringBuilder(view.text)
            getter.startLoading()
        }
        var marked = false
        awaitWhile {
            instrumentation.runOnMainSync {
                val text = view.text as Spanned
                marked = text.getSpans(0, text.length, CharacterStyle::class.java).any { it !is ImageSpan }
            }
            !marked
        }
        assertTrue("the pulse never marked the loading picture's line", marked)
        instrumentation.runOnMainSync { view.text = before }
        Thread.sleep(100)
        instrumentation.runOnMainSync { invalidations.set(0) }
        Thread.sleep(300)
        assertTrue("nothing redrew the loading picture's line", invalidations.get() > 0)
    }

    private fun getter() = NovelImageGetter(
        context = context,
        scope = scope,
        contentWidth = 600,
        sourceId = null,
        textSizePx = 18f,
        textColor = { 0xFF000000.toInt() },
        resolveView = { null },
        onImagesLanded = { _, _, _ -> },
    )

    /** Every other way a picture fails draws the box, so a data address with no payload does too. */
    @Test
    fun aDataImageWithNoCommaDrawsTheFailureBox() {
        val wrapper = getter().getDrawable("data:image/png;base64") as DrawableWrapper
        assertTrue("left as ${wrapper.innerDrawable}", wrapper.innerDrawable is ImageFailureDrawable)
    }

    @Test
    fun aDataImageThatDecodesToNothingDrawsTheFailureBox() {
        val wrapper = getter().getDrawable("data:image/png;base64,AAAA") as DrawableWrapper
        assertTrue("left as ${wrapper.innerDrawable}", wrapper.innerDrawable is ImageFailureDrawable)
    }

    /** The render and a restyle hold a picture off the line spacing by the one rule. */
    @Test
    fun aPictureTakesBackItsViewsLineSpacing() {
        val span = ChapterImageSpan(ColorDrawable(), "x", topPx = 0, bottomPx = 0)
        val text = SpannableStringBuilder("￼").apply { setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

        NovelTextStyle.holdImagesOffLineSpacing(text, 12.6f)

        assertEquals(13, span.lineExtraPx)
    }

    /**
     * A drawable inside a span has no callback, so setting the box dimmed redraws nothing on its own. The
     * retry is slow to answer, so the tap is measured before any result could redraw the view.
     */
    @Test
    fun tappingRetryRedrawsTheBoxDimmed() {
        val failing = PngServer(pngOf(10, 10), failFirst = 1).also { server = it }
        val invalidations = AtomicInteger()
        lateinit var block: ChapterTextBlock
        lateinit var renderer: NovelTextRenderer
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java).apply {
            onActivity { activity ->
                block = ChapterTextBlock(activity) {
                    object : TextView(activity) {
                        override fun invalidate() {
                            invalidations.incrementAndGet()
                            super.invalidate()
                        }
                    }.apply {
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
        runBlocking(Dispatchers.Main) {
            renderer.render(
                block = block,
                html = "<img src=\"${failing.url("retry", delayMs = RETRY_DELAY_MS)}\"><p>lorem ipsum</p>",
                fontSize = 18,
                paragraphSpacing = 0f,
                paragraphIndent = 0f,
                selectable = false,
                bionic = false,
                contentWidth = 600,
                baseUrl = null,
                sourceId = null,
                holdAcross = { it() },
                onTextSet = {},
            ).join()
        }
        awaitWhile { block.imagesLoading }

        var box: ImageFailureDrawable? = null
        var redrawn = false
        instrumentation.runOnMainSync {
            val view = block.chunkViews.first()
            val text = view.text as Spanned
            val retry = text.getSpans(0, text.length, ClickableSpan::class.java).single()
            box = failureBoxOf(view)
            val before = invalidations.get()
            retry.onClick(view)
            redrawn = invalidations.get() > before
        }

        assertTrue("the box did not dim", box?.retrying == true)
        assertTrue("nothing redrew the dimmed box", redrawn)
    }

    private fun failureBoxOf(view: TextView): ImageFailureDrawable? {
        val text = view.text as Spanned
        return text.getSpans(0, text.length, ImageSpan::class.java)
            .firstNotNullOfOrNull { (it.drawable as? DrawableWrapper)?.innerDrawable as? ImageFailureDrawable }
    }

    private fun awaitWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private companion object {
        /** Long enough that the retry cannot answer inside the tap being measured. */
        const val RETRY_DELAY_MS = 3_000L

        /** How long the stand-in re-measure holds its copy, well past a loopback fetch. */
        const val HOLD_MS = 1_500L
    }
}
