package reikai.presentation.reader

import android.view.InputDevice
import android.view.MotionEvent
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric, because a real MotionEvent carries the axis and source this reads. */
@RunWith(RobolectricTestRunner::class)
class MouseWheelTest {

    private fun event(scroll: Float, action: Int = MotionEvent.ACTION_SCROLL, source: Int = InputDevice.SOURCE_MOUSE) =
        MotionEvent.obtain(
            0L,
            0L,
            action,
            1,
            arrayOf(MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_VSCROLL, scroll) }),
            0,
            0,
            1f,
            1f,
            0,
            0,
            source,
            0,
        )

    @Test
    fun `scrolling down turns forward`() {
        wheelForward(event(-1f)) shouldBe true
    }

    @Test
    fun `scrolling up turns back`() {
        wheelForward(event(1f)) shouldBe false
    }

    @Test
    fun `a sideways scroll turns nothing`() {
        wheelForward(event(0f)) shouldBe null
    }

    @Test
    fun `any other motion turns nothing`() {
        wheelForward(event(-1f, action = MotionEvent.ACTION_HOVER_MOVE)) shouldBe null
    }

    @Test
    fun `a joystick's scroll turns nothing`() {
        wheelForward(event(-1f, source = InputDevice.SOURCE_JOYSTICK)) shouldBe null
    }
}
