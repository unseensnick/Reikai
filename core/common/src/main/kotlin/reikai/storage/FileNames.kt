package reikai.storage

import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil

/**
 * [stem] made a valid file name and cut so [suffix] (an extension, a number, a hash) always fits after it,
 * counted in bytes as the limit is: cutting the whole name dropped the extension of a long one.
 */
fun validFileName(stem: String, suffix: String): String =
    DiskUtil.buildValidFilename(stem, DiskUtil.MAX_FILE_NAME_BYTES - suffix.byteSize()) + suffix
