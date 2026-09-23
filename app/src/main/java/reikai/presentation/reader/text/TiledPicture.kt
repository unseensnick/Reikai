package reikai.presentation.reader.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * A tall picture drawn from the slices around what is on screen, so a strip the loader had to shrink to
 * fit a decode is still drawn at the page's width and its own sharpness. What is held is the slices on
 * screen plus one either side, which is what bounds the memory: nothing else of the picture is decoded.
 * Until a slice arrives its part is drawn from [preview], the shrunken whole the loader decoded.
 */
internal class TiledPicture(
    private val reader: TileReader,
    private val box: PictureBox,
    private val preview: Drawable?,
    private val scope: CoroutineScope,
    private val onTileReady: () -> Unit,
    tileDrawnPx: Int = TILE_DRAWN_PX,
) : Drawable(), Closeable {

    private val tileSourceHeight = tileSourceHeight(reader.sourceHeight, box.height, tileDrawnPx)
    private val tileCount = tileCount(reader.sourceHeight, tileSourceHeight)
    private val sampleSize = tileSampleSize(reader.sourceWidth, box.width)

    /** Main thread. The slices decoded, and the ones being decoded, keyed by their place in the picture. */
    private val tiles = mutableMapOf<Int, Bitmap>()
    private val loading = mutableSetOf<Int>()
    private var wanted: IntRange = IntRange.EMPTY

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    /** What this test reads to say which slices are held; nothing else has a reason to. */
    internal val heldTiles: Set<Int> get() = tiles.keys

    init {
        setBounds(0, 0, box.width, box.height)
        preview?.setBounds(0, 0, box.width, box.height)
    }

    /** Main thread: the rows of this picture on screen, in the pixels it is drawn at. */
    fun onVisible(topPx: Int, bottomPx: Int) {
        val next = tilesFor(topPx, bottomPx, box.height, tileCount, TILES_AHEAD)
        if (next == wanted) return
        wanted = next
        // Scrolled away, so its slices are let go of before the ones now wanted are asked for.
        tiles.keys.retainAll { it in next }
        next.forEach { index -> if (index !in tiles && loading.add(index)) load(index) }
    }

    private fun load(index: Int) {
        scope.launch {
            val rows = tileRows(index, tileSourceHeight, reader.sourceHeight)
            val bitmap = withContext(Dispatchers.IO) { reader.decode(rows, sampleSize) }
            loading -= index
            // Gone from what is wanted while it decoded, which a scroll does.
            if (bitmap == null || index !in wanted) return@launch
            tiles[index] = bitmap
            onTileReady()
        }
    }

    override fun draw(canvas: Canvas) {
        // Under the slices, so a part not decoded yet is the picture rather than a hole.
        preview?.draw(canvas)
        val scale = bounds.height().toFloat() / reader.sourceHeight
        // Top down, so a slice landing later never draws over one above it.
        tiles.toSortedMap().forEach { (index, bitmap) ->
            val rows = tileRows(index, tileSourceHeight, reader.sourceHeight)
            if (rows.isEmpty()) return@forEach
            destination.set(
                bounds.left.toFloat(),
                bounds.top + rows.first * scale,
                bounds.right.toFloat(),
                bounds.top + (rows.last + 1) * scale,
            )
            canvas.drawBitmap(bitmap, null, destination, paint)
        }
    }

    override fun close() {
        tiles.clear()
        wanted = IntRange.EMPTY
        reader.close()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** About a screen's worth of the picture per slice, so a scroll crosses one at a time. */
        const val TILE_DRAWN_PX = 2_000
        const val TILES_AHEAD = 1
    }
}

/**
 * A tall picture in a chapter's text: the chunk it sits in, where its span starts, the space above it its
 * span draws, and the picture. Enough for the viewport to say which of its rows are on screen.
 */
internal class TiledAnchor(
    val view: TextView,
    val offset: Int,
    val topPx: Int,
    val picture: TiledPicture,
)
