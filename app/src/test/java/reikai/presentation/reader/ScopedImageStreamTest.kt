package reikai.presentation.reader

import coil3.decode.ImageSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import okio.Buffer
import okio.FileSystem
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.Executor

/** Closing the reader cancels its scope's job, which must stop a picture the WebView is reading. */
class ScopedImageStreamTest {

    private val job = Job()
    private val body = Body("picture")
    private val inline = Executor { it.run() }

    @Test
    fun `closing the stream closes the picture`() {
        ScopedImageStream(body.picture, job, inline).close()

        body.closed shouldBe true
    }

    @Test
    fun `the reader closing between reads closes the picture and fails the next read`() {
        val stream = ScopedImageStream(body.picture, job, inline)

        job.cancel()

        body.closed shouldBe true
        shouldThrow<IOException> { stream.read() }
    }

    /** The close waits for the read in progress, since a source is not safe to close from another thread. */
    @Test
    fun `the reader closing during a read closes the picture once that read returns`() {
        body.onRead = { job.cancel() }
        val stream = ScopedImageStream(body.picture, job, inline)

        stream.read(ByteArray(16), 0, 16)

        body.closed shouldBe true
        shouldThrow<IOException> { stream.read() }
    }

    private class Body(text: String) {
        var closed = false
        var onRead: () -> Unit = {}
        val picture = ImageSource(
            object : ForwardingSource(Buffer().writeUtf8(text)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    // A close arriving during the read must not reach the source until it returns.
                    onRead().also { closed shouldBe false }
                    return super.read(sink, byteCount)
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }.buffer(),
            FileSystem.SYSTEM,
        )
    }
}
