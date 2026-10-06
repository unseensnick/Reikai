package reikai.presentation.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import coil3.annotation.ExperimentalCoilApi
import coil3.asDrawable
import coil3.executeBlocking
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.transform.RoundedCornersTransformation
import tachiyomi.presentation.widget.R

/**
 * A widget cover cell's bitmap, decoded to [widthPx] x [heightPx]. Mihon's updates widget and the
 * combined widget both draw through here, so a sync that changes upstream's request in
 * `BaseUpdatesGridGlanceWidget.prepareData` changes this instead.
 */
@OptIn(ExperimentalCoilApi::class)
fun loadWidgetCover(context: Context, data: Any, widthPx: Int, heightPx: Int): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(data)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .precision(Precision.EXACT)
        .size(widthPx, heightPx)
        .scale(Scale.FILL)
        .let {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val roundPx = context.resources.getDimension(R.dimen.appwidget_inner_radius)
                it.transformations(RoundedCornersTransformation(roundPx))
            } else {
                it // Handled by system
            }
        }
        .build()
    return context.imageLoader.executeBlocking(request)
        .image
        ?.asDrawable(context.resources)
        ?.toBitmap()
}
