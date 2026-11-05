package reikai.storage

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil

/**
 * The cover a user put in a local series' folder: the first file named `cover`, in any case and with any
 * extension, that is a picture. The manga and novel local sources both read it here.
 */
fun Iterable<UniFile>.localCover(): UniFile? =
    filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
        .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
