package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension

object Archive {

    private val SUPPORTED_ARCHIVE_TYPES = listOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt")

    fun isSupported(file: UniFile): Boolean {
        return isSupported(file.extension) // RK
    }

    // RK: by extension, so the local import names a picked file by the list the source reads
    fun isSupported(extension: String?): Boolean = extension?.lowercase() in SUPPORTED_ARCHIVE_TYPES
}
