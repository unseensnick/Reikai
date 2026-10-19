package reikai.presentation.stats.details

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
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
import reikai.presentation.stats.label
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The series a Statistics row counts, opened on top of Statistics so back returns there, drawn as the
 * library draws them. [name] is the row's own, so the title reads as the row did.
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
        val rows by viewModel.rows.collectAsState()
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
            val current = rows
            if (current == null) {
                LoadingScreen()
                return@Scaffold
            }
            LibraryItemsLayout(
                items = current,
                displayMode = displayMode,
                getColumnsForOrientation = viewModel::columnsForOrientation,
                contentPadding = paddingValues,
                selection = emptySet(),
                onClick = { item -> scope.launch { viewModel.detailsScreen(item.entryId)?.let(navigator::push) } },
                onLongClick = {},
                onClickContinueReading = onContinueReading.takeIf { showContinueReading },
                searchQuery = null,
                onGlobalSearchClicked = {},
            )
        }
    }
}
