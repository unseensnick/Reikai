package reikai.presentation.reader

import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The long strip measured its height once, so a zoom after the window grew pinned it to the old
 * height and left a gap below it (mihonapp/mihon#1721). Hosted offscreen like
 * [RecyclerPrependPositionTest]; built on the main thread because the strip's gesture detector needs
 * a Looper.
 */
@RunWith(AndroidJUnit4::class)
class WebtoonResizeAfterZoomTest {

    @Test
    fun zoomAfterTheWindowGrowsFillsTheNewHeight() {
        val measured = onStrip { frame, strip ->
            frame.measureAt(HEIGHT_BEFORE)
            frame.measureAt(HEIGHT_AFTER)
            strip.onScale(1f)
            frame.measureAt(HEIGHT_AFTER)
            strip.measuredHeight
        }
        assertEquals(HEIGHT_AFTER, measured)
    }

    @Test
    fun theWindowGrowingAfterAZoomFillsTheNewHeight() {
        val measured = onStrip { frame, strip ->
            frame.measureAt(HEIGHT_BEFORE)
            strip.onScale(1f)
            frame.measureAt(HEIGHT_AFTER)
            strip.measuredHeight
        }
        assertEquals(HEIGHT_AFTER, measured)
    }

    /** Zoomed out, the strip is taller than the window, and that height must not become the window's. */
    @Test
    fun aZoomedOutStripKeepsTheWindowHeight() {
        val original = onStrip { frame, strip ->
            frame.measureAt(HEIGHT_BEFORE)
            strip.onScale(0.5f)
            frame.measureAt(HEIGHT_BEFORE)
            strip.originalHeight
        }
        assertEquals(HEIGHT_BEFORE, original)
    }

    private fun onStrip(body: (FrameLayout, WebtoonRecyclerView) -> Int): Int {
        var result = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val frame = FrameLayout(context)
            val strip = WebtoonRecyclerView(context)
            frame.addView(strip, ViewGroup.LayoutParams(MATCH, MATCH))
            result = body(frame, strip)
        }
        return result
    }

    private fun FrameLayout.measureAt(height: Int) {
        measure(
            MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        layout(0, 0, WIDTH, height)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WIDTH = 1080
        const val HEIGHT_BEFORE = 1500
        const val HEIGHT_AFTER = 2400
    }
}
