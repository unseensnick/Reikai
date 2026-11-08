package reikai.presentation.reader

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

/** The high quality renderer's long strip scrolls smoothly however the system's animations are set. */
@OptIn(ExperimentalTestApi::class)
class StripSmoothScrollTest {

    // Developer options' "Animation off", which lands a default-scale animation in one frame.
    private val animationsOff = object : MotionDurationScale {
        override val scaleFactor = 0f
    }

    private suspend fun steps(delta: Float): List<Float> {
        val steps = mutableListOf<Float>()
        springScroll(delta) { steps += it }
        return steps
    }

    @Test
    fun `a scroll takes many frames with system animations off`() = runTest {
        withContext(TestMonotonicFrameClock(this) + animationsOff) { steps(1000f) }.size shouldBeGreaterThan 10
    }

    @Test
    fun `a scroll's steps add up to the distance asked`() = runTest {
        withContext(TestMonotonicFrameClock(this)) { steps(1000f) }.sum().roundToInt() shouldBe 1000
    }
}
