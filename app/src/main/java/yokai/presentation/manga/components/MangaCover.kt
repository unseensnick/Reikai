package yokai.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.manga.MangaCoverMetadata
import yokai.util.rememberResourceBitmapPainter
import yokai.domain.manga.models.MangaCover as MangaCoverModel

@Composable
fun MangaCover(
    data: Any?,
    modifier: Modifier = Modifier,
    ratio: Float? = null,
    contentDescription: String = "",
    shape: Shape = RoundedCornerShape(12.dp),
    contentScale: ContentScale = ContentScale.Crop,
    onClick: (() -> Unit)? = null,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    // When the caller hasn't pinned a specific ratio (e.g., the staggered grid path which wants
    // covers to render at their intrinsic aspect after load), fall back to the cached per-cover
    // ratio from [MangaCoverMetadata]. On the very first paint of a never-seen cover, fall back
    // again to BOOK (2:3). Without this fallback the AsyncImage placeholder reports a 0
    // intrinsic size and the wrapping cell collapses to nothing, leaving a phantom empty cell
    // until Coil reports the loaded image size. Once the image loads, [onSuccess] writes the
    // real ratio back so future renders use it.
    val coverMangaId = (data as? MangaCoverModel)?.mangaId
    val effectiveRatio = ratio ?: MangaCoverMetadata.getRatio(coverMangaId) ?: MangaCoverRatio.BOOK
    AsyncImage(
        model = data,
        placeholder = ColorPainter(Color(0x1F888888)),
        error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
        contentDescription = contentDescription,
        contentScale = contentScale,
        onLoading = { state -> onState?.invoke(state) },
        onSuccess = { state ->
            onState?.invoke(state)
            // Persist the cover's intrinsic aspect ratio so subsequent renders can apply it as
            // the cell's stable height BEFORE Coil reports an intrinsic size on the next load.
            // This is what unblocks the "uniform off + regular grid" path: without a cached
            // ratio the cell collapses to 0 height while the AsyncImage's placeholder is in
            // place, and LazyVerticalGrid stretches the row to other cells' heights, leaving a
            // phantom empty cell. Matches the legacy `MangaCoverMetadata` cache that
            // `LibraryGridHolder.setFreeformCoverRatio` reads from.
            (data as? MangaCoverModel)?.mangaId?.let { mid ->
                val size = state.painter.intrinsicSize
                if (size.isSpecified && size != Size.Zero && size.height > 0f) {
                    MangaCoverMetadata.addCoverRatio(mid, size.width / size.height)
                }
            }
        },
        onError = { state -> onState?.invoke(state) },
        modifier = modifier
            .aspectRatio(effectiveRatio)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
    )
}

object MangaCoverRatio {
    val SQUARE = 1f / 1f
    val BOOK = 2f / 3f
}
