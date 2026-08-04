package reikai.presentation.migrate.flow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.presentation.core.util.shouldExpandFAB
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
        val screenModel = rememberScreenModel { EntryMigrationFavoritesScreenModel(contentType, sourceKey) }
        val state by screenModel.state.collectAsState()
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
                                        icon = Icons.Outlined.SelectAll,
                                        onClick = screenModel::selectAll,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_select_inverse),
                                        icon = Icons.Outlined.FlipToBack,
                                        onClick = screenModel::invertSelection,
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
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
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
                    stringRes = MR.strings.information_empty_library,
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
                        onToggle = { screenModel.toggle(entry.id) },
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

class EntryMigrationFavoritesScreenModel(
    contentType: ContentType,
    private val sourceKey: String,
) : StateScreenModel<EntryMigrationFavoritesScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val sourceName = adapter.sourceDisplayName(sourceKey)
            mutableState.update { it.copy(sourceName = sourceName) }
            // Kept subscribed: migrating an entry away removes it from this source's library, and
            // the list should say so rather than offering it again.
            adapter.favorites(sourceKey).collectLatest { entries ->
                mutableState.update { state ->
                    val present = entries.mapTo(HashSet()) { it.id }
                    state.copy(
                        isLoading = false,
                        entries = entries,
                        selected = state.selected.intersect(present),
                    )
                }
            }
        }
    }

    fun toggle(id: EntryId) = mutableState.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAll() = mutableState.update { it.copy(selected = it.entries.mapTo(HashSet()) { entry -> entry.id }) }

    fun invertSelection() = mutableState.update { state ->
        state.copy(selected = state.entries.mapNotNull { it.id.takeIf { id -> id !in state.selected } }.toSet())
    }

    data class State(
        val isLoading: Boolean = true,
        val sourceName: String = "",
        val entries: List<MigrationFavorite> = emptyList(),
        val selected: Set<EntryId> = emptySet(),
    )
}
