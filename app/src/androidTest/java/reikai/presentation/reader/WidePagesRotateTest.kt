package reikai.presentation.reader

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.mpreg.imagedecoder.ImageDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rotate to fit through the library's native quarter turn, which only runs on a device. Each case is the layout
 * the turn must produce; a 4 x 2 image of pixels 0..7 is turned to 2 x 4.
 */
@RunWith(AndroidJUnit4::class)
class WidePagesRotateTest {

    private fun image(direct: Boolean = true): ByteBuffer =
        (if (direct) ByteBuffer.allocateDirect(4 * 2 * 4) else ByteBuffer.allocate(4 * 2 * 4))
            .order(ByteOrder.nativeOrder())
            .apply { repeat(8) { putInt(it * 4, it) } }

    private fun ReshapedPixels.ints(count: Int): List<Int> {
        val ints = pixels.order(ByteOrder.nativeOrder())
        return List(count) { ints.getInt(it * 4) }
    }

    @Test
    fun aClockwiseTurnPutsTheBottomLeftCornerAtTheTopLeft() {
        val out = reshapePixels(image(), 4, 2, 4, WidePageShape.Rotated(90f))
        assertEquals(Triple(2, 4, listOf(4, 0, 5, 1, 6, 2, 7, 3)), Triple(out.width, out.height, out.ints(8)))
    }

    @Test
    fun aCounterclockwiseTurnPutsTheTopRightCornerAtTheTopLeft() {
        val out = reshapePixels(image(), 4, 2, 4, WidePageShape.Rotated(-90f))
        assertEquals(Triple(2, 4, listOf(3, 7, 2, 6, 1, 5, 0, 4)), Triple(out.width, out.height, out.ints(8)))
    }

    @Test
    fun pixelsOutsideDirectMemoryTurnTheSame() {
        val out = reshapePixels(image(direct = false), 4, 2, 4, WidePageShape.Rotated(90f))
        assertEquals(listOf(4, 0, 5, 1, 6, 2, 7, 3), out.ints(8))
    }

    // A gain map is one or three bytes a pixel, so it turns byte by byte with its page.
    private val gainMap: ByteBuffer = ByteBuffer.allocateDirect(4 * 2).apply { repeat(8) { put(it, it.toByte()) } }

    @Test
    fun aOneChannelGainMapTurnsWithItsPage() {
        val out = reshapePixels(gainMap, 4, 2, 1, WidePageShape.Rotated(90f))
        assertEquals(listOf(4, 0, 5, 1, 6, 2, 7, 3), List(8) { out.pixels.get(it).toInt() })
    }

    @Test
    fun aThreeChannelGainMapTurnsWholePixels() {
        // The first six bytes as a 2 x 1 map of three-byte pixels, turned counterclockwise: (3,4,5) above (0,1,2).
        val out = reshapePixels(gainMap, 2, 1, 3, WidePageShape.Rotated(-90f))
        assertEquals(listOf(3, 4, 5, 0, 1, 2), List(6) { out.pixels.get(it).toInt() })
    }

    @Test
    fun halfFloatPixelsTurnWholeEightBytesAtATime() {
        // Read as eight-byte pixels the same buffer is 2 x 2: (0,1) (2,3) / (4,5) (6,7).
        val out = reshapePixels(image(), 2, 2, 8, WidePageShape.Rotated(90f))
        assertEquals(listOf(4, 5, 0, 1, 6, 7, 2, 3), out.ints(8))
    }

    /** The decoder's frames are what the viewer turns; the native turn reads only direct memory. */
    @Test
    fun aDecodedFrameIsInDirectMemory() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        ImageDecoder.open(png.inputStream()).use { decoder ->
            decoder.decodeNext().use { frame -> assertTrue(frame.image.isDirect) }
        }
    }

    /**
     * A real Ultra HDR JPEG, made here since Android 14 writes one for a bitmap with a gain map: the decoder's map is
     * in direct memory and turns with its page. The map's quadrants carry 40, 100, 160 and 220 (top left, top right,
     * bottom left, bottom right), so a clockwise turn puts 160 top left, 40 top right, 220 bottom left, 100 bottom right.
     */
    @Test
    fun aDecodedGainMapIsDirectAndTurnsWithItsPage() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val (width, height) = 256 to 128
        fun quadrants(vararg greys: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val grey = greys[(if (y < height / 2) 0 else 2) + (if (x < width / 2) 0 else 1)]
                    setPixel(x, y, android.graphics.Color.rgb(grey, grey, grey))
                }
            }
        }
        val photo = quadrants(128, 128, 128, 128).apply { gainmap = Gainmap(quadrants(40, 100, 160, 220)) }
        val jpeg = ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()

        ImageDecoder.open(jpeg.inputStream()).use { decoder ->
            decoder.decodeNext().close()
            val map = decoder.getGainmap()
            assertNotNull(map)
            map!!
            assertTrue(map.pixels.isDirect)
            val out = reshapePixels(map.pixels, map.width, map.height, map.channels, WidePageShape.Rotated(90f))
            fun at(fx: Float, fy: Float): Int {
                val x = (out.width * fx).toInt()
                val y = (out.height * fy).toInt()
                return out.pixels.get((y * out.width + x) * map.channels).toInt() and 0xFF
            }
            val corners = listOf(at(0.25f, 0.25f), at(0.75f, 0.25f), at(0.25f, 0.75f), at(0.75f, 0.75f))
            // Ordered as expected, with room for JPEG's loss and the map's encoding.
            assertTrue(
                "corners $corners",
                corners[1] < corners[3] && corners[3] < corners[0] && corners[0] < corners[2],
            )
        }
    }

    /** Not a check: logs how long a page-sized turn takes natively against the per-pixel loop it replaced. */
    @Test
    fun timesAPageSizedTurn() {
        val width = 4000
        val height = 2800
        val source = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        repeat(3) { reshapePixels(source, width, height, 4, WidePageShape.Rotated(90f)) }
        val native = (1..5).minOf { timed { reshapePixels(source, width, height, 4, WidePageShape.Rotated(90f)) } }
        val loop = (1..3).minOf { timed { perPixelTurn(source, width, height) } }
        Log.i("WidePagesRotateTest", "4000x2800 turn: native ${native}ms, per-pixel loop ${loop}ms")
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    // The clockwise loop rotate to fit used before the native turn, kept here only to time against.
    private fun perPixelTurn(pixels: ByteBuffer, width: Int, height: Int) {
        val out = ByteBuffer.allocateDirect(width * height * 4).order(pixels.order())
        for (y in 0 until width) {
            for (x in 0 until height) {
                out.putInt((y * height + x) * 4, pixels.getInt(((height - 1 - x) * width + y) * 4))
            }
        }
    }
}
