package reikai.presentation.recommendation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import reikai.domain.recommendation.RecommendationOrigin
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/**
 * Cover grid for the "See all" browse screen. Flat taste-ranked by default; when [grouped] is on it
 * inserts full-width section headers per candidate [RecommendationOrigin] (data already on each
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
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (grouped) {
            items.groupBy { it.candidate.origin }.forEach { (origin, groupItems) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "header-$origin") {
                    GroupHeader(originLabel(origin))
                }
                items(groupItems, key = { it.candidate.manga.url }) { item ->
                    BrowseGridItem(item, item.candidate.manga.url in selectedUrls, onItemClick, onItemLongClick)
                }
            }
        } else {
            items(items, key = { it.candidate.manga.url }) { item ->
                BrowseGridItem(item, item.candidate.manga.url in selectedUrls, onItemClick, onItemLongClick)
            }
        }
    }
}

@Composable
private fun BrowseGridItem(
    item: RelatedMangasBrowseScreenModel.BrowseItem,
    isSelected: Boolean,
    onItemClick: (RelatedMangasBrowseScreenModel.BrowseItem) -> Unit,
    onItemLongClick: (RelatedMangasBrowseScreenModel.BrowseItem) -> Unit,
) {
    MangaComfortableGridItem(
        title = item.candidate.manga.title,
        titleMaxLines = 3,
        coverData = MangaCover(
            mangaId = 0L,
            sourceId = item.candidate.sourceId,
            isMangaFavorite = item.inLibrary,
            url = item.candidate.manga.thumbnail_url,
            lastModified = 0L,
        ),
        isSelected = isSelected,
        coverBadgeStart = { InLibraryBadge(enabled = item.inLibrary) },
        coverAlpha = if (item.inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
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

@Composable
private fun originLabel(origin: RecommendationOrigin): String = when (origin) {
    is RecommendationOrigin.SourceNative -> stringResource(MR.strings.recs_group_source_native)
    is RecommendationOrigin.Tracker -> stringResource(MR.strings.recs_group_tracker, origin.trackerName)
    is RecommendationOrigin.CrossRec -> stringResource(MR.strings.recs_group_cross_rec, origin.fromTitle)
    is RecommendationOrigin.TagSearch -> stringResource(MR.strings.recs_group_tag_search, origin.tag)
}
