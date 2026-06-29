package reikai.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.ContinueReadingButton
import eu.kanade.presentation.library.components.ContinueReadingButtonGridPadding
import eu.kanade.presentation.library.components.ContinueReadingButtonIconSizeLarge
import eu.kanade.presentation.library.components.ContinueReadingButtonSizeLarge
import eu.kanade.presentation.library.components.GRID_SELECTED_COVER_ALPHA
import eu.kanade.presentation.library.components.GridItemSelectable
import eu.kanade.presentation.library.components.GridItemTitle
import eu.kanade.presentation.library.components.MangaGridCover
import eu.kanade.presentation.manga.components.MangaCover

/**
 * Comfortable grid cell for the panorama display mode. Identical to Mihon's
 * [eu.kanade.presentation.library.components.MangaComfortableGridItem] (same Book-ratio cell, so cell
 * heights stay uniform and the single-list fast-scroller is undisturbed), except a wide cover is shown
 * whole with [ContentScale.Fit] instead of cropped. "Wide" is decided per cell from the cover's real
 * aspect ratio, measured at image load (no stored metadata), mirroring Komikku's library behavior.
 */
@Composable
fun ReikaiComfortableGridPanoramaItem(
    coverData: Any, // Any (not MangaCover) so reikai.data.coil.NovelCover renders here too
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    titleMaxLines: Int = 2,
    coverAlpha: Float = 1f,
    coverBadgeStart: (@Composable RowScope.() -> Unit)? = null,
    coverBadgeEnd: (@Composable RowScope.() -> Unit)? = null,
    onClickContinueReading: (() -> Unit)? = null,
) {
    val coverRatio = remember { mutableFloatStateOf(1f) }
    val coverIsWide = coverRatio.floatValue <= RATIO_SWITCH_TO_PANORAMA
    GridItemSelectable(
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column {
            MangaGridCover(
                cover = {
                    MangaCover.Book(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha),
                        data = coverData,
                        scale = if (coverIsWide) ContentScale.Fit else ContentScale.Crop,
                        onSuccess = {
                            val image = it.result.image
                            coverRatio.floatValue = image.height.toFloat() / image.width
                        },
                    )
                },
                badgesStart = coverBadgeStart,
                badgesEnd = coverBadgeEnd,
                content = {
                    if (onClickContinueReading != null) {
                        ContinueReadingButton(
                            size = ContinueReadingButtonSizeLarge,
                            iconSize = ContinueReadingButtonIconSizeLarge,
                            onClick = onClickContinueReading,
                            modifier = Modifier
                                .padding(ContinueReadingButtonGridPadding)
                                .align(Alignment.BottomEnd),
                        )
                    }
                },
            )
            GridItemTitle(
                modifier = Modifier.padding(4.dp),
                title = title,
                style = MaterialTheme.typography.titleSmall,
                minLines = 2,
                maxLines = titleMaxLines,
            )
        }
    }
}

/** A cover at or below this height/width ratio is "wide" enough to letterbox (Komikku's value). */
private const val RATIO_SWITCH_TO_PANORAMA = 0.75f
