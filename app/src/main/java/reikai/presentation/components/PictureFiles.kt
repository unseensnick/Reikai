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
import tachiyomi.core.common.util.lang.withIOContext

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
        imageSaver.save(
            Image.Cover(
                bitmap = bitmap,
                name = name,
                location = if (forSharing) Location.Cache else Location.Pictures.create(),
            ),
        )
    }
}
