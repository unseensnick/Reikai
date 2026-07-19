package reikai.presentation.recommendation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.GRID_SELECTED_COVER_ALPHA
import eu.kanade.presentation.library.components.GridItemSelectable
import eu.kanade.presentation.library.components.GridItemTitle
import eu.kanade.presentation.library.components.MangaGridCover
import eu.kanade.presentation.manga.components.MangaCover
import reikai.domain.recommendation.RecommendationOrigin

/**
 * A recommendation cover cell for the details carousel and the "See all" grid: the shared library cover,
 * selection and in-library badge, with the origin as an eyebrow between the cover and the title.
 *
 * Composed from the library grid item's own internal pieces so it matches that look, but keeps the origin
 * snug: a caption below the fixed two-line title leaves a visible gap under a one-line title, whereas an
 * eyebrow above it sits flush under the cover and the two-line title stays uniform. [showOrigin] is false
 * in the grouped grid view, where a section header names the origin instead.
 */
@Composable
fun RecommendationGridItem(
    coverData: Any,
    title: String,
    origin: RecommendationOrigin,
    inLibrary: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    showOrigin: Boolean = true,
    titleMaxLines: Int = 2,
) {
    val coverAlpha = if (inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f
    GridItemSelectable(
        modifier = modifier,
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
                    )
                },
                badgesStart = { InLibraryBadge(enabled = inLibrary) },
            )
            if (showOrigin) {
                RecommendationOriginCaption(
                    origin = origin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            GridItemTitle(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                title = title,
                style = MaterialTheme.typography.titleSmall,
                minLines = 2,
                maxLines = titleMaxLines,
            )
        }
    }
}
