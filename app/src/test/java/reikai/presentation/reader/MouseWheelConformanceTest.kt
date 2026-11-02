package reikai.presentation.reader

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.mouseWheelPaging
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * One wheel notch turns the same way in both readers. The two read opposite signs, Compose's delta and
 * Android's axis, so each case drives both from the same turn of the wheel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class MouseWheelConformanceTest(private val down: Boolean) {

    @get:Rule
    val compose = createComposeRule()

    private fun axisEvent() = MotionEvent.obtain(
        0L,
        0L,
        MotionEvent.ACTION_SCROLL,
        1,
        arrayOf(MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_MOUSE }),
        arrayOf(
            MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_VSCROLL, if (down) -1f else 1f) },
        ),
        0,
        0,
        1f,
        1f,
        0,
        0,
        InputDevice.SOURCE_MOUSE,
        0,
    )

    @Test
    fun `a notch reads the same way in both readers`() {
        val viewer = mockk<Viewer>(relaxed = true)
        compose.setContent { Box(Modifier.size(100.dp).testTag("page").mouseWheelPaging(viewer)) }

        compose.onNodeWithTag("page").performMouseInput {
            moveTo(Offset(10f, 10f))
            scroll(if (down) 1f else -1f)
        }
        compose.waitForIdle()

        verify(exactly = if (down) 1 else 0) { viewer.moveForward() }
        verify(exactly = if (down) 0 else 1) { viewer.moveBackward() }
        wheelForward(axisEvent()) shouldBe down
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "down={0}")
        fun turns(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
    }
}
