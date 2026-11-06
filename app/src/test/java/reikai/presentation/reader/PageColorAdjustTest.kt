package reikai.presentation.reader

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** The one colour rule both renderers apply, the normal viewers as a colour matrix and the other as a table. */
class PageColorAdjustTest {

    private val samples = listOf(
        Triple(0f, 0f, 0f),
        Triple(1f, 1f, 1f),
        Triple(0.9f, 0.1f, 0.3f),
        Triple(0.25f, 0.5f, 0.75f),
        Triple(0.6f, 0.6f, 0.2f),
        Triple(0.05f, 0.95f, 0.5f),
    )

    private fun PageColorAdjust.of(r: Float, g: Float, b: Float) = apply(r, g, b).toList().map { it.toDouble() }

    @Test
    fun `nothing set leaves the page alone in both renderers`() {
        listOf(PageColorAdjust().colorFilter(), PageColorAdjust().lut()).filterNotNull() shouldBe emptyList()
    }

    @Test
    fun `grayscale weighs the channels as the reader always has`() {
        PageColorAdjust(grayscale = true).of(1f, 0f, 0f).map { (it * 1000).roundToInt() } shouldBe listOf(213, 213, 213)
    }

    @Test
    fun `inverted turns each channel over`() {
        PageColorAdjust(inverted = true).of(0.2f, 0.4f, 0.6f).map { (it * 100).roundToInt() } shouldBe
            listOf(80, 60, 40)
    }

    @Test
    fun `double contrast pushes the quarter tones to black and white`() {
        val adjust = PageColorAdjust(contrastPercent = 200)

        listOf(adjust.of(0.25f, 0.5f, 0.75f)) shouldBe listOf(listOf(0.0, 0.5, 1.0))
    }

    @Test
    fun `half contrast pulls black and white toward grey`() {
        PageColorAdjust(contrastPercent = 50).of(0f, 1f, 0.5f) shouldBe listOf(0.25, 0.75, 0.5)
    }

    /** The two renderers read one rule: the matrix the normal viewers take matches [PageColorAdjust.apply]. */
    @Test
    fun `the colour matrix gives what apply gives`() {
        val adjust = PageColorAdjust(grayscale = true, inverted = true, contrastPercent = 150)
        val m = adjust.colorMatrix()!!.values

        samples.maxOf { (r, g, b) ->
            val expected = adjust.apply(r, g, b).toList()
            (0 until 3).maxOf { row ->
                val raw = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4] / 255f
                abs(raw.coerceIn(0f, 1f) - expected[row])
            }
        }.toDouble() shouldBe (0.0 plusOrMinus 1e-5)
    }

    /** And the table the high quality renderer samples holds apply's answer at every one of its entries. */
    @Test
    fun `the page table holds what apply gives at each entry`() {
        val adjust = PageColorAdjust(grayscale = true, contrastPercent = 180)
        val lut = adjust.lut(size = 5)!!
        val step = 1f / 4

        val worst = (0 until 5).flatMap { b -> (0 until 5).flatMap { g -> (0 until 5).map { r -> Triple(r, g, b) } } }
            .maxOf { (r, g, b) ->
                val at = ((b * 5 + g) * 5 + r) * 3
                val expected = adjust.apply(r * step, g * step, b * step).toList()
                (0 until 3).maxOf { abs(lut.data[at + it] - expected[it]) }
            }

        worst.toDouble() shouldBe (0.0 plusOrMinus 1e-6)
    }

    /** Clamping waits for the end, so the steps commute and their order cannot set the renderers apart. */
    @Test
    fun `the steps give the same page in any order`() {
        val all = PageColorAdjust(grayscale = true, inverted = true, contrastPercent = 140)
        val grayThenRest = { r: Float, g: Float, b: Float ->
            val y = 0.213f * r + 0.715f * g + 0.072f * b
            val contrasted = (1f - y - 0.5f) * 1.4f + 0.5f
            contrasted.coerceIn(0f, 1f).toDouble()
        }

        samples.maxOf { (r, g, b) -> abs(all.of(r, g, b)[0] - grayThenRest(r, g, b)) } shouldBe (0.0 plusOrMinus 1e-5)
    }

    @Test
    fun `contrast alone keeps the page in colour`() {
        PageColorAdjust(contrastPercent = 120).of(0.9f, 0.1f, 0.3f).distinct().size shouldBe 3
    }
}
