package reikai.presentation.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieModel
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import eu.kanade.presentation.more.stats.components.StatsItem
import kotlinx.datetime.toJavaLocalDate
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.recommendation.taste.TrackStatus
import reikai.presentation.components.EntryStatus
import reikai.presentation.components.entryStatusRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** One overview card; [fill] goes on its card body, so a shelf can stretch the body to its row's height. */
typealias StatsCard = @Composable ColumnScope.(fill: Modifier) -> Unit

/** Reading this week, one of the chart cards. */
fun statsReadingCard(charts: StatsCharts, onOpenReading: () -> Unit): StatsCard = { fill ->
    StatsCardHeader(StatsCardKind.READING.title, MR.strings.local_import_open, onOpenReading)
    SectionCard(modifier = fill, verticalArrangement = Arrangement.Center) {
        StatsReadingWeek(charts)
    }
}

/** The chart cards, each left out when it would draw nothing. Reading this week is [statsReadingCard]. */
fun statsChartCards(
    charts: StatsCharts,
    onSeeAll: (StatsDimension) -> Unit,
    onOpenKey: (StatsGroupKey) -> Unit,
): Map<StatsCardKind, StatsCard> = buildMap {
    if (charts.status.isNotEmpty()) {
        put(StatsCardKind.PUBLISHING) { fill ->
            StatsCardHeader(StatsCardKind.PUBLISHING.title, MR.strings.stats_see_all) {
                onSeeAll(StatsDimension.STATUS)
            }
            SectionCard(modifier = fill) {
                val slices = charts.status.toSortedMap().map { (code, count) ->
                    DonutSlice(stringResource(entryStatusRes(code)), count, statusColor(code)) {
                        onOpenKey(StatsGroupKey.Status(code))
                    }
                }
                // Only on a two-column shelf: on one column the pie would only add height
                if (StatsShelf.CHARTS.columns(LocalStatsShelfWidth.current) > 1) StatsDonut(slices)
                StatsShareBars(slices)
            }
        }
    }
    if (charts.trackStatus.isNotEmpty()) {
        put(StatsCardKind.TRACKING) { fill ->
            StatsCardHeader(StatsCardKind.TRACKING.title, MR.strings.stats_see_all) {
                onSeeAll(StatsDimension.TRACK_STATUS)
            }
            SectionCard(modifier = fill) {
                StatsShareBars(
                    TrackStatus.entries.mapNotNull { status ->
                        charts.trackStatus[status]?.let {
                            DonutSlice(stringResource(status.label), it, status.color) {
                                onOpenKey(StatsGroupKey.TrackingStatus(status))
                            }
                        }
                    },
                )
            }
        }
    }
    if (charts.scores.isNotEmpty()) {
        put(StatsCardKind.SCORES) { fill ->
            StatsCardHeader(StatsCardKind.SCORES.title, MR.strings.stats_see_all) { onSeeAll(StatsDimension.SCORE) }
            SectionCard(modifier = fill, verticalArrangement = Arrangement.Center) {
                StatsScoreBreakdown(charts.scores) { onOpenKey(StatsGroupKey.Score(it)) }
            }
        }
    }
}

/**
 * The Most read card, then a card of rows per grouping that has any. [onOpenRow] opens the series a row
 * counts, [onOpenEntry] a series' details, [onOpenMostRead] the Most read list in full.
 */
fun statsGroupCards(
    charts: StatsCharts,
    onSeeAll: (StatsDimension) -> Unit,
    onOpenRow: (StatsRow) -> Unit,
    onOpenEntry: (EntryId) -> Unit,
    onOpenMostRead: () -> Unit,
): Map<StatsCardKind, StatsCard> = buildMap {
    if (charts.mostRead.isNotEmpty()) {
        put(StatsCardKind.MOST_READ) { fill ->
            StatsCardHeader(StatsCardKind.MOST_READ.title, MR.strings.stats_see_all, onOpenMostRead)
            SectionCard(modifier = fill) {
                charts.mostRead.forEach { StatsTitleLine(it.name, it.readMs) { onOpenEntry(it.entryId) } }
            }
        }
    }
    StatsCardKind.entries.forEach { kind ->
        val dimension = kind.grouping ?: return@forEach
        val rows = charts.groups[dimension].orEmpty()
        if (rows.isNotEmpty()) {
            put(kind) { fill ->
                StatsCardHeader(dimension.title, MR.strings.stats_see_all) { onSeeAll(dimension) }
                SectionCard(modifier = fill) {
                    rows.forEach { StatsRowLine(it, onOpenRow) }
                }
            }
        }
    }
}

