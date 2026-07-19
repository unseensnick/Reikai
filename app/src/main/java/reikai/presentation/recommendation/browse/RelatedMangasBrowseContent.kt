package reikai.presentation.recommendation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import reikai.presentation.recommendation.RecommendationGridItem
import reikai.presentation.recommendation.originLabel
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.plus

/**
 * Cover grid for the "See all" browse screen. Flat taste-ranked by default; when [grouped] is on it
 * inserts full-width section headers per candidate origin (data already on each
 * candidate, no new fetching). Selection rendering rides on [MangaComfortableGridItem.isSelected].
 */
@Composable
fun RelatedMangasBrowseContent(
    items: List<RelatedMangasBrowseScreenModel.BrowseItem>,
    columns: GridCells,
    selectedUrls: Set<String>,
    grouped: Boolean,
    contentPadding: PaddingValues,
    onItemClick: (RelatedMangasBrowseScreenModel.BrowseItem) -> Unit,
    onItemLongClick: (RelatedMangasBrowseScreenModel.BrowseItem) -> Unit,
) {
    FastScrollLazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        // Start the scroll thumb below the app bar instead of behind it.
        topContentPadding = contentPadding.calculateTopPadding(),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (grouped) {
            items.groupBy { it.candidate.origin }.forEach { (origin, groupItems) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "header-$origin") {
                    GroupHeader(originLabel(origin))
                }
                items(groupItems, key = { it.candidate.manga.url }) { item ->
                    BrowseGridItem(
                        item,
                        item.candidate.manga.url in selectedUrls,
                        showOrigin = false,
                        onItemClick,
                        onItemLongClick,
                    )
                }
            }
        } else {
            items(items, key = { it.candidate.manga.url }) { item ->
                BrowseGridItem(
                    item,
                    item.candidate.manga.url in selectedUrls,
                    showOrigin = true,
                    onItemClick,
                    onItemLongClick,
                )
            }
        }
    }
}

@Composable
private fun BrowseGridItem(
    item: RelatedMangasBrowseScreenModel.BrowseItem,
    isSelected: Boolean,
    // Flat view labels each card's origin; the grouped view shows it in the section header instead.
    showOrigin: Boolean,
    onItemClick: (RelatedMangasBrowseScreenModel.BrowseItem) -> Unit,
    onItemLongClick: (RelatedMangasBrowseScreenModel.BrowseItem) -> Unit,
) {
    RecommendationGridItem(
        coverData = MangaCover(
            mangaId = 0L,
            sourceId = item.candidate.sourceId,
            isMangaFavorite = item.inLibrary,
            url = item.candidate.manga.thumbnail_url,
            lastModified = 0L,
        ),
        title = item.candidate.manga.title,
        origin = item.candidate.origin,
        inLibrary = item.inLibrary,
        isSelected = isSelected,
        showOrigin = showOrigin,
        titleMaxLines = 2,
        onClick = { onItemClick(item) },
        onLongClick = { onItemLongClick(item) },
    )
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.small, vertical = MaterialTheme.padding.small),
    )
}
