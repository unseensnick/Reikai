package reikai.presentation.migrate.flow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.SelectAll
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.presentation.core.util.shouldExpandFAB
import kotlin.time.Duration.Companion.seconds

/**
 * The library entries of one source, to pick which of them to migrate away from it. This is the
 * route in from the Migrate tab, where a source is chosen first and its entries second.
 *
 * Continue goes straight to the source picker: the entries here all come from the same source, so
 * the merge-group question this flow otherwise asks has already been answered by choosing it.
 */
class EntryMigrationFavoritesScreen(
    private val contentType: ContentType,
    private val sourceKey: String,
) : Screen(), MigrationFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel =
            assistedMetroViewModel<EntryMigrationFavoritesViewModel, EntryMigrationFavoritesViewModel.Factory> {
                create(
                    adapter = context.appGraph.migrationAdapters.forType(contentType),
                    sourceKey = sourceKey,
                )
            }
        val state by viewModel.state.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = state.sourceName,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (state.entries.isNotEmpty()) {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_select_all),
                                        icon = MaterialSymbols.Rounded.SelectAll,
                                        onClick = viewModel::selectAll,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_select_inverse),
                                        icon = MaterialSymbols.Rounded.FlipToBack,
                                        onClick = viewModel::invertSelection,
                                    ),
                                ),
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                if (state.selected.isNotEmpty()) {
                    SmallExtendedFloatingActionButton(
                        text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                        icon = {
                            Icon(
                                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            navigator.push(
                                EntryMigrationConfigScreen(contentType, state.selected.map { it.rawId }),
                            )
                        },
                        expanded = listState.shouldExpandFAB(),
                    )
                }
            },
        ) { contentPadding ->
            if (state.entries.isEmpty()) {
                EmptyScreen(
                    // A failed read is not an empty source, and saying "no entries" would send the
                    // user looking for a library problem that isn't there.
                    stringRes = if (state.failed) MR.strings.internal_error else MR.strings.information_empty_library,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            FastScrollLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                items(items = state.entries, key = { it.id.toString() }) { entry ->
                    FavoriteRow(
                        favorite = entry,
                        checked = entry.id in state.selected,
                        onToggle = { viewModel.toggle(entry.id) },
                        onClickCover = { entry.openDetails(navigator) },
                    )
                }
            }
        }
    }
}

/** Cover width of one picker row; its height follows the 2:3 book ratio. */
private val COVER_WIDTH = 40.dp

/**
 * One library entry to pick. Selection shows as the row's background rather than a checkbox, and the
 * cover is its own tap target that opens the entry, so a title can be checked before it is picked.
 */
@Composable
private fun FavoriteRow(
    favorite: MigrationFavorite,
    checked: Boolean,
    onToggle: () -> Unit,
    onClickCover: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(checked)
            .clickable(onClick = onToggle)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            data = favorite.cover,
            modifier = Modifier.width(COVER_WIDTH),
            onClick = onClickCover,
        )
        Text(
            text = favorite.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        )
    }
}

@AssistedInject
class EntryMigrationFavoritesViewModel(
    @Assisted private val adapter: MigrationFlowAdapter,
    /** A novel source id is a plugin string, so this is Reikai's String rather than upstream's Long. */
    @Assisted private val sourceKey: String,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(adapter: MigrationFlowAdapter, sourceKey: String): EntryMigrationFavoritesViewModel
    }

    private val selected = MutableStateFlow<Set<EntryId>>(emptySet())

    /**
     * The source's name and its favorites, as one value so the screen never shows a loaded list under
     * an empty source name. Stays subscribed while the screen is: migrating an entry away removes it
     * from this source's library, and the list should say so rather than offering it again. A throw
     * used to escape and leave the screen on its spinner for good, since nothing clears isLoading.
     */
    private val content: Flow<Content> = flow {
        adapter.prepare()
        val sourceName = adapter.sourceDisplayName(sourceKey)
        emit(Content(sourceName = sourceName))
        emitAll(
            adapter.favorites(sourceKey)
                .map { entries -> Content(isLoading = false, sourceName = sourceName, entries = entries) }
                .catch { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to read favorites for $sourceKey" }
                    emit(Content(isLoading = false, failed = true, sourceName = sourceName))
                },
        )
    }
        // Drop a selected entry that left the list, so a migration cannot act on a stale id.
        .onEach { content ->
            if (!content.isLoading) {
                val present = content.entries.mapTo(HashSet()) { it.id }
                selected.update { it.intersect(present) }
            }
        }

    val state: StateFlow<State> = combine(content, selected) { content, selected ->
        State(
            isLoading = content.isLoading,
            failed = content.failed,
            sourceName = content.sourceName,
            entries = content.entries,
            selected = selected,
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun toggle(id: EntryId) = selected.update { if (id in it) it - id else it + id }

    fun selectAll() {
        val ids = state.value.entries.mapTo(HashSet()) { it.id }
        selected.update { ids }
    }

    fun invertSelection() {
        val entries = state.value.entries
        selected.update { current -> entries.mapNotNull { it.id.takeIf { id -> id !in current } }.toSet() }
    }

    private data class Content(
        val isLoading: Boolean = true,
        val failed: Boolean = false,
        val sourceName: String = "",
        val entries: List<MigrationFavorite> = emptyList(),
    )

    data class State(
        val isLoading: Boolean = true,
        /** The read failed. Distinct from an empty list, which is a source with no favorites. */
        val failed: Boolean = false,
        val sourceName: String = "",
        val entries: List<MigrationFavorite> = emptyList(),
        val selected: Set<EntryId> = emptySet(),
    )
}
