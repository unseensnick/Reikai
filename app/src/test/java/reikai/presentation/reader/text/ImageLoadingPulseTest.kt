package reikai.presentation.reader.text

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** A loading picture's box pulses as the details skeleton does, and as the page's reader.css does. */
class ImageLoadingPulseTest {

    @ParameterizedTest(name = "{0} ms in, the box is at {1}")
    @CsvSource(
        "0, 0.45",
        "225, 0.5159",
        "450, 0.675",
        "900, 0.9",
        "1800, 0.45",
        "2700, 0.9",
    )
    fun `the box pulses between faint and strong every 900 ms`(elapsedMs: Long, strength: Float) {
        imageLoadingPulse(elapsedMs) shouldBe (strength plusOrMinus 0.001f)
    }
}
