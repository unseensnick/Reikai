package reikai.presentation.stats.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import reikai.presentation.stats.BarUnit
import reikai.presentation.stats.ReadingBar
import reikai.presentation.stats.ReadingPeriod
import reikai.presentation.stats.ReadingWindow
import reikai.presentation.stats.StatsCoverTitleLine
import reikai.presentation.stats.statsDuration
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Instant

/**
 * Reading time over a week, a month, a year or a picked range; tapping a bar narrows the series list to
 * its dates.
 */
class StatsReadingScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<StatsReadingViewModel>()
        val scope = rememberCoroutineScope()
        val state by viewModel.state.collectAsState()
        var pickingRange by rememberSaveable { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.stats_group_reading_time),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val current = state
            if (current == null) {
                LoadingScreen()
                return@Scaffold
            }
            if (pickingRange) {
                RangePickerDialog(
                    window = current.window,
                    today = current.today,
                    onConfirm = { first, last ->
                        pickingRange = false
                        viewModel.selectRange(first, last)
                    },
                    onDismiss = { pickingRange = false },
                )
            }
            LazyColumn(contentPadding = paddingValues, horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Capped {
                        PeriodChips(current.window.period) { period ->
                            if (period == ReadingPeriod.CUSTOM) pickingRange = true else viewModel.selectPeriod(period)
                        }
                    }
                }
                item { Capped { WindowHeader(current, viewModel::move) } }
                item { Capped { Bars(current, viewModel::selectBar) } }
                item { Capped { SelectedBarLine(current) } }
                if (current.titles.isEmpty()) {
                    item {
                        Capped {
                            Text(
                                text = stringResource(MR.strings.stats_nothing_read),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.medium),
                            )
                        }
                    }
                }
                items(current.titles) { title ->
                    StatsCoverTitleLine(
                        title = title.name,
                        readMs = title.readMs,
                        cover = title.cover,
                        modifier = Modifier.widthIn(
                            max = READING_MAX_WIDTH,
                        ).padding(horizontal = MaterialTheme.padding.medium),
                    ) {
                        scope.launch { viewModel.detailsScreen(title.entryId)?.let(navigator::push) }
                    }
                }
            }
        }
    }
}

/** How wide the chart and list grow on a tablet, so a bar and its series' times stay within reach of the eye. */
internal val READING_MAX_WIDTH = 720.dp

@Composable
private fun Capped(content: @Composable () -> Unit) {
    Box(Modifier.widthIn(max = READING_MAX_WIDTH)) { content() }
}

private val PERIOD_LABELS: List<Pair<ReadingPeriod, StringResource>> = listOf(
    ReadingPeriod.WEEK to MR.strings.stats_period_week,
    ReadingPeriod.MONTH to MR.strings.stats_period_month,
    ReadingPeriod.YEAR to MR.strings.stats_period_year,
    ReadingPeriod.CUSTOM to MR.strings.stats_period_custom,
)

@Composable
private fun PeriodChips(selected: ReadingPeriod, onSelect: (ReadingPeriod) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        PERIOD_LABELS.forEach { (period, label) ->
            FilterChip(
                selected = period == selected,
                // Custom stays tappable while chosen, since tapping it again picks another range.
                onClick = { onSelect(period) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun WindowHeader(state: StatsReadingState, onMove: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onMove(false) }) {
            Icon(
                MaterialSymbols.AutoMirroredRounded.ArrowBack,
                contentDescription = stringResource(MR.strings.stats_previous_period),
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = windowLabel(state.window), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = statsDuration(state.bars.sumOf { it.ms }),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(onClick = { onMove(true) }, enabled = state.canMoveForward) {
            Icon(
                MaterialSymbols.AutoMirroredRounded.ArrowForward,
                contentDescription = stringResource(MR.strings.stats_next_period),
            )
        }
    }
}

/**
 * One bar per [ReadingBar], scaled to the window's longest; a bar is the button that picks its dates. With
 * nothing picked every bar is drawn as chosen, since the list then covers them all.
 */
@Composable
private fun Bars(state: StatsReadingState, onSelect: (ReadingBar) -> Unit) {
    val longest = state.bars.maxOf { it.ms }.coerceAtLeast(1L)
    val unit = state.window.unit
    val dense = state.bars.size > 14
    val gap = if (dense) 2.dp else MaterialTheme.padding.small
    val corner = if (dense) 2.dp else 6.dp
    Column(
        modifier = Modifier.padding(
            start = MaterialTheme.padding.medium,
            end = MaterialTheme.padding.medium,
            top = MaterialTheme.padding.small,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            state.bars.forEach { bar ->
                val picked = state.selected == null || bar == state.selected
                val spoken = "${barLabel(bar, unit)}, ${statsDuration(bar.ms)}"
                // A day still to come draws nothing, so a month opens with its unread end visibly empty.
                val share = if (bar.first > state.today) 0f else (bar.ms.toFloat() / longest).coerceAtLeast(0.02f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = bar == state.selected, role = Role.Tab, onClick = { onSelect(bar) })
                        .semantics { contentDescription = spoken },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(share)
                            .background(
                                color = with(MaterialTheme.colorScheme) { if (picked) primary else secondaryContainer },
                                shape = RoundedCornerShape(topStart = corner, topEnd = corner),
                            ),
                    )
                }
            }
        }
        BarTicks(state.bars, unit, gap)
    }
}

/** Axis labels centred under their bars, thinned so they never collide; a label may be wider than its bar. */
@Composable
private fun BarTicks(bars: List<ReadingBar>, unit: BarUnit, gap: Dp) {
    val step = ceil(bars.size / maxTicks(unit).toDouble()).toInt().coerceAtLeast(1)
    val locale = LocalLocale.current.platformLocale
    Layout(
        content = {
            bars.forEachIndexed { index, bar ->
                Text(
                    text = if (index % step == 0) barTick(bar, unit, bars.size, locale) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.padding.extraSmall),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val gapPx = gap.roundToPx()
        val barWidth = (constraints.maxWidth - gapPx * (bars.size - 1)).toFloat() / bars.size
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                val centre = index * (barWidth + gapPx) + barWidth / 2
                val x = (centre - placeable.width / 2f).toInt()
                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, 0)
            }
        }
    }
}

