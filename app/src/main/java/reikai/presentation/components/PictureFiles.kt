package reikai.presentation.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.getSystemService
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.novel.export.coverPicture
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream

/**
 * The folder under Pictures a reader saves a page or a chapter's picture of [seriesTitle] into: the series'
 * own when [perSeries] (Save pages into separate folders) is on, else none. Both readers read this.
 */
fun savedPictureFolder(seriesTitle: String?, perSeries: Boolean): String =
    if (perSeries && seriesTitle != null) DiskUtil.buildValidFilename(seriesTitle) else ""

/**
 * Saves the picture coil loads for [model] as [name]: to [folder] under Pictures, or with [forSharing] to the
 * cache a share reads from. Null when nothing loads. What [PictureViewerDialog]'s Save and Share run, for a
 * cover and a chapter's picture alike.
 */
suspend fun savePicture(
    context: Context,
    imageSaver: ImageSaver,
    model: Any,
    name: String,
    folder: String,
    forSharing: Boolean,
): Uri? {
    val bytes = pictureBytes(context, model) ?: return null
    return withIOContext {
        imageSaver.save(
            Image.Page(
                inputStream = { ByteArrayInputStream(bytes) },
                name = name,
                location = if (forSharing) Location.Cache else Location.Pictures.create(folder),
            ),
        )
    }
}

/**
 * The picture coil loads for [model], encoded as a file: a PNG when it has transparency, which a Cover's
 * fixed JPEG turns black. Null when nothing loads. What a picture's Save, Share and Set as cover write.
 */
suspend fun pictureBytes(context: Context, model: Any): ByteArray? {
    val request = ImageRequest.Builder(context)
        .data(model)
        .size(Size.ORIGINAL)
        .build()
    return withIOContext {
        val result = context.imageLoader.execute(request).image?.asDrawable(context.resources)
        result?.getBitmapOrNull()?.let { coverPicture(it).bytes }
    }
}

/** Puts the saved picture at [uri] on the clipboard, as a manga page's Copy to clipboard does. */
fun Context.copyPictureToClipboard(uri: Uri) {
    val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
    clipboardManager.setPrimaryClip(ClipData.newUri(applicationContext.contentResolver, "", uri))
}

/**
 * [savePicture] on [appScope], so closing the viewer mid-save does not drop the file, then [then] on this scope with
 * the saved file, or null when nothing was saved, which a caller reports as a failure. What every picture viewer's
 * Save and Share run.
 */
fun CoroutineScope.savePictureOutliving(
    appScope: CoroutineScope,
    context: Context,
    imageSaver: ImageSaver,
    model: Any,
    name: String,
    folder: String,
    forSharing: Boolean,
    then: suspend (Uri?) -> Unit,
): Job = launchOutliving(
    appScope,
    write = {
        try {
            savePicture(context, imageSaver, model, name, folder, forSharing)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            null
        }
    },
    then = then,
)

/**
 * Runs [write] on [appScope] and only [then], the snackbar or the share, on this scope, as
 * `ReaderViewModel.saveImage` splits a page's save.
 */
internal fun <T> CoroutineScope.launchOutliving(
    appScope: CoroutineScope,
    write: suspend CoroutineScope.() -> T,
    then: suspend (T) -> Unit,
): Job = launch { then(appScope.async { write() }.await()) }
