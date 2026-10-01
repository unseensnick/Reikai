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
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.plus

/**
 * Cover grid for the "See all" browse screen, drawing the model's sections in order: a section with an
 * origin gets a full-width header (the grouped view), one without is the flat taste-ranked grid.
 * Selection rendering rides on [RecommendationGridItem]'s `isSelected`.
 */
@Composable
fun RelatedMangasBrowseContent(
    sections: List<RelatedMangasBrowseViewModel.Section>,
    columns: GridCells,
    selectedUrls: Set<String>,
    contentPadding: PaddingValues,
    onItemClick: (RelatedMangasBrowseViewModel.BrowseItem) -> Unit,
    onItemLongClick: (RelatedMangasBrowseViewModel.BrowseItem) -> Unit,
) {
    FastScrollLazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        // Start the scroll thumb below the app bar instead of behind it.
        topContentPadding = contentPadding.calculateTopPadding(),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        sections.forEach { section ->
            section.origin?.let { origin ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "header-$origin") {
                    GroupHeader(originLabel(origin))
                }
            }
            items(section.items, key = { it.candidate.manga.url }) { item ->
                BrowseGridItem(
                    item,
                    item.candidate.manga.url in selectedUrls,
                    showOrigin = section.origin == null,
                    onItemClick,
                    onItemLongClick,
                )
            }
        }
    }
}

@Composable
private fun BrowseGridItem(
    item: RelatedMangasBrowseViewModel.BrowseItem,
    isSelected: Boolean,
    // Flat view labels each card's origin; the grouped view shows it in the section header instead.
    showOrigin: Boolean,
    onItemClick: (RelatedMangasBrowseViewModel.BrowseItem) -> Unit,
    onItemLongClick: (RelatedMangasBrowseViewModel.BrowseItem) -> Unit,
) {
    RecommendationGridItem(
        candidate = item.candidate,
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
