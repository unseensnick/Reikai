package reikai.presentation.components

import android.content.Context
import android.net.Uri
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
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
 * Saves the picture coil loads for [model] as [name]: to Pictures, or with [forSharing] to the cache a share
 * reads from. Null when nothing loads. What [PictureViewerDialog]'s Save and Share run, for a cover and a
 * chapter's picture alike.
 */
suspend fun savePicture(
    context: Context,
    imageSaver: ImageSaver,
    model: Any,
    name: String,
    forSharing: Boolean,
): Uri? {
    val request = ImageRequest.Builder(context)
        .data(model)
        .size(Size.ORIGINAL)
        .build()
    return withIOContext {
        val result = context.imageLoader.execute(request).image?.asDrawable(context.resources)
        val bitmap = result?.getBitmapOrNull() ?: return@withIOContext null
        // A PNG when the picture has transparency, which a Cover's fixed JPEG turns black. ImageSaver
        // names a Page's file by the type it reads from the bytes.
        val bytes = coverPicture(bitmap).bytes
        imageSaver.save(
            Image.Page(
                inputStream = { ByteArrayInputStream(bytes) },
                name = name,
                location = if (forSharing) Location.Cache else Location.Pictures.create(),
            ),
        )
    }
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
    forSharing: Boolean,
    then: suspend (Uri?) -> Unit,
): Job = launchOutliving(
    appScope,
    write = {
        try {
            savePicture(context, imageSaver, model, name, forSharing)
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