/** The narrowest a See all card gets before its grid drops a column, so a phone keeps one column. */
val STATS_CARD_MIN_WIDTH: Dp = 340.dp

@Composable
private fun StatsReadingWeek(charts: StatsCharts) {
    val since = charts.loggedSince
    if (since == null) {
        Text(
            text = stringResource(MR.strings.stats_reading_not_logged),
            style = MaterialTheme.typography.bodyMedium,
        )
        // History predates the log, so a pace can be known before any day is.
        StatsPaceRow(charts.paces)
        return
    }
    val locale = LocalLocale.current.platformLocale
    val days = charts.lastWeek.map { (day, _) ->
        day.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    }
    // Fractional, so a day read for under a minute still shows a bar rather than none.
    val minutes = charts.lastWeek.map { (_, ms) -> ms.toDouble() / MS_PER_MINUTE }
    val spoken = charts.lastWeek.map { (_, ms) -> statsDuration(ms) }
    Text(
        text = stringResource(
            MR.strings.stats_logged_since,
            since.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
    )
    StatsColumns(
        values = minutes,
        label = { days[it] },
        description = days.zip(spoken).joinToString { (day, time) -> "$day: $time" },
    )
    // Intrinsic height: a StatsItem fills the height it is given, which here would leave nothing for the
    // rows under it.
    Row(Modifier.height(IntrinsicSize.Min)) {
        // The week ends today, so its last day is the time read today.
        StatsItem(statsDuration(charts.lastWeek.last().second), stringResource(MR.strings.relative_time_today))
        StatsItem(
            pluralStringResource(MR.plurals.day, charts.streaks.current, charts.streaks.current),
            stringResource(MR.strings.stats_current_streak),
        )
        StatsItem(
            pluralStringResource(MR.plurals.day, charts.streaks.longest, charts.streaks.longest),
            stringResource(MR.strings.stats_longest_streak),
        )
    }
    StatsPaceRow(charts.paces)
}

/**
 * Scores as a ratings breakdown, a row for each score given from 10 down: its bar as long as its share of the
 * largest and its count of series at the end. Tapping a row lists that score's series.
 */
@Composable
private fun StatsScoreBreakdown(
    scores: Map<Int, Int>,
    onOpenScore: (Int) -> Unit,
) {
    val most = scores.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    scores.keys.sortedDescending().forEach { score ->
        val count = scores.getValue(score)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenScore(score) }
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.width(24.dp),
            )
            Box(
                Modifier
                    .padding(horizontal = MaterialTheme.padding.small)
                    .weight(1f)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(count.toFloat() / most)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp),
            )
        }
    }
}

/** Each type's overall pace in its own measure, or Learning until three finished chapters say. */
@Composable
private fun StatsPaceRow(paces: List<StatsPace>) {
    if (paces.isEmpty()) return
    Row(Modifier.height(IntrinsicSize.Min)) {
        paces.forEach { pace ->
            val measure = pace.measure()
            val isManga = pace.contentType == ContentType.MANGA
            StatsItem(
                title = when {
                    measure == null -> stringResource(MR.strings.stats_pace_learning)
                    isManga -> stringResource(MR.strings.seconds_short, measure.toInt())
                    else -> measure.toString()
                },
                subtitle = stringResource(
                    when {
                        isManga -> MR.strings.stats_pace_per_page
                        pace.listening -> MR.strings.stats_pace_listening
                        else -> MR.strings.stats_pace_words_per_minute
                    },
                ),
            )
        }
    }
}

/** One slice and its legend row; [onClick] opens the slice's series from its legend row. */
data class DonutSlice(val label: String, val count: Int, val color: Color, val onClick: () -> Unit)

/**
 * [slices] as one labelled row each, most first: the name, the count and its share, over a bar as long as that
 * share. Tapping a row opens its series, as a donut's legend row does.
 */
@Composable
fun StatsShareBars(slices: List<DonutSlice>) {
    val total = slices.sumOf { it.count }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        slices.sortedByDescending { it.count }.forEach { slice ->
            val share = slice.count.toFloat() / total
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = slice.onClick)
                    .padding(vertical = 6.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = slice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${slice.count} · ${(share * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(share)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(slice.color),
                    )
                }
            }
        }
    }
}

