package reikai.presentation.stats.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Check
import reikai.presentation.details.searchLibraryFromDetails
import reikai.presentation.stats.STATS_CARD_MIN_WIDTH
import reikai.presentation.stats.StatsDimension
import reikai.presentation.stats.StatsRowCard
import reikai.presentation.stats.StatsSort
import reikai.presentation.stats.isSortable
import reikai.presentation.stats.title
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/** Every row of one grouping, each opening the library search for the series it counts. */
class StatsGroupScreen(private val dimension: StatsDimension) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val viewModel = assistedMetroViewModel<StatsGroupViewModel, StatsGroupViewModel.Factory> { create(dimension) }
        val state by viewModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(dimension.title),
                    navigateUp = navigator::pop,
                    actions = {
                        val current = state
                        if (dimension.isSortable && current != null) SortMenu(current.sort, viewModel::setSort)
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val current = state
            if (current == null) {
                LoadingScreen()
                return@Scaffold
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(STATS_CARD_MIN_WIDTH),
                contentPadding = PaddingValues(
                    start = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.medium,
                    top = paddingValues.calculateTopPadding() + MaterialTheme.padding.small,
                    bottom = paddingValues.calculateBottomPadding() + MaterialTheme.padding.medium,
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(current.rows, key = { it.group.key.toString() }) { row ->
                    StatsRowCard(row) { query ->
                        scope.launch { navigator.searchLibraryFromDetails(query, current.contentType) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenu(selected: StatsSort, onSelect: (StatsSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(MaterialSymbols.AutoMirroredRounded.Sort, contentDescription = stringResource(MR.strings.stats_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StatsSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.label)) },
                    onClick = {
                        onSelect(sort)
                        expanded = false
                    },
                    trailingIcon = {
                        if (sort == selected) Icon(MaterialSymbols.Rounded.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

private val StatsSort.label: StringResource
    get() = when (this) {
        StatsSort.COUNT -> MR.strings.stats_sort_count
        StatsSort.READ -> MR.strings.stats_sort_read
        StatsSort.SCORE -> MR.strings.stats_sort_score
        StatsSort.TIME -> MR.strings.stats_sort_time
    }
