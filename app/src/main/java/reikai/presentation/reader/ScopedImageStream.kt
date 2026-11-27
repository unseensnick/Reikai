package reikai.presentation.reader

import coil3.decode.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import okio.BufferedSource
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor

/**
 * A picture's body as the WebView reads it, closed when [job] ends, so closing the reader stops a read in
 * progress. An okio source is not thread-safe and an HTTP/1 body's close reads the socket, so a close that
 * arrives mid-read is left to that read's own thread, and the next read fails.
 */
internal class ScopedImageStream(
    private val picture: ImageSource,
    job: Job,
    /** Where a close the job's end triggers runs, since the reader closes on the main thread. */
    private val releaseOn: Executor = Dispatchers.IO.asExecutor(),
) : InputStream() {

    private val lock = Any()
    private var reading = false
    private var shut = false
    private var released = false

    // Last: on a job that already ended, the handler runs here and needs the fields above.
    private val handle = job.invokeOnCompletion { releaseOn.execute(::shut) }

    /** [block] over the picture, unless the stream was closed or its job ended. */
    fun <T> read(block: (BufferedSource) -> T): T {
        synchronized(lock) {
            if (shut) throw IOException("The picture's reader closed")
            reading = true
        }
        try {
            return block(picture.source())
        } finally {
            synchronized(lock) {
                reading = false
                if (shut) release()
            }
        }
    }

    override fun read(): Int = read { if (it.exhausted()) -1 else it.readByte().toInt() and 0xff }

    override fun read(b: ByteArray, off: Int, len: Int): Int = read { it.read(b, off, len) }

    override fun close() {
        handle.dispose()
        shut()
    }

    private fun shut() = synchronized(lock) {
        shut = true
        if (!reading) release()
    }

    private fun release() {
        if (released) return
        released = true
        picture.close()
    }
}
