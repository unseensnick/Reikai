package eu.kanade.presentation.browse.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.manga.components.GenreBadge
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.RatingStars
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.util.SourceTagsUtil
import reikai.presentation.browse.catalogue.EntryBrowseRow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import java.time.Instant
import java.time.ZoneId
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData

@Composable
fun BrowseSourceEHentaiList(
    // Neutral catalogue rows. The manga adapter is the only one that asks for this layout, so its
    //     payload is always the live (manga, gallery metadata) pair the rows render rating and pages from.
    rows: LazyPagingItems<EntryBrowseRow>,
    contentPadding: PaddingValues,
    selectedKeys: Set<String>,
    onClick: (EntryBrowseRow) -> Unit,
    onLongClick: (EntryBrowseRow) -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding,
    ) {
        item {
            if (rows.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = rows.itemCount) { index ->
            val row = rows[index] ?: return@items
            val content by row.content.collectAsState()
            val (manga, metadata) = content.payload as Pair<*, *>

            BrowseSourceEHentaiListItem(
                manga = manga as Manga,
                metadata = metadata as RaisedSearchMetadata?,
                onClick = { onClick(row) },
                onLongClick = { onLongClick(row) },
                isSelected = row.key in selectedKeys,
            )
        }

        item {
            if (rows.loadState.refresh is LoadState.Loading || rows.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceEHentaiListItem(
    manga: Manga,
    metadata: RaisedSearchMetadata?,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    isSelected: Boolean = false,
) {
    if (metadata !is EHentaiSearchMetadata) return

    val coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f

    val flag = remember(metadata) { SourceTagsUtil.ehLanguageFlag(metadata) }
    val pageCount = metadata.length
    val languageText = when {
        flag != null && pageCount != null ->
            pluralStringResource(MR.plurals.browse_language_and_pages, pageCount, pageCount, flag)
        pageCount != null -> pluralStringResource(MR.plurals.num_pages, pageCount, pageCount)
        else -> flag.orEmpty()
    }
    val datePosted = remember(metadata) {
        runCatching {
            metadata.datePosted?.let {
                MetadataUtil.EX_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
            }
        }.getOrNull().orEmpty()
    }
    val genre = remember(metadata) { SourceTagsUtil.ehGenre(metadata.genre) }

    Row(
        modifier = Modifier
            .height(148.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // Highlight the row while bulk-selecting
            .selectedBackground(isSelected)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MangaCover.Book(
                modifier = Modifier
                    .fillMaxHeight()
                    .alpha(coverAlpha),
                data = MangaCoverData(
                    mangaId = manga.id,
                    sourceId = manga.source,
                    isMangaFavorite = manga.favorite,
                    url = manga.thumbnailUrl,
                    lastModified = manga.coverLastModified,
                ),
            )
            if (manga.favorite) {
                BadgeGroup(
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopStart),
                ) {
                    InLibraryBadge(enabled = true)
                }
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = manga.title,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    overflow = TextOverflow.Ellipsis,
                )
                metadata.uploader?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    horizontalAlignment = Alignment.Start,
                ) {
                    RatingStars(rating = metadata.averageRating?.toFloat() ?: 0f, starSize = 18.dp)
                    GenreBadge(
                        color = genre?.first,
                        label = genre?.second?.let { stringResource(it) } ?: metadata.genre.orEmpty(),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(languageText, maxLines = 1, fontSize = 14.sp)
                    Text(datePosted, maxLines = 1, fontSize = 14.sp)
                }
            }
        }
    }
}
