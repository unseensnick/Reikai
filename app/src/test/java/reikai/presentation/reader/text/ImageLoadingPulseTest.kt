package reikai.presentation.reader.text

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.presentation.components.LoadingPulse
import java.io.File

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

    // Unit tests run from the module directory, so the asset resolves relative to it.
    private val css = File("src/main/assets/novel-web/reader.css").readText()

    private fun cssNumber(pattern: String) = Regex(pattern).find(css)!!.groupValues[1].toFloat()

    @Test
    fun `the page's picture box is as strong as the drawable's at its peak`() {
        cssNumber("""img\[src]:not\(\.rk-loaded\) \{[^}]*?opacity: ([\d.]+);""") shouldBe
            (LoadingPulse.MAX * ImageLoadingDrawable.BOX_ALPHA plusOrMinus 0.001f)
    }

    @Test
    fun `the page's picture box fades as far as the drawable's`() {
        cssNumber("""@keyframes rk-pulse \{\s*from \{\s*opacity: ([\d.]+);""") shouldBe
            (LoadingPulse.MIN * ImageLoadingDrawable.BOX_ALPHA plusOrMinus 0.001f)
    }

    @Test
    fun `the page's picture box pulses at the drawable's pace`() {
        cssNumber("""animation: rk-pulse ([\d.]+)s""") * 1000 shouldBe
            (LoadingPulse.HALF_PERIOD_MS.toFloat() plusOrMinus 0.5f)
    }
}
