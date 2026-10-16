package reikai.presentation.stats.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
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

/** A week of reading time; picking a day lists what was read that day and for how long. */
class StatsReadingScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<StatsReadingViewModel>()
        val state by viewModel.state.collectAsState()

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
            LazyColumn(contentPadding = paddingValues) {
                item { WeekHeader(current, viewModel::moveWeek) }
                item { WeekBars(current, viewModel::selectDay) }
                item {
                    Text(
                        text = current.selected.toJavaLocalDate()
                            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(
                            start = MaterialTheme.padding.medium,
                            end = MaterialTheme.padding.medium,
                            top = MaterialTheme.padding.large,
                            bottom = MaterialTheme.padding.small,
                        ),
                    )
                }
                if (current.titles.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.stats_nothing_read),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        )
                    }
                }
                items(current.titles) { (title, ms) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = MaterialTheme.padding.medium),
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
                            text = statsDuration(ms),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(state: StatsReadingState, onMoveWeek: (Boolean) -> Unit) {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onMoveWeek(false) }) {
            Icon(
                MaterialSymbols.AutoMirroredRounded.ArrowBack,
                contentDescription = stringResource(MR.strings.stats_previous_week),
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.days.first().first.toJavaLocalDate().format(format) + " - " +
                    state.days.last().first.toJavaLocalDate().format(format),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = statsDuration(state.days.sumOf { it.second }),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(onClick = { onMoveWeek(true) }, enabled = !state.isCurrentWeek) {
            Icon(
                MaterialSymbols.AutoMirroredRounded.ArrowForward,
                contentDescription = stringResource(MR.strings.stats_next_week),
            )
        }
    }
}

/** One bar per day, scaled to the week's longest; a bar is the button that picks its day. */
@Composable
private fun WeekBars(state: StatsReadingState, onSelect: (LocalDate) -> Unit) {
    val longest = state.days.maxOf { it.second }.coerceAtLeast(1L)
    val locale = Locale.getDefault()
    Row(
        modifier = Modifier.fillMaxWidth().height(160.dp).padding(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        state.days.forEach { (day, ms) ->
            val selected = day == state.selected
            val name = day.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val spoken = "$name, ${statsDuration(ms)}"
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(day) })
                    .semantics { contentDescription = spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((ms.toFloat() / longest).coerceAtLeast(0.02f))
                            .background(
                                color = with(MaterialTheme.colorScheme) {
                                    if (selected) primary else secondaryContainer
                                },
                                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                            ),
                    )
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = with(MaterialTheme.colorScheme) { if (selected) primary else onSurfaceVariant },
                    modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                )
            }
        }
    }
}