/** A donut of [slices] with their total in the hole; the bars under it are its legend and carry the numbers. */
@Composable
fun StatsDonut(slices: List<DonutSlice>) {
    val producer = remember { PieChartModelProducer() }
    // Keyed on the counts: a slice's click lambda is new on every composition and must not redraw the pie.
    val counts = slices.map { it.count }
    LaunchedEffect(counts) { producer.runTransaction { pieModel { series(counts) } } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MaterialTheme.padding.medium)
            .height(112.dp),
        contentAlignment = Alignment.Center,
    ) {
        ProvideVicoTheme(rememberM3VicoTheme()) {
            PieChartHost(
                chart = rememberPieChart(
                    sliceProvider = PieChart.SliceProvider.series(slices.map { PieChart.Slice(fill = Fill(it.color)) }),
                    innerSize = PieSize.Inner.fixed(68.dp),
                    spacing = 2.dp,
                ),
                modelProducer = producer,
                // The bars under it read every slice, so the chart itself is silent.
                modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = counts.sum().toString(), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(MR.strings.stats_donut_series),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Columns of [values], labelled by index along the bottom; [description] is what a screen reader hears. */
@Composable
fun StatsColumns(
    values: List<Number>,
    label: (Int) -> String,
    description: String,
    onColumnClick: ((Int) -> Unit)? = null,
) {
    val producer = remember { CartesianChartModelProducer() }
    // Vico hands touches to the marker controller only when the chart has a marker, so a tappable chart
    // gets one that never shows: the controller reports a tap's column and always answers false.
    val currentOnColumnClick by rememberUpdatedState(onColumnClick)
    val marker = if (onColumnClick != null) rememberDefaultCartesianMarker(label = rememberTextComponent()) else null
    val tapController = remember {
        CartesianMarkerController { interaction, targets ->
            if (interaction is Interaction.Tap) {
                targets.firstOrNull()?.let { currentOnColumnClick?.invoke(it.x.toInt()) }
            }
            false
        }
    }
    val pressController = CartesianMarkerController.rememberShowOnPress()
    LaunchedEffect(values) { producer.runTransaction { columnModel { series(values) } } }
    val bottomFormatter = remember(label) { CartesianValueFormatter { _, x, _ -> label(x.toInt()) } }
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    ColumnCartesianLayer.ColumnProvider.series(
                        rememberLineComponent(Fill(MaterialTheme.colorScheme.primary), thickness = 16.dp),
                    ),
                ),
                // Counts and minutes are whole, so the axis steps by whole units (wider when labels crowd).
                startAxis = VerticalAxis.rememberStart(itemPlacer = remember { VerticalAxis.ItemPlacer.step({ 1.0 }) }),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomFormatter),
                marker = marker,
                markerController = if (onColumnClick != null) tapController else pressController,
            ),
            modelProducer = producer,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = description },
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            // Vico's default zoom only grows the chart, so on a narrow card the last columns ran off the edge.
            zoomState = rememberVicoZoomState(
                zoomEnabled = false,
                initialZoom = remember { Zoom.min(Zoom.fixed(), Zoom.Content) },
            ),
        )
    }
}

// Theme roles, so the charts follow dynamic colour and dark mode; fixed per value, so a status keeps its
// colour from one chip to the next.
@Composable
private fun statusColor(code: Long): Color = with(MaterialTheme.colorScheme) {
    when (EntryStatus.of(code)) {
        EntryStatus.ONGOING -> primary
        EntryStatus.COMPLETED -> tertiary
        EntryStatus.LICENSED -> secondary
        EntryStatus.PUBLISHING_FINISHED -> inversePrimary
        EntryStatus.CANCELLED -> error
        EntryStatus.ON_HIATUS -> onSurfaceVariant
        EntryStatus.UNKNOWN -> outline
    }
}

private val TrackStatus.color: Color
    @Composable get() = with(MaterialTheme.colorScheme) {
        when (this@color) {
            TrackStatus.READING -> primary
            TrackStatus.COMPLETED -> tertiary
            TrackStatus.ON_HOLD -> secondary
            TrackStatus.PLAN_TO_READ -> inversePrimary
            TrackStatus.DROPPED -> error
            TrackStatus.UNKNOWN -> outline
        }
    }

internal val TrackStatus.label
    get() = when (this) {
        TrackStatus.READING -> MR.strings.reading
        TrackStatus.COMPLETED -> MR.strings.completed
        TrackStatus.ON_HOLD -> MR.strings.on_hold
        TrackStatus.PLAN_TO_READ -> MR.strings.plan_to_read
        TrackStatus.DROPPED -> MR.strings.dropped
        TrackStatus.UNKNOWN -> MR.strings.unknown
    }
