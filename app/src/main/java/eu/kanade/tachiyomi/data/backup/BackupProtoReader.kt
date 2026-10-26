// Net-new. Streams a backup's top-level protobuf fields one at a time so a large backup is
// never fully materialised in memory (the read counterpart to BackupCreator.writeProtoField). Used
// by BackupFileValidator and BackupRestorer to replace the whole-file decodeFromByteArray, which
// OOMs on large libraries. Generic proto framing, no content-type coupling.
package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import kotlinx.serialization.SerializationException
import okio.buffer
import okio.gzip
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import java.io.IOException

class BackupProtoReader(
    private val context: Context,
) {
    /**
     * Reads the gzipped backup at [uri] field by field, invoking [onField] with each top-level
     * field's number and its length-delimited bytes. Non-length-delimited fields are skipped.
     */
    suspend fun read(uri: Uri, onField: suspend (fieldNumber: Int, data: ByteArray) -> Unit) {
        withIOContext {
            context.contentResolver.openInputStream(uri)!!.use { inputStream ->
                val source = inputStream.source().buffer()

                val peeked = source.peek().apply {
                    require(2)
                }
                val id1id2 = peeked.readShort().toInt()

                val payloadSource = when (id1id2) {
                    GZIP_MAGIC -> source.gzip().buffer()
                    MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
                        throw IOException(context.stringResource(MR.strings.invalid_backup_file_json))
                    }
                    else -> source
                }

                while (true) {
                    val tag = orInvalid { readVarint(payloadSource) } ?: break
                    val wireType = (tag and 0x07).toInt()
                    val fieldNumber = (tag ushr 3).toInt()

                    when (wireType) {
                        WIRE_TYPE_LENGTH_DELIMITED -> {
                            val length = orInvalid { readVarint(payloadSource) }
                                ?: throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
                            if (length > Int.MAX_VALUE) {
                                throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
                            }
                            val bytes = orInvalid { payloadSource.readByteArray(length) }
                            // The same mapping Mihon's whole-file BackupDecoder made for a bad entry.
                            try {
                                onField(fieldNumber, bytes)
                            } catch (_: SerializationException) {
                                throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
                            }
                        }
                        WIRE_TYPE_VARINT -> {
                            orInvalid { readVarint(payloadSource) }
                                ?: throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
                        }
                        WIRE_TYPE_64BIT -> orInvalid { payloadSource.skip(8) }
                        WIRE_TYPE_32BIT -> orInvalid { payloadSource.skip(4) }
                        else -> throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
                    }
                }
            }
        }
    }

    // A file cut short (EOF) or failing its gzip check, as Mihon's BackupDecoder.orInvalid reports it. Only around
    // reading, never onField, so the JSON error and a failure in the caller keep their own messages.
    private inline fun <T> orInvalid(block: () -> T): T {
        return try {
            block()
        } catch (e: IOException) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown), e)
        }
    }

    private fun readVarint(source: okio.BufferedSource): Long? {
        if (source.exhausted()) return null

        var shift = 0
        var result = 0L
        while (shift < 64) {
            val b = source.readByte().toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) {
                return result
            }
            shift += 7
        }
        throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
    }

    companion object {
        private const val WIRE_TYPE_VARINT = 0
        private const val WIRE_TYPE_64BIT = 1
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2
        private const val WIRE_TYPE_32BIT = 5
        private const val GZIP_MAGIC = 0x1f8b
        private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d // "{}"
        private const val MAGIC_JSON_SIGNATURE2 = 0x7b22 // "{\""
        private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a // "{\n"
    }
}
