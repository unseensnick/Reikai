package reikai.data.extension

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.blackholeSink
import okio.buffer
import org.junit.jupiter.api.Test
import java.io.IOException

class StoreIndexCapTest {

    @Test
    fun `an index of exactly the cap is read whole`() {
        zeros(MAX_STORE_INDEX_BYTES).cappedAsStoreIndex().readAll(blackholeSink()) shouldBe MAX_STORE_INDEX_BYTES
    }

    @Test
    fun `an index past the cap fails the read`() {
        shouldThrow<IOException> { zeros(MAX_STORE_INDEX_BYTES + 1).cappedAsStoreIndex().readAll(blackholeSink()) }
    }

    /** Generated as it is read, so the test never holds the index in memory. */
    private fun zeros(size: Long) = object : Source {
        private var left = size

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (left == 0L) return -1
            val n = minOf(byteCount, left, CHUNK.size.toLong())
            sink.write(CHUNK, 0, n.toInt())
            left -= n
            return n
        }

        override fun timeout() = Timeout.NONE

        override fun close() = Unit
    }.buffer()

    private companion object {
        val CHUNK = ByteArray(8192)
    }
}
