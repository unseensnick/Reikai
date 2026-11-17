package reikai.data.download

import eu.kanade.tachiyomi.util.storage.saveTo
import okhttp3.Response
import java.io.OutputStream

/**
 * Resumes a page image cut off partway only where what comes back continues the bytes on disk. A
 * source that decrypts or unscrambles images in an interceptor sees the shortened answer as a whole
 * file and decodes it from its own start, so appending it keeps the first part and garbles the rest.
 * The resumed request starts [OVERLAP] bytes early, and those must come back as stored, or the image
 * is fetched whole instead.
 */
object ResumedImageDownload {

    const val OVERLAP = 1024

    /**
     * Writes the image into [open]'s stream, appended to the [existingSize] bytes on disk when the answer
     * continues them, and returns the response whose body was written, consumed. [stored] reads the file
     * from an offset to its end; [fetch] asks the source from an offset, 0 for the whole image.
     */
    suspend fun write(
        existingSize: Long,
        stored: (from: Long) -> ByteArray,
        fetch: suspend (from: Long) -> Response,
        open: (append: Boolean) -> OutputStream,
    ): Response {
        val from = if (existingSize > OVERLAP) existingSize - OVERLAP else 0L
        val response = fetch(from)
        if (response.code != 206) return response.also { it.body.source().saveTo(open(false)) }
        val body = response.body.source()
        val expected = stored(from)
        val continues =
            body.request(expected.size.toLong()) && body.readByteArray(expected.size.toLong()).contentEquals(expected)
        if (continues) return response.also { body.saveTo(open(true)) }
        response.close()
        return fetch(0L).also { it.body.source().saveTo(open(false)) }
    }
}
