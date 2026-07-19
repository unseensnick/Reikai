package reikai.presentation.recommendation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import reikai.domain.recommendation.RecommendationOrigin
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Human labels for where a recommendation came from, shared by the carousel (per-card caption) and the
 * browse grid (per-origin section header) so the two can't drift. [originLabel] is the full phrase for a
 * section header; [originLabelShort] is the compact form that fits under a narrow carousel card.
 */
@Composable
fun originLabel(origin: RecommendationOrigin): String = when (origin) {
    is RecommendationOrigin.SourceNative -> stringResource(MR.strings.recs_group_source_native, origin.sourceName)
    is RecommendationOrigin.Tracker -> stringResource(MR.strings.recs_group_tracker, origin.trackerName)
    is RecommendationOrigin.CrossRec -> stringResource(MR.strings.recs_group_cross_rec, origin.fromTitle)
    is RecommendationOrigin.TagSearch -> stringResource(MR.strings.recs_group_tag_search, origin.tag)
}

@Composable
fun originLabelShort(origin: RecommendationOrigin): String = when (origin) {
    // The provider's own name is the clearest "where it came from", unambiguous in the grid + merged groups.
    is RecommendationOrigin.SourceNative -> origin.sourceName
    is RecommendationOrigin.Tracker -> origin.trackerName
    // Taste-driven: name the reason (a title you rate, a tag you like), distinct from the host source.
    is RecommendationOrigin.CrossRec -> stringResource(MR.strings.recs_origin_because_of, origin.fromTitle)
    is RecommendationOrigin.TagSearch -> stringResource(MR.strings.recs_origin_tag, origin.tag)
}

/** The compact one-line origin caption shown as an eyebrow above a recommendation title (carousel + grid). */
@Composable
fun RecommendationOriginCaption(origin: RecommendationOrigin, modifier: Modifier = Modifier) {
    Text(
        text = originLabelShort(origin),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
