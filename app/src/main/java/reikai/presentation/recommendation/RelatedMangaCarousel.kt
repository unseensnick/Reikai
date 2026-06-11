package reikai.presentation.recommendation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import reikai.domain.recommendation.RelatedMangaCandidate
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Horizontal "Related" carousel on the manga details screen: a row of cover cards ranked by the
 * recommendation engine, with a skeleton while loading and an in-library badge on titles already
 * saved. Renders nothing when there's no result and nothing loading.
 */
@Composable
fun RelatedMangaCarousel(
    items: List<MangaScreenModel.RelatedMangaItem>,
    loading: Boolean,
    onClick: (RelatedMangaCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty() && !loading) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.related_mangas),
                style = MaterialTheme.typography.titleSmall,
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        if (items.isEmpty()) {
            // Loading with nothing yet: a skeleton row so the section doesn't pop in abruptly.
            Row(
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                repeat(4) { SkeletonCard() }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                items(items, key = { it.candidate.manga.url }) { item ->
                    Box(modifier = Modifier.width(96.dp)) {
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
                            coverBadgeStart = { InLibraryBadge(enabled = item.inLibrary) },
                            coverAlpha = if (item.inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                            onClick = { onClick(item.candidate) },
                            onLongClick = { onClick(item.candidate) },
                        )
                    }
                }
            }
        }

        // Separates the carousel from the chapter list below it (matches Komikku's suggestions row).
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.small))
    }
}

@Composable
private fun SkeletonCard() {
    Box(
        modifier = Modifier
            .width(96.dp)
            .padding(4.dp)
            .aspectRatio(MangaCoverAspectRatio)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

private const val MangaCoverAspectRatio = 2f / 3f
