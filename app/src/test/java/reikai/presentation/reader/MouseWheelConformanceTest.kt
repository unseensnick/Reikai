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
import io.mockk.every
import io.mockk.mockk
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
class MouseWheelConformanceTest(private val notch: Float, private val expected: String) {

    @get:Rule
    val compose = createComposeRule()

    private fun axisEvent() = MotionEvent.obtain(
        0L,
        0L,
        MotionEvent.ACTION_SCROLL,
        1,
        arrayOf(MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_MOUSE }),
        arrayOf(
            MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_VSCROLL, -notch) },
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
    fun `a notch turns the manga page the way the parameter names`() {
        val moves = mutableListOf<String>()
        val viewer = mockk<Viewer> {
            every { moveForward() } answers { moves += FORWARD }
            every { moveBackward() } answers { moves += BACKWARD }
        }
        compose.setContent { Box(Modifier.size(100.dp).testTag("page").mouseWheelPaging(viewer)) }

        compose.onNodeWithTag("page").performMouseInput {
            moveTo(Offset(10f, 10f))
            scroll(notch)
        }
        compose.waitForIdle()

        moves shouldBe listOf(expected)
    }

    @Test
    fun `a notch turns the novel page the way the parameter names`() {
        wheelForward(axisEvent()) shouldBe (expected == FORWARD)
    }

    companion object {
        private const val FORWARD = "forward"
        private const val BACKWARD = "backward"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "notch={0}")
        fun turns(): List<Array<Any>> = listOf(arrayOf(1f, FORWARD), arrayOf(-1f, BACKWARD))
    }
}
