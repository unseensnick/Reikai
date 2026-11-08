package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.viewer.pager.SimplePagedSettings
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.core.common.util.system.ImageUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A wide page split or rotated as the classic viewers do it, for the high quality renderer's pixels. */
class WidePagesTest {

    private fun settings(
        split: Boolean = false,
        invert: Boolean = false,
        rotate: Boolean = false,
        rotateInvert: Boolean = false,
    ) = SimplePagedSettings(split, invert, rotate, rotateInvert)

    @ParameterizedTest
    @CsvSource(
        "true,false,false,LEFT",
        "true,true,false,RIGHT",
        "false,false,false,RIGHT",
        "false,true,false,LEFT",
        "true,false,true,RIGHT",
        "false,true,true,RIGHT",
    )
    fun `a split page shows the reading side first, the other second, swapped by invert`(
        leftToRight: Boolean,
        second: Boolean,
        invert: Boolean,
        side: ImageUtil.Side,
    ) {
        splitHalfSide(leftToRight, second, invert) shouldBe side
    }

    @Test
    fun `a page reached going back moves on to its second half once found split`() {
        landsOnSecondHalf(arrivedBackward = "p3", shown = "p3", split = "p3") shouldBe true
    }

    @Test
    fun `a page reached going forward stays on its first half`() {
        landsOnSecondHalf(arrivedBackward = null, shown = "p3", split = "p3") shouldBe false
    }

    @Test
    fun `a page the reader has since left is not moved to`() {
        landsOnSecondHalf(arrivedBackward = "p3", shown = "p2", split = "p3") shouldBe false
    }

    @Test
    fun `a page found split behind the one reached going back moves nothing`() {
        landsOnSecondHalf(arrivedBackward = "p3", shown = "p3", split = "p2") shouldBe false
    }

    @Test
    fun `a page that is not wide is left whole`() {
        settings(
            split = true,
            rotate = true,
        ).widePageShape(1000, 1000, strip = false, leftToRight = true, second = false) shouldBe
            WidePageShape.Whole
    }

    @Test
    fun `rotate to fit wins over split, clockwise unless inverted`() {
        settings(split = true, rotate = true, rotateInvert = true)
            .widePageShape(2000, 1000, strip = false, leftToRight = true, second = false) shouldBe
            WidePageShape.Rotated(-90f)
    }

    @Test
    fun `a wide page with neither setting on is left whole`() {
        settings().widePageShape(2000, 1000, strip = false, leftToRight = true, second = false) shouldBe
            WidePageShape.Whole
    }

    @Test
    fun `a split wide page in the strip stacks its right half on top unless inverted`() {
        settings(
            split = true,
            invert = true,
        ).widePageShape(2000, 1000, strip = true, leftToRight = true, second = false) shouldBe
            WidePageShape.Stacked(ImageUtil.Side.LEFT)
    }

    @Test
    fun `a split wide page in paged mode shows its reading half`() {
        settings(split = true).widePageShape(2000, 1000, strip = false, leftToRight = false, second = false) shouldBe
            WidePageShape.Half(ImageUtil.Side.RIGHT)
    }

    // A 4 x 2 image whose pixels are numbered row by row: 0 1 2 3 / 4 5 6 7.
    private val image: ByteBuffer = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).apply {
        repeat(8) { putInt(it * 4, it) }
    }

    private fun WidePageShape.pixels(bytesPerPixel: Int = 4): Triple<Int, Int, List<Int>> {
        val out = reshapePixels(image, 4, 2, bytesPerPixel, this)
        val ints = out.pixels.order(ByteOrder.nativeOrder())
        return Triple(out.width, out.height, List(out.width * out.height * bytesPerPixel / 4) { ints.getInt(it * 4) })
    }

    @Test
    fun `the left half keeps the left columns`() {
        WidePageShape.Half(ImageUtil.Side.LEFT).pixels() shouldBe Triple(2, 2, listOf(0, 1, 4, 5))
    }

    @Test
    fun `the right half keeps the right columns`() {
        WidePageShape.Half(ImageUtil.Side.RIGHT).pixels() shouldBe Triple(2, 2, listOf(2, 3, 6, 7))
    }

    @Test
    fun `a stacked page puts the upper side above the other`() {
        WidePageShape.Stacked(ImageUtil.Side.RIGHT).pixels() shouldBe Triple(2, 4, listOf(2, 3, 6, 7, 0, 1, 4, 5))
    }

    @Test
    fun `a clockwise turn puts the bottom left corner at the top left`() {
        WidePageShape.Rotated(90f).pixels() shouldBe Triple(2, 4, listOf(4, 0, 5, 1, 6, 2, 7, 3))
    }

    @Test
    fun `a counterclockwise turn puts the top right corner at the top left`() {
        WidePageShape.Rotated(-90f).pixels() shouldBe Triple(2, 4, listOf(3, 7, 2, 6, 1, 5, 0, 4))
    }

    // A gain map is one or three bytes a pixel, so it reshapes byte by byte with its page.
    private val gainMap: ByteBuffer = ByteBuffer.allocateDirect(4 * 2).apply { repeat(8) { put(it, it.toByte()) } }

    @Test
    fun `a one-channel gain map turns with its page`() {
        val out = reshapePixels(gainMap, 4, 2, 1, WidePageShape.Rotated(90f))
        List(8) { out.pixels.get(it).toInt() } shouldBe listOf(4, 0, 5, 1, 6, 2, 7, 3)
    }

    @Test
    fun `a three-channel gain map turns whole pixels`() {
        // The same 2 x 1 map turned counterclockwise: (3,4,5) above (0,1,2).
        val out = reshapePixels(gainMap, 2, 1, 3, WidePageShape.Rotated(-90f))
        List(6) { out.pixels.get(it).toInt() } shouldBe listOf(3, 4, 5, 0, 1, 2)
    }

    @Test
    fun `a three-channel gain map splits with its page`() {
        // Read as three-byte pixels, the first six bytes are a 2 x 1 map: (0,1,2) (3,4,5).
        val out = reshapePixels(gainMap, 2, 1, 3, WidePageShape.Half(ImageUtil.Side.RIGHT))
        Triple(out.width, out.height, List(3) { out.pixels.get(it).toInt() }) shouldBe Triple(1, 1, listOf(3, 4, 5))
    }

    @Test
    fun `half-float pixels turn whole, eight bytes at a time`() {
        // Read as eight-byte pixels the same buffer is 2 x 2: (0,1) (2,3) / (4,5) (6,7).
        val out = reshapePixels(image, 2, 2, 8, WidePageShape.Rotated(90f))
        val ints = List(8) { out.pixels.order(ByteOrder.nativeOrder()).getInt(it * 4) }
        ints shouldBe listOf(4, 5, 0, 1, 6, 7, 2, 3)
    }
}
