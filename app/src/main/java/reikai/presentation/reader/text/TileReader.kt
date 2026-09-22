package reikai.presentation.reader.text

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.Closeable
import java.io.File

/**
 * Where a tall picture's slices are decoded from, kept open while the picture is on screen. The platform
 * reads a region of JPEG, PNG, WebP and HEIF only, so a picture in any other format has none of this and
 * is drawn from the copy the loader decoded, as every picture used to be.
 */
internal class TileReader private constructor(private val decoder: BitmapRegionDecoder) : Closeable {

    val sourceWidth: Int get() = decoder.width
    val sourceHeight: Int get() = decoder.height

    /** The picture's [rows], or null when the source is gone: a cached file the cache has since dropped. */
    fun decode(rows: IntRange, sampleSize: Int): Bitmap? {
        if (rows.isEmpty()) return null
        val region = Rect(0, rows.first, decoder.width, rows.last + 1)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return runCatching { decoder.decodeRegion(region, options) }.getOrNull()
    }

    override fun close() {
        runCatching { decoder.recycle() }
    }

    companion object {
        fun of(file: File): TileReader? = open { BitmapRegionDecoder.newInstance(file.inputStream(), false) }

        fun of(bytes: ByteArray): TileReader? = open {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }

        @Suppress("DEPRECATION")
        private fun open(decoder: () -> BitmapRegionDecoder?): TileReader? =
            runCatching { decoder()?.let(::TileReader) }.getOrNull()
    }
}
