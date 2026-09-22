package reikai.presentation.reader.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * That a tall picture is drawn from the slices around what is on screen: the right part of it, at the
 * width it is drawn, and never more of it held than those slices. The picture is a band of colour per
 * slice, so which rows were decoded, and where they were drawn, can be read off the pixels drawn.
 */
@RunWith(AndroidJUnit4::class)
class TiledPictureTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val scope = MainScope()
    private lateinit var file: File
    private var picture: TiledPicture? = null

    @Before
    fun setUp() {
        file = File(instrumentation.targetContext.cacheDir, "tiled-picture-test.png")
        file.writeBytes(bandedPng())
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { picture?.close() }
        scope.cancel()
        file.delete()
    }

    @Test
    fun aSliceIsDrawnWhereItsOwnRowsBelong() {
        val picture = open()
        show(picture, topPx = 0, bottomPx = SCREEN_PX)

        assertEquals(BANDS[0], colourAt(picture, atPx = 0))
    }

    @Test
    fun theSliceBelowItIsDrawnBelowIt() {
        val picture = open()
        show(picture, topPx = 0, bottomPx = SCREEN_PX)

        assertEquals(BANDS[1], colourAt(picture, atPx = BAND_DRAWN_PX + BAND_DRAWN_PX / 2))
    }

    @Test
    fun theBottomOfAPictureOnScreenIsDrawnFromItsOwnRows() {
        val picture = open()
        show(picture, topPx = DRAWN_HEIGHT_PX - SCREEN_PX, bottomPx = DRAWN_HEIGHT_PX)

        assertEquals(BANDS.last(), colourAt(picture, atPx = DRAWN_HEIGHT_PX - STRIP_PX))
    }

    @Test
    fun aPictureScrolledPastLetsItsSlicesGo() {
        val picture = open()
        show(picture, topPx = 0, bottomPx = SCREEN_PX)
        val atTheTop = held(picture)

        show(picture, topPx = DRAWN_HEIGHT_PX - SCREEN_PX, bottomPx = DRAWN_HEIGHT_PX)

        assertEquals(emptySet<Int>(), held(picture).intersect(atTheTop))
    }

    @Test
    fun onlyTheSlicesOnScreenAndOneEitherSideAreHeld() {
        val picture = open()
        show(picture, topPx = DRAWN_HEIGHT_PX / 2, bottomPx = DRAWN_HEIGHT_PX / 2 + SCREEN_PX)

        assertTrue("held ${held(picture)}", held(picture).size <= SLICES_ON_SCREEN + 2)
    }

    private fun open(): TiledPicture {
        val reader = checkNotNull(TileReader.of(file)) { "the picture could not be read in parts" }
        return TiledPicture(
            reader = reader,
            box = PictureBox(DRAWN_WIDTH_PX, DRAWN_HEIGHT_PX),
            preview = null,
            scope = scope,
            onTileReady = {},
            tileDrawnPx = SCREEN_PX,
        ).also { picture = it }
    }

    /** Puts [topPx] to [bottomPx] of the picture on screen and waits for its slices to arrive. */
    private fun show(picture: TiledPicture, topPx: Int, bottomPx: Int) {
        instrumentation.runOnMainSync { picture.onVisible(topPx, bottomPx) }
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (held(picture).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(SAMPLE_MS)
        // Whatever else is wanted lands in the same pass, which one more sample covers.
        Thread.sleep(SAMPLE_MS * 4)
    }

    private fun held(picture: TiledPicture): Set<Int> {
        var tiles = emptySet<Int>()
        instrumentation.runOnMainSync { tiles = picture.heldTiles.toSet() }
        return tiles
    }

    /** The colour the picture draws [atPx] rows down, read off a strip of what it draws there. */
    private fun colourAt(picture: TiledPicture, atPx: Int): Int {
        val strip = Bitmap.createBitmap(DRAWN_WIDTH_PX, STRIP_PX, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            Canvas(strip).apply {
                translate(0f, -atPx.toFloat())
                picture.draw(this)
            }
        }
        return strip.getPixel(DRAWN_WIDTH_PX / 2, STRIP_PX / 2)
    }

    /** A band per slice, so a slice drawn from the wrong rows, or in the wrong place, shows the wrong colour. */
    private fun bandedPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH_PX, SOURCE_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val band = SOURCE_HEIGHT_PX / BANDS.size
        BANDS.forEachIndexed { index, colour ->
            Canvas(bitmap).apply {
                clipRect(0, index * band, SOURCE_WIDTH_PX, (index + 1) * band)
                drawColor(colour)
            }
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private companion object {
        const val SOURCE_WIDTH_PX = 200
        const val SOURCE_HEIGHT_PX = 6_000

        /** Drawn at twice the picture's own size, as a strip narrower than the column is. */
        const val DRAWN_WIDTH_PX = 400
        const val DRAWN_HEIGHT_PX = 12_000

        /** One per slice, at the size a slice is cut to below. */
        val BANDS = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)

        const val SCREEN_PX = 2_000
        const val BAND_DRAWN_PX = DRAWN_HEIGHT_PX / 6
        const val SLICES_ON_SCREEN = 1
        const val STRIP_PX = 20
        const val TIMEOUT_MS = 10_000L
        const val SAMPLE_MS = 50L
    }
}
