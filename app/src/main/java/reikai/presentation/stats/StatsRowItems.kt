package reikai.presentation.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.toDurationString
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.presentation.components.entryStatusRes
import reikai.presentation.library.SourceBadge
import reikai.presentation.library.SourceIconBadge
import reikai.presentation.recents.RECENTS_ROW_HEIGHT
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A section title, with a link at its end when it has [action], set where SectionCard sets its title. It
 * keeps the link's height without one, so cards side by side start level.
 */
@Composable
fun StatsCardHeader(title: StringResource, action: StringResource? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = HEADER_HEIGHT).padding(
            start = MaterialTheme.padding.extraLarge,
            end = MaterialTheme.padding.medium,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(stringResource(action)) }
    }
}

/** One grouping row: its source's icon when it has one, its name, and at the end how many series it holds. */
@Composable
fun StatsRowLine(row: StatsRow, onOpen: (StatsRow) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onOpen(row) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        row.sourceBadge?.let { StatsSourceIcon(it) }
        Text(
            text = row.group.key.label(row.name),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            row.group.count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One series and how long it was read, opening its details: the Most read card and a day's reading. */
@Composable
fun StatsTitleLine(title: String, readMs: Long, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onOpen)
            .then(modifier)
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = statsDuration(readMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [StatsTitleLine] with the series' cover, at History's row height, for the full reading-time lists. */
@Composable
fun StatsCoverTitleLine(title: String, readMs: Long, cover: Any?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onOpen)
            .then(modifier)
            .fillMaxWidth()
            .height(RECENTS_ROW_HEIGHT)
            .padding(vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        MangaCover.Book(modifier = Modifier.fillMaxHeight(), data = cover)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = statsDuration(readMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A source's library cover badge at list size. The badge lays itself out at its cover-corner size, so it
 * is drawn scaled inside a fixed box: every badge case (installed, built-in logo, generic, missing) keeps
 * the library's own drawing, and the library badge stays untouched.
 */
@Composable
private fun StatsSourceIcon(badge: SourceBadge) {
    Box(Modifier.size(SOURCE_ICON_SIZE), contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer(scaleX = SOURCE_ICON_SCALE, scaleY = SOURCE_ICON_SCALE)) {
            SourceIconBadge(badge)
        }
    }
}

/** What a grouping row is called. */
@Composable
fun StatsGroupKey.label(name: String?): String = when (this) {
    is StatsGroupKey.Status -> stringResource(entryStatusRes(code))
    is StatsGroupKey.Score -> bar?.toString() ?: stringResource(MR.strings.stats_not_scored)
    // The band in words, so "2-10" reads "2-10 chapters"; a band counts as plural unless it is exactly one.
    is StatsGroupKey.Length -> {
        val band = when {
            chapters.last == Long.MAX_VALUE -> "${chapters.first}+"
            chapters.first == chapters.last -> chapters.first.toString()
            else -> "${chapters.first}-${chapters.last}"
        }
        val count = if (chapters.first == chapters.last) chapters.first.toInt() else Int.MAX_VALUE
        pluralStringResource(MR.plurals.manga_num_chapters, count, band)
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
    is StatsGroupKey.TrackingStatus -> stringResource(status.label)
}

/**
 * A reading time in at most two units: whole minutes once there is one, so "19m 29s" reads "19m"; a read
 * under a minute keeps its seconds rather than showing nothing.
 */
@Composable
fun statsDuration(ms: Long): String {
    val shown = if (ms >= MS_PER_MINUTE) ms - ms % MS_PER_MINUTE else ms
    return shown.toDuration(DurationUnit.MILLISECONDS)
        .toDurationString(LocalContext.current, fallback = stringResource(MR.strings.none))
}

/**
 * One grouping as a card: its name and series count, its series split by how far they are read (a bar,
 * then the three counts under labels), then its score and time. No value is a bare number, and a missing
 * one shows None in its place rather than shifting the rest.
 */
@Composable
fun StatsRowCard(row: StatsRow, onOpen: (StatsRow) -> Unit) {
    ElevatedCard(
        onClick = { onOpen(row) },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) { StatsRowCardContent(row) }
}

/**
 * A row's card body. With [onReadState], its three read counts are a filter: tapping one narrows to it,
 * tapping the [selected] one again clears it.
 */
@Composable
fun StatsRowCardContent(
    row: StatsRow,
    selected: ReadState? = null,
    onReadState: ((ReadState?) -> Unit)? = null,
) {
    val group = row.group
    Column(
        modifier = Modifier.padding(MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            row.sourceBadge?.let { StatsSourceIcon(it) }
            Text(
                text = group.key.label(row.name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(MR.strings.stats_series_count, group.count),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = TABULAR),
            )
        }
        ReadStateBar(group)
        // A filter cell is filled and inset so it reads as tappable; Score and Time take the same inset to
        // stay in line with the counts above them.
        val inset = if (onReadState != null) Modifier.padding(MaterialTheme.padding.extraSmall) else Modifier
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
            listOf(
                Triple(ReadState.FINISHED, MR.strings.stats_label_finished, group.finished),
                Triple(ReadState.READING, MR.strings.reading, group.reading),
                Triple(ReadState.NOT_STARTED, MR.strings.stats_label_not_started, group.notStarted),
            ).forEach { (state, label, count) ->
                val picked = state == selected
                val filter = onReadState?.let {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            with(MaterialTheme.colorScheme) {
                                if (picked) secondaryContainer else surfaceContainerHighest
                            },
                        )
                        .selectable(selected = picked, role = Role.Tab) { it(if (picked) null else state) }
                        .then(inset)
                } ?: Modifier
                StatsLabelled(label, Modifier.weight(1f).then(filter)) { StatsValue(count.toString()) }
            }
        }
        // Spaced as the counts are, so Time, two cells wide, starts under Reading.
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
            StatsLabelled(MR.strings.score, Modifier.weight(1f).then(inset)) {
                StatsValue(group.meanScore?.let { "%.1f".format(it) })
            }
            StatsLabelled(MR.strings.stats_label_time, Modifier.weight(2f).then(inset)) {
                StatsValue(if (group.readDuration > 0) statsDuration(group.readDuration) else null)
            }
        }
    }
}

/** The finished, reading and not-started shares side by side; the counts under it say the same in words. */
@Composable
private fun ReadStateBar(group: StatsGroup) {
    val parts = listOf(
        group.finished to MaterialTheme.colorScheme.primary,
        group.reading to MaterialTheme.colorScheme.tertiary,
        group.notStarted to MaterialTheme.colorScheme.surfaceVariant,
    ).filter { it.first > 0 }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(MaterialTheme.shapes.small)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.forEach { (count, color) ->
            Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun StatsLabelled(label: StringResource, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(
            text = stringResource(label),
            // A long label in a third of a phone's width breaks inside a word rather than off the edge.
            style = MaterialTheme.typography.labelLarge.copy(hyphens = Hyphens.Auto),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/** A value, or the word None in the same place when there is none. */
@Composable
private fun StatsValue(value: String?) {
    Text(
        text = value ?: stringResource(MR.strings.none),
        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = TABULAR),
        color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

// Same-width digits, so numbers in a list line up and do not jitter between rows.
private const val TABULAR = "tnum"

internal const val MS_PER_MINUTE = 60_000L

private val SOURCE_ICON_SIZE = 24.dp

// A TextButton's touch target, so a header without a link is as tall as one with it.
private val HEADER_HEIGHT = 48.dp

// The library badge is 18dp square (LoadedSourceIconBadge), so this brings it to SOURCE_ICON_SIZE.
private const val SOURCE_ICON_SCALE = 24f / 18f

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
        StatsDimension.TRACK_STATUS -> MR.strings.stats_tracking_status
    }
