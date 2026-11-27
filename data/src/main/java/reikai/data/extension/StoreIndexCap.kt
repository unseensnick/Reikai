package reikai.data.extension

import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Far above the largest public index, which is under 2 MB. */
internal const val MAX_STORE_INDEX_BYTES = 64L shl 20

/**
 * This store index, failing with an [IOException] once more than [MAX_STORE_INDEX_BYTES] have been read.
 * Applied after decompression, so a broken address or a gzip bomb fails the fetch instead of running the
 * heap out, which no `catch (e: Exception)` would survive.
 */
internal fun BufferedSource.cappedAsStoreIndex(): BufferedSource = object : ForwardingSource(this) {
    private var total = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read > 0) {
            total += read
            if (total > MAX_STORE_INDEX_BYTES) throw IOException("Extension store index too large")
        }
        return read
    }
}.buffer()
