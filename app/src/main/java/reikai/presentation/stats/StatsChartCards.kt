package reikai.presentation.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import java.util.Locale

/** One overview card; [fill] goes on its card body, so a shelf can stretch the body to its row's height. */
typealias StatsCard = @Composable ColumnScope.(fill: Modifier) -> Unit

/** The chart cards, each left out when it would draw nothing. */
fun statsChartCards(
    charts: StatsCharts,
    onSeeAll: (StatsDimension) -> Unit,
    onOpenKey: (StatsGroupKey) -> Unit,
    onOpenReading: () -> Unit,
): List<StatsCard> = buildList {
    if (charts.status.isNotEmpty()) {
        add { fill ->
            StatsCardHeader(MR.strings.stats_publishing_status, MR.strings.stats_see_all) {
                onSeeAll(StatsDimension.STATUS)
            }
            SectionCard(modifier = fill, verticalArrangement = Arrangement.Center) {
                StatsDonut(
                    charts.status.toSortedMap().map { (code, count) ->
                        DonutSlice(stringResource(entryStatusRes(code)), count, statusColor(code)) {
                            onOpenKey(StatsGroupKey.Status(code))
                        }
                    },
                )
            }
        }
    }
    if (charts.trackStatus.isNotEmpty()) {
        add { fill ->
            StatsCardHeader(MR.strings.stats_tracking_status, MR.strings.stats_see_all) {
                onSeeAll(StatsDimension.TRACK_STATUS)
            }
            SectionCard(modifier = fill, verticalArrangement = Arrangement.Center) {
                StatsDonut(
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
        add { fill ->
            StatsCardHeader(MR.strings.stats_scores, MR.strings.stats_see_all) { onSeeAll(StatsDimension.SCORE) }
            SectionCard(modifier = fill, verticalArrangement = Arrangement.Center) {
                StatsColumns(
                    values = (1..10).map { charts.scores[it] ?: 0 },
                    label = { (it + 1).toString() },
                    description = (1..10).joinToString { "$it: ${charts.scores[it] ?: 0}" },
                    // Column x is the bar less one; an empty bar has no series to list.
                    onColumnClick = { x ->
                        (x + 1).takeIf { charts.scores[it] != null }?.let { onOpenKey(StatsGroupKey.Score(it)) }
                    },
                )
            }
        }
    }
    add { fill ->
        StatsCardHeader(MR.strings.stats_reading_week, MR.strings.stats_open, onOpenReading)
        SectionCard(modifier = fill, verticalArrangement = Arrangement.Center) {
            StatsReadingWeek(charts)
        }
    }
}

/**
 * The Most read card, then a card of rows per grouping that has any. [onOpenRow] opens the series a row
 * counts, [onOpenEntry] a series' details.
 */
fun statsGroupCards(
    charts: StatsCharts,
    onSeeAll: (StatsDimension) -> Unit,
    onOpenRow: (StatsRow) -> Unit,
    onOpenEntry: (EntryId) -> Unit,
): List<StatsCard> = buildList {
    if (charts.mostRead.isNotEmpty()) {
        add { fill ->
            StatsCardHeader(MR.strings.stats_most_read)
            SectionCard(modifier = fill) {
                charts.mostRead.forEach { StatsTitleLine(it.title, it.readDuration) { onOpenEntry(it.entryId) } }
            }
        }
    }
    OVERVIEW_GROUPINGS.forEach { dimension ->
        val rows = charts.groups[dimension].orEmpty()
        if (rows.isNotEmpty()) {
            add { fill ->
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

// Tall enough to aim at, short enough that a six-slice legend stays beside its pie.
private val LEGEND_ROW_HEIGHT: Dp = 32.dp

@Composable
private fun StatsReadingWeek(charts: StatsCharts) {
    val since = charts.loggedSince
    if (since == null) {
        Text(
            text = stringResource(MR.strings.stats_reading_not_logged),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val locale = Locale.getDefault()
    val days = charts.lastWeek.map { (day, _) ->
        day.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    }
    // Fractional, so a day read for under a minute still shows a bar rather than none.
    val minutes = charts.lastWeek.map { (_, ms) -> ms.toDouble() / MS_PER_MINUTE }
    val spoken = charts.lastWeek.map { (_, ms) -> statsDuration(ms) }
    StatsColumns(
        values = minutes,
        label = { days[it] },
        description = days.zip(spoken).joinToString { (day, time) -> "$day: $time" },
    )
    Row {
        StatsItem(
            pluralStringResource(MR.plurals.day, charts.streaks.current, charts.streaks.current),
            stringResource(MR.strings.stats_current_streak),
        )
        StatsItem(
            pluralStringResource(MR.plurals.day, charts.streaks.longest, charts.streaks.longest),
            stringResource(MR.strings.stats_longest_streak),
        )
    }
    Text(
        text = stringResource(
            MR.strings.stats_logged_since,
            since.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = MaterialTheme.padding.small),
    )
}

/** One slice and its legend row; [onClick] makes the legend row open the slice's series. */
data class DonutSlice(val label: String, val count: Int, val color: Color, val onClick: (() -> Unit)? = null)

/** A donut of [slices] beside its legend; the legend carries the numbers. */
@Composable
fun StatsDonut(slices: List<DonutSlice>) {
    val producer = remember { PieChartModelProducer() }
    // Keyed on the counts: a slice's click lambda is new on every composition and must not redraw the pie.
    val counts = slices.map { it.count }
    LaunchedEffect(counts) { producer.runTransaction { pieModel { series(counts) } } }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        ProvideVicoTheme(rememberM3VicoTheme()) {
            PieChartHost(
                chart = rememberPieChart(
                    sliceProvider = PieChart.SliceProvider.series(slices.map { PieChart.Slice(fill = Fill(it.color)) }),
                    innerSize = PieSize.Inner.fixed(56.dp),
                    spacing = 2.dp,
                ),
                modelProducer = producer,
                // The legend beside it reads every slice, so the chart itself is silent.
                modifier = Modifier.size(112.dp).clearAndSetSemantics {},
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
            slices.forEach { slice ->
                Row(
                    modifier = Modifier
                        .heightIn(min = LEGEND_ROW_HEIGHT)
                        .then(slice.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(slice.color, CircleShape))
                    Text(
                        text = "${slice.label}  ${slice.count}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = MaterialTheme.padding.small),
                    )
                }
            }
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
