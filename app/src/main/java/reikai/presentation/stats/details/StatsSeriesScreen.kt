package reikai.presentation.stats.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.launch
import reikai.domain.library.ContentType
import reikai.presentation.library.LibraryItemsLayout
import reikai.presentation.stats.StatsGroupKey
import reikai.presentation.stats.StatsRowCardContent
import reikai.presentation.stats.label
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The series a Statistics row counts, opened on top of Statistics so back returns there, drawn as the
 * library draws them, under the row's own summary card. [name] is the row's own, so the title reads as the
 * row did.
 */
class StatsSeriesScreen(
    private val groupKey: StatsGroupKey,
    private val name: String?,
    private val contentType: ContentType,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val viewModel = assistedMetroViewModel<StatsSeriesViewModel, StatsSeriesViewModel.Factory> {
            create(groupKey, contentType)
        }
        val state by viewModel.state.collectAsState()
        val showContinueReading by viewModel.showContinueReading.collectAsState()
        val displayMode by remember { viewModel.displayMode() }

        val onContinueReading: (LibraryItem) -> Unit = { item ->
            scope.launch {
                val intent = viewModel.resumeIntent(context, item)
                if (intent != null) {
                    context.startActivity(intent)
                } else {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(title = groupKey.label(name), navigateUp = navigator::pop, scrollBehavior = scrollBehavior)
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            val current = state
            if (current == null) {
                LoadingScreen()
                return@Scaffold
            }
            // The bar's padding goes outside the grid, as the library's does, so its scroll thumb starts below it.
            Column(
                modifier = Modifier.padding(
                    top = paddingValues.calculateTopPadding(),
                    start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                    end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
                ),
            ) {
                current.summary?.let { summary ->
                    ElevatedCard(
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.padding.small),
                    ) { StatsRowCardContent(summary, current.readState, viewModel::setReadState) }
                }
                Box(Modifier.weight(1f)) {
                    LibraryItemsLayout(
                        items = current.rows,
                        displayMode = displayMode,
                        getColumnsForOrientation = viewModel::columnsForOrientation,
                        contentPadding = PaddingValues(bottom = paddingValues.calculateBottomPadding()),
                        selection = emptySet(),
                        onClick = { item ->
                            scope.launch { viewModel.detailsScreen(item.entryId)?.let(navigator::push) }
                        },
                        onLongClick = {},
                        onClickContinueReading = onContinueReading.takeIf { showContinueReading },
                        searchQuery = null,
                        onGlobalSearchClicked = {},
                    )
                }
            }
        }
    }
}