@Composable
private fun SelectedBarLine(state: StatsReadingState) {
    val bar = state.selected
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = MaterialTheme.padding.medium,
            end = MaterialTheme.padding.medium,
            top = MaterialTheme.padding.large,
            bottom = MaterialTheme.padding.small,
        ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        if (bar != null) {
            Text(
                text = barLabel(bar, state.window.unit),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            // Nothing read is said by the line below, so no zero duration beside it.
            if (bar.ms > 0) Text(text = statsDuration(bar.ms), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(
    window: ReadingWindow,
    today: LocalDate,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val todayMillis = today.utcMillis()
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = window.first.utcMillis(),
        initialSelectedEndDateMillis = minOf(window.last, today).utcMillis(),
        selectableDates = remember(todayMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = pickerState.selectedStartDateMillis
            TextButton(
                enabled = start != null,
                onClick = {
                    // A range left open at one end is that one day.
                    val first = start!!.utcDate()
                    onConfirm(first, pickerState.selectedEndDateMillis?.utcDate() ?: first)
                },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.weight(1f),
            // The defaults are sized and indented for a full screen picker, and wrap the range in a dialog.
            title = {
                DateRangePickerDefaults.DateRangePickerTitle(
                    displayMode = pickerState.displayMode,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
            headline = {
                ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                    DateRangePickerDefaults.DateRangePickerHeadline(
                        selectedStartDateMillis = pickerState.selectedStartDateMillis,
                        selectedEndDateMillis = pickerState.selectedEndDateMillis,
                        displayMode = pickerState.displayMode,
                        dateFormatter = remember { DatePickerDefaults.dateFormatter() },
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                    )
                }
            },
        )
    }
}

/** The picker speaks in UTC midnights, whatever the device's zone. */
private fun LocalDate.utcMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

private fun Long.utcDate(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

private fun windowLabel(window: ReadingWindow): String = when (window.period) {
    ReadingPeriod.MONTH -> monthYear(window.first)
    ReadingPeriod.YEAR -> window.first.year.toString()
    ReadingPeriod.WEEK, ReadingPeriod.CUSTOM -> {
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        window.first.toJavaLocalDate().format(format) + " - " + window.last.toJavaLocalDate().format(format)
    }
}

/** A bar's dates in full, for the line over the list and for a screen reader. */
private fun barLabel(bar: ReadingBar, unit: BarUnit): String = when (unit) {
    BarUnit.DAY -> bar.first.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    BarUnit.MONTH -> monthYear(bar.first)
    BarUnit.YEAR -> bar.first.year.toString()
}

private fun barTick(bar: ReadingBar, unit: BarUnit, count: Int, locale: Locale): String = when (unit) {
    BarUnit.DAY -> if (count <= 7) {
        bar.first.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    } else {
        bar.first.day.toString()
    }
    BarUnit.MONTH -> bar.first.toJavaLocalDate().month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
    BarUnit.YEAR -> bar.first.year.toString()
}

/** How many axis labels fit under the chart on a phone for each kind of bar. */
private fun maxTicks(unit: BarUnit): Int = when (unit) {
    BarUnit.DAY -> 7
    BarUnit.MONTH -> 12
    BarUnit.YEAR -> 6
}

private fun monthYear(day: LocalDate): String =
    day.toJavaLocalDate().format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
