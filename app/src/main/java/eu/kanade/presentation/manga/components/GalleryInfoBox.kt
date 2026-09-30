package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.util.SourceTagsUtil
import exh.util.SourceTagsUtil.GenreColor
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Storage
import mihon.icons.materialsymbols.roundedfilled.Bookmark
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.icons.Star
import reikai.presentation.icons.StarBorder
import reikai.presentation.icons.StarHalf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * Per-source gallery-info block shown above the description on adult/metadata galleries (Reikai's
 * Compose-native take on Komikku's *DescriptionAdapter layouts). Borderless and dense, matching the
 * reference: E-Hentai gets a two-column grid (rating + descriptor, size, language, favorites,
 * visible, uploader) with icons; MangaDex gets a rating row; other metadata sources fall back to
 * their [RaisedSearchMetadata.getExtraInfoPairs]. The full field dump is behind the More-info link.
 */
@Composable
fun GalleryInfoBox(
    metadata: RaisedSearchMetadata,
    onMoreInfoClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Skip the block when a source has nothing curated and no non-URL info pairs to list; its
    // namespaced tags still render as chips in the description block regardless.
    val hasInfo = metadata is EHentaiSearchMetadata || metadata is MangaDexSearchMetadata ||
        remember(metadata) { metadata.getExtraInfoPairs(context).any { !it.second.startsWith("http") } }
    if (!hasInfo) return

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (metadata) {
            is EHentaiSearchMetadata -> EHentaiGalleryInfo(metadata, onMoreInfoClick)
            is MangaDexSearchMetadata -> MangaDexGalleryInfo(metadata, onMoreInfoClick)
            else -> {
                GenericGalleryInfo(metadata)
                onMoreInfoClick?.let { MoreInfoLink(it, Modifier.align(Alignment.End)) }
            }
        }
    }
}

@Composable
private fun EHentaiGalleryInfo(metadata: EHentaiSearchMetadata, onMoreInfoClick: (() -> Unit)?) {
    val genre = remember(metadata) { SourceTagsUtil.ehGenre(metadata.genre) }
    val language = remember(metadata) {
        metadata.language?.let { lang ->
            listOfNotNull(SourceTagsUtil.ehLanguageFlag(metadata), lang).joinToString(" ")
        }
    }

    // Genre badge, page count, and the More-info link.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        genre?.let { (color, res) -> GenreBadge(color, stringResource(res)) }
        Spacer(Modifier.weight(1f))
        metadata.length?.let {
            IconLabel(
                MaterialSymbols.AutoMirroredRounded.ChromeReaderMode,
                pluralStringResource(MR.plurals.num_pages, it, it),
            )
            Spacer(Modifier.weight(1f))
        }
        onMoreInfoClick?.let { MoreInfoLink(it) }
    }
    if (metadata.averageRating != null || metadata.size != null) {
        TwoColumnRow(
            left = {
                metadata.averageRating?.let {
                    RatingRow(it.toFloat(), it.toFloat(), it.toFloat() * 2, MaterialTheme.typography.bodySmall)
                }
            },
            right = {
                metadata.size?.let {
                    IconLabel(MaterialSymbols.Rounded.Storage, MetadataUtil.humanReadableByteCount(it, true))
                }
            },
        )
    }
    if (language != null || metadata.favorites != null) {
        TwoColumnRow(
            left = {
                language?.let {
                    val text = if (metadata.translated == true) {
                        "$it (${stringResource(MR.strings.translated)})"
                    } else {
                        it
                    }
                    InfoText(text)
                }
            },
            right = { metadata.favorites?.let { IconLabel(MaterialSymbols.RoundedFilled.Bookmark, it.toString()) } },
        )
    }
    if (metadata.visible != null || metadata.uploader != null) {
        TwoColumnRow(
            left = { metadata.visible?.let { InfoText("${stringResource(MR.strings.visible)}: $it") } },
            right = { metadata.uploader?.let { InfoText(it) } },
        )
    }
}

@Composable
private fun MangaDexGalleryInfo(metadata: MangaDexSearchMetadata, onMoreInfoClick: (() -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        metadata.rating?.let { RatingRow(it / 2f, it, it) }
        Spacer(Modifier.weight(1f))
        onMoreInfoClick?.let { MoreInfoLink(it) }
    }
}

@Composable
private fun GenericGalleryInfo(metadata: RaisedSearchMetadata) {
    val context = LocalContext.current
    // Drop URL-valued fields (thumbnail / token links) so the inline box stays readable.
    val pairs = remember(metadata) {
        metadata.getExtraInfoPairs(context).filterNot { it.second.startsWith("http") }
    }
    pairs.forEach { (label, value) -> InfoRow(label, value) }
}

/** Stars + "score - descriptor" (e.g. "9.19 - Amazing"), the curated rating shown on both cards. */
@Composable
private fun RatingRow(
    stars: Float,
    score: Float,
    scoreOutOfTen: Float,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RatingStars(stars, starSize = 20.dp)
        Text(
            text = "%.2f - %s".format(score, stringResource(ratingLabel(scoreOutOfTen))),
            style = textStyle,
        )
    }
}

/** A two-column row: left content pinned to the start, right content to the end. */
@Composable
private fun TwoColumnRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { left() }
        Row(verticalAlignment = Alignment.CenterVertically) { right() }
    }
}

@Composable
private fun IconLabel(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        InfoText(text)
    }
}

@Composable
private fun MoreInfoLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(MR.strings.more_info),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InfoText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** A genre label on its site colour, or on the default card colours for a genre with none. */
@Composable
internal fun GenreBadge(color: GenreColor?, label: String) {
    val background = color?.let { Color(it.color) }
    Card(colors = background?.let { CardDefaults.cardColors(containerColor = it) } ?: CardDefaults.cardColors()) {
        Text(
            text = label,
            color = background?.let { if (it.luminance() > 0.5f) Color.Black else Color.White } ?: Color.Unspecified,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Five stars for a 0-5 [rating], drawn to the nearest half as the site's own star image is. */
@Composable
internal fun RatingStars(rating: Float, starSize: Dp) {
    val stars = rating.toHalfStars()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (star in 1..5) {
            val icon = when {
                stars >= star -> ReikaiIcons.Star
                stars >= star - 0.5f -> ReikaiIcons.StarHalf
                else -> ReikaiIcons.StarBorder
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

internal fun Float.toHalfStars(): Float = (this * 2).roundToInt() / 2f

// A 0-10 rating mapped to Komikku's descriptor buckets (9 = Amazing, 10 = Masterpiece).
private fun ratingLabel(rating: Float): StringResource = when (rating.roundToInt()) {
    0 -> MR.strings.rating0
    1 -> MR.strings.rating1
    2 -> MR.strings.rating2
    3 -> MR.strings.rating3
    4 -> MR.strings.rating4
    5 -> MR.strings.rating5
    6 -> MR.strings.rating6
    7 -> MR.strings.rating7
    8 -> MR.strings.rating8
    9 -> MR.strings.rating9
    10 -> MR.strings.rating10
    else -> MR.strings.no_rating
}
