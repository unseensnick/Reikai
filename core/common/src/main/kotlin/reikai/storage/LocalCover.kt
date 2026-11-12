package reikai.storage

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import java.io.InputStream

/**
 * The cover a user put in a local series' folder: the first file named `cover`, in any case and with any
 * extension, that is a picture. One still in flight is not: a killed write leaves `cover.jpg_tmp`, which reads as
 * "cover" and sniffs as a picture, and taking it would aim every later write at it. Both local sources read it here.
 */
fun Iterable<UniFile>.localCover(): UniFile? =
    filter {
        it.isFile && !it.name.orEmpty().endsWith(IN_FLIGHT_SUFFIX) &&
            it.nameWithoutExtension.equals("cover", ignoreCase = true)
    }
        .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }

/**
 * Writes [input] as the cover of the local series in [folder], in place of its [localCover] or as a new
 * `cover.jpg`, through [replace], so the user's own cover is never truncated in place and a failed write keeps it.
 * Keeps the folder out of the gallery. The manga and novel local sources both write it here.
 */
fun writeLocalCover(context: Context, folder: UniFile, input: InputStream): UniFile = input.use { source ->
    val listed = folder.list(context)
    val name = listed.localCover()?.name ?: DEFAULT_COVER_NAME
    val written = folder.replace(context, listed, name) { source.copyTo(it) }
    DiskUtil.createNoMediaFile(folder, context)
    written
}

private const val DEFAULT_COVER_NAME = "cover.jpg"
