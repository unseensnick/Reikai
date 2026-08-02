package reikai.presentation.migrate.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
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
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The unified per-source favorites picker, one screen for both content types over the
 * [MigrationFlowAdapter] seam: lists one source's library favorites, multi-select, then Continue
 * hands the selection to [EntryMigrationConfigScreen] directly (the selection is per-source, so the
 * merge-group pick pre-step is skipped, as both routes it replaces do today). Works for an
 * uninstalled source too, since the list and the migration read stored data.
 */
class EntryMigrationFavoritesScreen(
    private val contentType: ContentType,
    private val sourceKey: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { EntryMigrationFavoritesScreenModel(contentType, sourceKey) }
        val state by screenModel.state.collectAsState()

        if (state.loading) {
            LoadingScreen()
            return
        }

        BackHandler(enabled = state.selectionMode) { screenModel.clearSelection() }

        val lazyListState = rememberLazyListState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = state.sourceName,
                    navigateUp = {
                        if (state.selectionMode) screenModel.clearSelection() else navigator.pop()
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                    icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                    onClick = {
                        val selection = state.selection.toList()
                        screenModel.clearSelection()
                        navigator.push(EntryMigrationConfigScreen(contentType, selection))
                    },
                    expanded = lazyListState.shouldExpandFAB(),
                    modifier = Modifier.animateFloatingActionButton(
                        visible = state.selectionMode,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            if (state.entries.isEmpty()) {
                EmptyScreen(stringRes = MR.strings.empty_screen, modifier = Modifier.padding(contentPadding))
                return@Scaffold
            }
            FastScrollLazyColumn(state = lazyListState, contentPadding = contentPadding) {
                items(items = state.entries, key = { it.id.rawId }) { favorite ->
                    FavoriteRow(
                        favorite = favorite,
                        isSelected = favorite.id.rawId in state.selection,
                        onClickItem = { screenModel.toggleSelection(favorite.id.rawId) },
                        onClickCover = { favorite.openDetails(navigator) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    favorite: MigrationFavorite,
    isSelected: Boolean,
    onClickItem: () -> Unit,
    onClickCover: () -> Unit,
) {
    Row(
        modifier = Modifier
            .selectedBackground(isSelected)
            .fillMaxWidth()
            // selectable, not clickable: announces the checked state to accessibility services.
            .selectable(selected = isSelected, onClick = onClickItem)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            data = favorite.cover,
            modifier = Modifier.width(48.dp),
            onClick = onClickCover,
        )
        Text(
            text = favorite.title,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

class EntryMigrationFavoritesScreenModel(
    contentType: ContentType,
    sourceKey: String,
) : StateScreenModel<EntryMigrationFavoritesScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    init {
        screenModelScope.launchIO {
            // Warm the source layer first (the novel plugin host): resolving names, sites and cover
            // Referers against a cold host silently loses them.
            adapter.prepare()
            mutableState.update { it.copy(sourceName = adapter.sourceDisplayName(sourceKey)) }
            adapter.favorites(sourceKey)
                .catch {
                    logcat(LogPriority.ERROR, it)
                    mutableState.update { st -> st.copy(loading = false, entries = emptyList()) }
                }
                .collect { entries -> mutableState.update { it.copy(loading = false, entries = entries) } }
        }
    }

    fun toggleSelection(rawId: Long) {
        mutableState.update {
            it.copy(selection = if (rawId in it.selection) it.selection - rawId else it.selection + rawId)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    data class State(
        val loading: Boolean = true,
        val sourceName: String = "",
        val entries: List<MigrationFavorite> = emptyList(),
        val selection: Set<Long> = emptySet(),
    ) {
        val selectionMode: Boolean get() = selection.isNotEmpty()
    }
}
