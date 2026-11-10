package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val size = pfd.statSize
    private val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = ArchiveInputStream(address, size).use {
        block(generateSequence { it.getNextEntry() })
    }

    // RK --> several entries in one pass, since getInputStream scans from the first entry each call
    fun readEntries(names: Set<String>): Map<String, ByteArray> = ArchiveInputStream(address, size).use { archive ->
        val found = HashMap<String, ByteArray>()
        while (found.size < names.size) {
            val entry = archive.getNextEntry() ?: break
            // A zip written on Windows can name `META-INF\container.xml`; a name asked for as listed still matches.
            val name = entry.name.takeIf { it in names } ?: entry.name.replace('\\', '/')
            if (entry.isFile && name in names) found[name] = archive.readBytes()
        }
        found
    }
    // RK <--

    fun getInputStream(entryName: String): InputStream? {
        val archive = ArchiveInputStream(address, size)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    override fun close() {
        Os.munmap(address, size)
    }
}
