// RK: net-new. Writes a single top-level protobuf field (write side of BackupProtoReader) so the
// backup can be streamed field by field instead of encoding the whole Backup at once, which OOMs on
// large libraries (Issue #53). Extracted from BackupCreator so the hand-rolled wire framing is unit
// tested directly (a wrong field number or varint corrupts every backup).
package eu.kanade.tachiyomi.data.backup.create

import java.io.OutputStream

internal object BackupProtoWriter {

    /** Write [data] as the length-delimited (wire type 2) field [fieldNumber]. */
    fun writeField(out: OutputStream, fieldNumber: Int, data: ByteArray) {
        // Tag = (fieldNumber << 3) | 2 (length-delimited)
        writeVarint(out, (fieldNumber.toLong() shl 3) or 2L)
        writeVarint(out, data.size.toLong())
        out.write(data)
    }

    /** Write [value] as a protobuf base-128 varint. */
    private fun writeVarint(out: OutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }
}
