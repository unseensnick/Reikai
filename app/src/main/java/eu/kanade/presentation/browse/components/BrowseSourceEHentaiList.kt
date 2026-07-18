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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.manga.components.MangaCover
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.util.SourceTagsUtil
import exh.util.SourceTagsUtil.GenreColor
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji.Companion.getEmojiLangFlag
import tachiyomi.presentation.core.util.selectedBackground
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData

@Composable
fun BrowseSourceEHentaiList(
    // pair carries the gallery metadata; the row renders rating / category / pages from .second.
    mangaList: LazyPagingItems<StateFlow<Pair<Manga, RaisedSearchMetadata?>>>,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // RK: highlighted when in bulk-selection mode
    selection: List<Manga> = emptyList(),
) {
    LazyColumn(
        contentPadding = contentPadding,
    ) {
        item {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val pair by mangaList[index]?.collectAsState() ?: return@items
            val manga = pair.first
            val metadata = pair.second

            BrowseSourceEHentaiListItem(
                manga = manga,
                metadata = metadata,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
                isSelected = selection.fastAny { it.id == manga.id },
            )
        }

        item {
            if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
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

    val context = LocalContext.current
    val coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f

    val languageText by produceState("", metadata) {
        value = withIOContext {
            val locale = metadata.tags
                .filter { it.namespace == EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE }
                .firstNotNullOfOrNull { SourceTagsUtil.getLocaleSourceUtil(it.name) }
            val pageCount = metadata.length
            when {
                locale != null && pageCount != null -> context.pluralStringResource(
                    MR.plurals.browse_language_and_pages,
                    pageCount,
                    pageCount,
                    getEmojiLangFlag(locale.toLanguageTag()),
                )
                pageCount != null -> context.pluralStringResource(MR.plurals.num_pages, pageCount, pageCount)
                else -> locale?.toLanguageTag()?.let { getEmojiLangFlag(it) }.orEmpty()
            }
        }
    }
    val datePosted by produceState("", metadata) {
        value = withIOContext {
            runCatching {
                metadata.datePosted?.let {
                    MetadataUtil.EX_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                }
            }.getOrNull().orEmpty()
        }
    }
    val genre by produceState<Pair<GenreColor, StringResource>?>(null, metadata) {
        value = withIOContext {
            when (metadata.genre) {
                "doujinshi" -> GenreColor.DOUJINSHI_COLOR to MR.strings.doujinshi
                "manga" -> GenreColor.MANGA_COLOR to MR.strings.content_type_manga
                "artistcg" -> GenreColor.ARTIST_CG_COLOR to MR.strings.artist_cg
                "gamecg" -> GenreColor.GAME_CG_COLOR to MR.strings.game_cg
                "western" -> GenreColor.WESTERN_COLOR to MR.strings.western
                "non-h" -> GenreColor.NON_H_COLOR to MR.strings.non_h
                "imageset" -> GenreColor.IMAGE_SET_COLOR to MR.strings.image_set
                "cosplay" -> GenreColor.COSPLAY_COLOR to MR.strings.cosplay
                "asianporn" -> GenreColor.ASIAN_PORN_COLOR to MR.strings.asian_porn
                "misc" -> GenreColor.MISC_COLOR to MR.strings.misc
                else -> null
            }
        }
    }
    val rating by produceState(0f, metadata) {
        value = withIOContext {
            // Round down to the nearest half star, matching the site's display.
            metadata.averageRating?.toFloat()?.div(0.5f)?.let { floor(it) }?.let { 0.5f * it } ?: 0f
        }
    }

    Row(
        modifier = Modifier
            .height(148.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // RK: highlight the row while bulk-selecting
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
                    RatingStars(rating = rating)

                    val badgeColor = genre?.first?.color?.let { Color(it) }
                    // contrast-derive the label color from the badge background so it stays
                    // readable on every genre color, in either app theme.
                    val textColor = badgeColor
                        ?.let { if (it.luminance() > 0.5f) Color.Black else Color.White }
                        ?: Color.Unspecified
                    val res = genre?.second
                    Card(
                        colors = if (badgeColor != null) {
                            CardDefaults.cardColors(containerColor = badgeColor)
                        } else {
                            CardDefaults.cardColors()
                        },
                    ) {
                        Text(
                            text = res?.let { stringResource(it) } ?: metadata.genre.orEmpty(),
                            color = textColor,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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

@Composable
private fun RatingStars(
    rating: Float,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (star in 1..5) {
            val icon = when {
                rating >= star -> Icons.Filled.Star
                rating >= star - 0.5f -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
