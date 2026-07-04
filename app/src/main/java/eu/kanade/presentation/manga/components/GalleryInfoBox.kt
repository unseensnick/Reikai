package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.util.SourceTagsUtil
import exh.util.SourceTagsUtil.GenreColor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji.Companion.getEmojiLangFlag
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Per-source gallery-info card shown above the description on adult/metadata galleries (Reikai's
 * Compose-native take on Komikku's *DescriptionAdapter cards). E-Hentai gets a curated rich layout
 * (rating stars, colored genre badge, uploader, pages, language flag, size, date); MangaDex gets a
 * curated rating widget (stars + score + descriptor); other metadata sources fall back to their
 * [RaisedSearchMetadata.getExtraInfoPairs]. The full field dump is always behind the More-info button.
 */
@Composable
fun GalleryInfoBox(
    metadata: RaisedSearchMetadata,
    onMoreInfoClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Skip the card when a source has nothing curated and no non-URL info pairs to list; its
    // namespaced tags still render as chips in the description block regardless.
    val hasInfo = metadata is EHentaiSearchMetadata || metadata is MangaDexSearchMetadata ||
        remember(metadata) { metadata.getExtraInfoPairs(context).any { !it.second.startsWith("http") } }
    if (!hasInfo) return

    OutlinedCard(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (metadata) {
                is EHentaiSearchMetadata -> EHentaiGalleryInfo(metadata)
                is MangaDexSearchMetadata -> MangaDexGalleryInfo(metadata)
                else -> GenericGalleryInfo(metadata)
            }
            if (onMoreInfoClick != null) {
                TextButton(onClick = onMoreInfoClick, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(MR.strings.action_metadata_viewer))
                }
            }
        }
    }
}

@Composable
private fun EHentaiGalleryInfo(metadata: EHentaiSearchMetadata) {
    val rating = remember(metadata) {
        metadata.averageRating?.toFloat()?.div(0.5f)?.let { floor(it) }?.let { 0.5f * it } ?: 0f
    }
    val genre = remember(metadata) { ehGenre(metadata.genre) }
    val flag = remember(metadata) {
        metadata.tags
            .filter { it.namespace == EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE }
            .firstNotNullOfOrNull { SourceTagsUtil.getLocaleSourceUtil(it.name) }
            ?.toLanguageTag()
            ?.let { getEmojiLangFlag(it) }
    }
    val date = remember(metadata) {
        metadata.datePosted?.let {
            runCatching {
                MetadataUtil.EX_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
            }.getOrNull()
        }
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RatingStars(rating)
        metadata.averageRating?.let { Text(text = "%.2f".format(it), style = MaterialTheme.typography.bodySmall) }
        genre?.let { (color, res) -> GenreBadge(color, stringResource(res)) }
        flag?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
    }
    metadata.uploader?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
    metadata.length?.let { InfoRow(stringResource(MR.strings.page_count), it.toString()) }
    metadata.size?.let { InfoRow(stringResource(MR.strings.gallery_size), MetadataUtil.humanReadableByteCount(it, true)) }
    metadata.favorites?.let { InfoRow(stringResource(MR.strings.total_favorites), it.toString()) }
    date?.let { InfoRow(stringResource(MR.strings.date_posted), it) }
}

@Composable
private fun MangaDexGalleryInfo(metadata: MangaDexSearchMetadata) {
    val rating = metadata.rating ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatingStars(rating / 2f)
        Text(
            text = "%.2f - %s".format(rating, stringResource(mdRatingLabel(rating))),
            style = MaterialTheme.typography.bodySmall,
        )
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
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
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// MangaDex's 0-10 rating mapped to Komikku's descriptor buckets (9 = Amazing, 10 = Masterpiece).
private fun mdRatingLabel(rating: Float): StringResource = when (rating.roundToInt()) {
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
