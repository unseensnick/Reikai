package reikai.novel.export

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import reikai.util.openTruncatingOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Writes the book to [this] from empty, and deletes it when the write fails or is cancelled: a book cut
 * short has no package document, so no reader opens it.
 */
internal suspend fun UniFile.writeOrDelete(context: Context, write: suspend (OutputStream) -> Unit) {
    try {
        openTruncatingOutputStream(context).use { write(it) }
    } catch (e: Throwable) {
        delete()
        throw e
    }
}

/** The cover as a book carries it: a JPEG, or a PNG when it has transparency, both EPUB core types. */
internal fun coverPicture(bitmap: Bitmap): ExportPicture {
    val png = bitmap.hasAlpha()
    val bytes = ByteArrayOutputStream().also {
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, COVER_QUALITY, it)
    }.toByteArray()
    return ExportPicture(bytes, if (png) "image/png" else "image/jpeg")
}

private const val COVER_QUALITY = 95
