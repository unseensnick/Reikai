package eu.kanade.presentation.browse.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.manga.components.GenreBadge
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.relativeTimeSpanString
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.util.SourceTagsUtil
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Group
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.roundedfilled.MenuBook
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.icons.Star
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import kotlin.math.round
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

/**
 * A gallery laid out like a list display item, with the facts its details page shows: title, artist and circle,
 * uploader, then category and language, then rating, pages and age. Ported from TachiyomiSY's redesign.
 */
@Composable
private fun BrowseSourceEHentaiListItem(
    manga: Manga,
    metadata: RaisedSearchMetadata?,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    isSelected: Boolean = false,
) {
    if (metadata !is EHentaiSearchMetadata) return

    val genre = remember(metadata) { SourceTagsUtil.ehGenre(metadata.genre) }
    val artist = remember(metadata) { metadata.tagNames(ARTIST_NAMESPACE) }
    val circle = remember(metadata) { metadata.tagNames(GROUP_NAMESPACE) }
    // From the tags, which a browse result carries before its details are fetched, unlike metadata.language.
    val language = remember(metadata) {
        metadata.tagNames(EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE, LANGUAGE_FLAGS, capitalize = true)
    }
    val translated = remember(metadata) {
        metadata.tags.any { it.namespace == EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE && it.name == TRANSLATED }
    }

    Row(
        modifier = Modifier
            .height(156.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // Highlight the row while bulk-selecting
            .selectedBackground(isSelected)
            .padding(8.dp),
    ) {
        Box(modifier = Modifier.fillMaxHeight()) {
            MangaCover.Book(
                modifier = Modifier
                    .fillMaxHeight()
                    .alpha(if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f),
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
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = manga.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (artist != null || circle != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        artist?.let {
                            GalleryFact(it, MaterialSymbols.Rounded.Palette, Modifier.weight(1f, fill = false))
                        }
                        circle?.let {
                            GalleryFact(it, MaterialSymbols.Rounded.Group, Modifier.weight(1f, fill = false))
                        }
                    }
                }
                GalleryUploader(metadata.uploader)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GenreBadge(
                        color = genre?.first,
                        label = genre?.second?.let { stringResource(it) }
                            ?: metadata.genre
                            ?: stringResource(MR.strings.unknown),
                    )
                    language?.let {
                        GalleryFact(
                            text = SourceTagsUtil.ehLanguageLabel(
                                metadata,
                                it,
                                stringResource(MR.strings.translated).takeIf { translated },
                            ),
                            icon = MaterialSymbols.Rounded.Translate,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val rating = metadata.averageRating
                    val length = metadata.length
                    val posted = metadata.datePosted
                    rating?.let { GalleryRating(it) }
                    length?.let {
                        if (rating != null) FactSeparator()
                        GalleryFact(
                            text = pluralStringResource(MR.plurals.num_pages, it, it),
                            icon = MaterialSymbols.RoundedFilled.MenuBook,
                        )
                    }
                    posted?.let {
                        if (rating != null || length != null) FactSeparator()
                        GalleryFact(relativeTimeSpanString(it))
                    }
                }
            }
        }
    }
}

/** One fact about the gallery, after its icon when it has one. */
@Composable
private fun GalleryFact(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The uploader in the accent colour, muted when E-Hentai reports none or a disowned gallery. */
@Composable
private fun GalleryUploader(uploader: String?) {
    val known = uploader != null && !uploader.equals(DISOWNED_UPLOADER, ignoreCase = true)
    val color = if (known) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Person,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        Text(
            text = uploader ?: stringResource(MR.strings.unknown),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (known) FontWeight.Medium else null,
            color = color,
        )
    }
}

@Composable
private fun GalleryRating(rating: Double) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ReikaiIcons.Star,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = (round(rating * 10.0) / 10.0).toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FactSeparator() {
    Text(
        text = "·",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The names of the gallery's tags in [namespace] but not [excluded], joined, or null when it has none. */
private fun EHentaiSearchMetadata.tagNames(
    namespace: String,
    excluded: Set<String> = emptySet(),
    capitalize: Boolean = false,
): String? = tags
    .filter { it.namespace == namespace && it.name !in excluded }
    .ifEmpty { null }
    ?.joinToString { tag -> if (capitalize) tag.name.replaceFirstChar { it.uppercase() } else tag.name }

private const val ARTIST_NAMESPACE = "artist"
private const val GROUP_NAMESPACE = "group"
private const val TRANSLATED = "translated"

/** Language tags that describe the translation rather than name a language. */
private val LANGUAGE_FLAGS = setOf(TRANSLATED, "rewrite", "speechless", "text cleaned")

/** What E-Hentai reports as the uploader once the uploader has disowned the gallery. */
private const val DISOWNED_UPLOADER = "(Disowned)"
