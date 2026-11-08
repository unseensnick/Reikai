package reikai.presentation.reader

import android.app.Activity
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A copy asked for once the window has lost its surface, which PixelCopy refuses by throwing. Robolectric's
 * window always has one, so the refusal is stubbed.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenCopyTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Before
    fun refuseCopies() {
        mockkStatic(PixelCopy::class)
        every {
            PixelCopy.request(any<Window>(), any(), any(), any(), any())
        } throws IllegalArgumentException("Window doesn't have a backing surface!")
    }

    @After
    fun tearDown() = unmockkStatic(PixelCopy::class)

    private fun <T : View> shown(view: T): T {
        val parent = FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(100, 100)) }
        activity.setContentView(parent)
        parent.layout(0, 0, 100, 100)
        view.layout(0, 0, 100, 100)
        return view
    }

    @Test
    fun `a spread hold with no surface to copy still runs its move`() {
        val host = shown(NovelSpreadHost(activity, View(activity), heightFactor = 1, rtl = false) { 0 })
        var ran = false

        host.hold { ran = true }

        ran shouldBe true
    }

    @Test
    fun `a page slide with no surface to copy still turns`() {
        val content = shown(View(activity))
        var ran = false

        PageSlide(content).turn(leftward = true) { ran = true }

        ran shouldBe true
    }
}
