package reikai.presentation.reader.text

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @After
    fun tearDown() {
        scope.cancel()
        server?.close()
        scenario?.close()
    }

    private fun getter() = NovelImageGetter(
        context = context,
        scope = scope,
        contentWidthPx = 600,
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
    }
}
