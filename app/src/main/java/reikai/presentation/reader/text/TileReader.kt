package reikai.presentation.reader.text

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.Closeable
import java.io.File

/**
 * Where a tall picture's slices are decoded from, kept open while the picture is on screen. The platform
 * reads a region of JPEG, PNG, WebP and HEIF only, so a picture in any other format has none of this and
 * is drawn whole from the copy the loader decoded.
 */
internal class TileReader private constructor(private val decoder: BitmapRegionDecoder) : Closeable {

    // Read once: the decoder's size getters take the lock a region decode holds, so reading them as the
    // picture draws waited on the main thread for a whole slice, and they throw once it is recycled.
    val sourceWidth: Int = decoder.width
    val sourceHeight: Int = decoder.height

    /** The picture's [rows], or null when the source is gone: a cached file the cache has since dropped. */
    fun decode(rows: IntRange, sampleSize: Int): Bitmap? {
        if (rows.isEmpty()) return null
        val region = Rect(0, rows.first, sourceWidth, rows.last + 1)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return runCatching { decoder.decodeRegion(region, options) }.getOrNull()
    }

    /** Off the caller's thread, since recycling waits for a slice still decoding. */
    override fun close() {
        Dispatchers.IO.asExecutor().execute { runCatching { decoder.recycle() } }
    }

    companion object {
        // The decoder copies the stream in whole, so it can be closed at once.
        @Suppress("DEPRECATION")
        fun of(file: File): TileReader? = open { file.inputStream().use { BitmapRegionDecoder.newInstance(it, false) } }

        @Suppress("DEPRECATION")
        fun of(bytes: ByteArray): TileReader? = open {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }

        private fun open(decoder: () -> BitmapRegionDecoder?): TileReader? =
            runCatching { decoder()?.let(::TileReader) }.getOrNull()
    }
}
