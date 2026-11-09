package reikai.novel.export

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import reikai.util.openTruncatingOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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

/**
 * Writes the book as [name] in this folder through a `.tmp` file renamed over the earlier copy once it is
 * whole, as the manga downloader names its pages, so a failed export again leaves the earlier one readable.
 */
internal suspend fun UniFile.writeBookNamed(context: Context, name: String, write: suspend (OutputStream) -> Unit) {
    val partName = "$name.tmp"
    findFile(partName)?.delete()
    val part = createFile(partName) ?: throw IOException("No file")
    part.writeOrDelete(context, write)
    findFile(name)?.delete()
    if (!part.renameTo(name)) {
        part.delete()
        throw IOException("Could not name $name")
    }
}

/**
 * The file name a book titled [title] is written as, its [number]th of that title from 2 on, cut so the
 * extension and number always fit the name limit: cutting the whole name dropped `.epub` from a long title.
 */
internal fun epubFileName(title: String, number: Int = 1): String {
    val tail = (if (number > 1) " ($number)" else "") + EPUB_EXTENSION
    return DiskUtil.buildValidFilename(title, DiskUtil.MAX_FILE_NAME_BYTES - tail.toByteArray().size) + tail
}

/**
 * The names one folder export gives its books, numbered once a title's name is taken, so two novels of one
 * title do not replace each other. Matched ignoring case, as a storage provider's folder does.
 */
internal class EpubFileNames {
    private val taken = HashSet<String>()

    fun next(title: String): String =
        generateSequence(1) { it + 1 }.map { epubFileName(title, it) }.first { taken.add(it.lowercase()) }
}

private const val EPUB_EXTENSION = ".epub"

/**
 * The cover as a book carries it, and a picture as the viewer's Save writes it: a JPEG, or a PNG when it has
 * transparency, both EPUB core types.
 */
internal fun coverPicture(bitmap: Bitmap): ExportPicture {
    val png = bitmap.hasAlpha()
    val bytes = ByteArrayOutputStream().also {
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, COVER_QUALITY, it)
    }.toByteArray()
    return ExportPicture(bytes, if (png) "image/png" else "image/jpeg")
}

private const val COVER_QUALITY = 95
