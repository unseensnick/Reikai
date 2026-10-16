package reikai.presentation.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.util.toDurationString
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.presentation.components.entryStatusRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/** A section title with a link at its end, set where SectionCard sets its title. */
@Composable
fun StatsCardHeader(title: StringResource, action: StringResource, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = MaterialTheme.padding.extraLarge,
            end = MaterialTheme.padding.medium,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(stringResource(action)) }
    }
}

/**
 * One grouping row: its name, [supporting] detail under it when given, and at the end how many series it
 * holds. Opens its library search.
 */
@Composable
fun StatsRowLine(row: StatsRow, onOpen: (String) -> Unit, supporting: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (row.query != null) Modifier.clickable { onOpen(row.query) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.group.key.label(row.name),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            row.group.count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What a grouping row is called. */
@Composable
fun StatsGroupKey.label(name: String?): String = when (this) {
    is StatsGroupKey.Status -> stringResource(entryStatusRes(code))
    is StatsGroupKey.Score -> bar?.toString() ?: stringResource(MR.strings.stats_not_scored)
    is StatsGroupKey.Length -> when {
        chapters.last == Long.MAX_VALUE -> "${chapters.first}+"
        chapters.first == chapters.last -> chapters.first.toString()
        else -> "${chapters.first}-${chapters.last}"
    }
    is StatsGroupKey.Tracker -> when (trackerId) {
        null -> stringResource(MR.strings.stats_not_tracked)
        else -> name ?: stringResource(MR.strings.unknown)
    }
    is StatsGroupKey.Source -> name ?: key
    is StatsGroupKey.Category -> name ?: stringResource(MR.strings.label_default)
    is StatsGroupKey.Tag -> name ?: ""
    is StatsGroupKey.Language ->
        if (code.isEmpty()) {
            stringResource(
                MR.strings.unknown,
            )
        } else {
            LocaleHelper.getSourceDisplayName(code, LocalContext.current)
        }
    is StatsGroupKey.StartYear -> year?.toString() ?: stringResource(MR.strings.unknown)
}

/** A reading time as the overview shows its total, so the two read alike. */
@Composable
fun statsDuration(ms: Long): String =
    ms.toDuration(
        DurationUnit.MILLISECONDS,
    ).toDurationString(LocalContext.current, fallback = stringResource(MR.strings.none))

/** The section title for a grouping's card and its See all screen. */
val StatsDimension.title: StringResource
    get() = when (this) {
        StatsDimension.STATUS -> MR.strings.stats_publishing_status
        StatsDimension.SCORE -> MR.strings.stats_scores
        StatsDimension.LENGTH -> MR.strings.stats_group_length
        StatsDimension.TRACKER -> MR.strings.stats_group_tracker
        StatsDimension.SOURCE -> MR.strings.stats_group_source
        StatsDimension.CATEGORY -> MR.strings.stats_group_category
        StatsDimension.TAG -> MR.strings.stats_group_tag
        StatsDimension.LANGUAGE -> MR.strings.stats_group_language
        StatsDimension.START_YEAR -> MR.strings.stats_group_start_year
    }
