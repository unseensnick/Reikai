package reikai.storage

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import reikai.util.openTruncatingOutputStream
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import java.io.InputStream

/**
 * The cover a user put in a local series' folder: the first file named `cover`, in any case and with any
 * extension, that is a picture. The manga and novel local sources both read it here.
 */
fun Iterable<UniFile>.localCover(): UniFile? =
    filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
        .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }

/**
 * Writes [input] as the cover of the local series in [folder], over its [localCover] or as a new `cover.jpg`,
 * and keeps the folder out of the gallery. Written from empty, since a document provider writes plain "w" in
 * place and a smaller picture kept the old one's tail. The manga and novel local sources both write it here.
 * Null when no file could be made.
 */
fun writeLocalCover(context: Context, folder: UniFile, input: InputStream): UniFile? = input.use { source ->
    val target = folder.listFiles().orEmpty().asIterable().localCover()
        ?: folder.createFile(DEFAULT_COVER_NAME)
        ?: return null
    target.openTruncatingOutputStream(context).use { source.copyTo(it) }
    DiskUtil.createNoMediaFile(folder, context)
    target
}

private const val DEFAULT_COVER_NAME = "cover.jpg"
