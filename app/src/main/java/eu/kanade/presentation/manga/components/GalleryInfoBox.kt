package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
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
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.util.SourceTagsUtil
import exh.util.SourceTagsUtil.GenreColor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji.Companion.getEmojiLangFlag
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
    val stars = remember(metadata) { metadata.averageRating?.toFloat()?.roundToHalf() ?: 0f }
    val genre = remember(metadata) { ehGenre(metadata.genre) }
    val language = remember(metadata) {
        metadata.language?.let { lang ->
            val flag = metadata.tags
                .filter { it.namespace == EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE }
                .firstNotNullOfOrNull { SourceTagsUtil.getLocaleSourceUtil(it.name) }
                ?.toLanguageTag()
                ?.let(::getEmojiLangFlag)
            listOfNotNull(flag, lang).joinToString(" ")
        }
    }

    // Genre badge, page count, and the More-info link.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        genre?.let { (color, res) -> GenreBadge(color, stringResource(res)) }
        Spacer(Modifier.weight(1f))
        metadata.length?.let {
            IconLabel(Icons.AutoMirrored.Outlined.MenuBook, pluralStringResource(MR.plurals.num_pages, it, it))
            Spacer(Modifier.weight(1f))
        }
        onMoreInfoClick?.let { MoreInfoLink(it) }
    }
    if (metadata.averageRating != null || metadata.size != null) {
        TwoColumnRow(
            left = {
                metadata.averageRating?.let {
                    RatingRow(stars, it.toFloat(), it.toFloat() * 2, MaterialTheme.typography.bodySmall)
                }
            },
            right = { metadata.size?.let { IconLabel(Icons.Outlined.Storage, MetadataUtil.humanReadableByteCount(it, true)) } },
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
            right = { metadata.favorites?.let { IconLabel(Icons.Outlined.Bookmark, it.toString()) } },
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
        metadata.rating?.let { RatingRow((it / 2f).roundToHalf(), it, it) }
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
        RatingStars(stars)
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
            imageVector = Icons.Outlined.Info,
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

@Composable
private fun GenreBadge(color: GenreColor, label: String) {
    val background = Color(color.color)
    val foreground = if (background.luminance() > 0.5f) Color.Black else Color.White
    Card(colors = CardDefaults.cardColors(containerColor = background)) {
        Text(
            text = label,
            color = foreground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RatingStars(rating: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (star in 1..5) {
            val icon = when {
                rating >= star -> Icons.Filled.Star
                rating >= star - 0.5f -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// Round to the nearest half so the star row matches the reference (e.g. 4.48 -> 4.5, not floored 4.0).
private fun Float.roundToHalf(): Float = (this * 2).roundToInt() / 2f

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

private fun ehGenre(genre: String?): Pair<GenreColor, StringResource>? = when (genre) {
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
