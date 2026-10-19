package reikai.presentation.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieModel
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import eu.kanade.presentation.more.stats.components.StatsItem
import kotlinx.datetime.toJavaLocalDate
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
                        DonutSlice(stringResource(entryStatusRes(code)), count, statusColor(code))
                    },
                )
            }
        }
    }
    if (charts.trackStatus.isNotEmpty()) {
        add { fill ->
            StatsCardHeader(MR.strings.stats_tracking_status)
            SectionCard(modifier = fill, verticalArrangement = Arrangement.Center) {
                StatsDonut(
                    TrackStatus.entries.mapNotNull { status ->
                        charts.trackStatus[status]?.let { DonutSlice(stringResource(status.label), it, status.color) }
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

/** A card of rows per grouping that has any, [onOpenRow] opening the series a row counts. */
fun statsGroupCards(
    charts: StatsCharts,
    onSeeAll: (StatsDimension) -> Unit,
    onOpenRow: (StatsRow) -> Unit,
): List<StatsCard> = OVERVIEW_GROUPINGS.mapNotNull { dimension ->
    val rows = charts.groups[dimension].orEmpty()
    if (rows.isEmpty()) return@mapNotNull null
    val card: StatsCard = { fill ->
        StatsCardHeader(dimension.title, MR.strings.stats_see_all) { onSeeAll(dimension) }
        SectionCard(modifier = fill) {
            rows.forEach { StatsRowLine(it, onOpenRow) }
        }
    }
    card
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

/** One slice and its legend row. */
data class DonutSlice(val label: String, val count: Int, val color: Color)

/** A donut of [slices] beside its legend; the legend carries the numbers. */
@Composable
fun StatsDonut(slices: List<DonutSlice>) {
    val producer = remember { PieChartModelProducer() }
    LaunchedEffect(slices) { producer.runTransaction { pieModel { series(slices.map { it.count }) } } }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
fun StatsColumns(values: List<Number>, label: (Int) -> String, description: String) {
    val producer = remember { CartesianChartModelProducer() }
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
            ),
            modelProducer = producer,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = description },
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false),
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

private val TrackStatus.label
    get() = when (this) {
        TrackStatus.READING -> MR.strings.reading
        TrackStatus.COMPLETED -> MR.strings.completed
        TrackStatus.ON_HOLD -> MR.strings.on_hold
        TrackStatus.PLAN_TO_READ -> MR.strings.plan_to_read
        TrackStatus.DROPPED -> MR.strings.dropped
        TrackStatus.UNKNOWN -> MR.strings.unknown
    }
