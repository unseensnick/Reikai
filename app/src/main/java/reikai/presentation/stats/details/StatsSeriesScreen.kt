package reikai.presentation.stats.details

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import reikai.domain.library.ContentType
import reikai.presentation.library.LibraryItemCell
import reikai.presentation.stats.StatsGroupKey
import reikai.presentation.stats.label
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The series a Statistics row counts, opened on top of Statistics so back returns there. [name] is the
 * row's own, so the title reads as the row did.
 */
class StatsSeriesScreen(
    private val groupKey: StatsGroupKey,
    private val name: String?,
    private val contentType: ContentType,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val viewModel = assistedMetroViewModel<StatsSeriesViewModel, StatsSeriesViewModel.Factory> {
            create(groupKey, contentType)
        }
        val rows by viewModel.rows.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(title = groupKey.label(name), navigateUp = navigator::pop, scrollBehavior = scrollBehavior)
            },
        ) { paddingValues ->
            val current = rows
            if (current == null) {
                LoadingScreen()
                return@Scaffold
            }
            LazyColumn(contentPadding = paddingValues) {
                items(current, key = { it.entryId.toString() }) { item ->
                    LibraryItemCell(
                        item = item,
                        displayMode = LibraryDisplayMode.List,
                        isSelected = false,
                        onClick = { scope.launch { viewModel.detailsScreen(item.entryId)?.let(navigator::push) } },
                        onLongClick = {},
                        onClickContinueReading = null,
                    )
                }
            }
        }
    }
}
