package reikai.presentation.browse.migrate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import reikai.data.coil.NovelCover
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.migrate.NovelMigrationConfigScreen
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
 * Per-source favorited-novel picker, the novel twin of
 * [eu.kanade.tachiyomi.ui.browse.migration.manga.MigrateMangaScreen]: lists the favorited novels of
 * one source, multi-select, then Continue hands the selection to the existing
 * [NovelMigrationSourcePickScreen] pipeline. Works for an uninstalled source too, since the list and
 * the migration read stored data.
 */
data class MigrateNovelScreen(
    private val sourceId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MigrateNovelScreenModel(sourceId) }
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
                        // Source-scoped: the user already picked novels from this one source, so go
                        // straight to the migration config and skip the merge-group source picker (that
                        // pre-step is for the library/details routes, where selection isn't per-source).
                        val selection = state.selection.toList()
                        screenModel.clearSelection()
                        navigator.push(NovelMigrationConfigScreen(selection))
                    },
                    expanded = lazyListState.shouldExpandFAB(),
                    modifier = Modifier.animateFloatingActionButton(
                        visible = state.selectionMode,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            if (state.isEmpty) {
                EmptyScreen(stringRes = MR.strings.empty_screen, modifier = Modifier.padding(contentPadding))
                return@Scaffold
            }
            FastScrollLazyColumn(state = lazyListState, contentPadding = contentPadding) {
                items(items = state.novels, key = { it.id }) { novel ->
                    MigrateNovelItem(
                        novel = novel,
                        site = state.sourceSite,
                        isSelected = novel.id in state.selection,
                        onClickItem = { screenModel.toggleSelection(novel.id) },
                        onClickCover = { navigator.push(NovelScreen(novel.source, novel.url)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MigrateNovelItem(
    novel: Novel,
    site: String?,
    isSelected: Boolean,
    onClickItem: () -> Unit,
    onClickCover: () -> Unit,
) {
    Row(
        modifier = Modifier
            .selectedBackground(isSelected)
            .fillMaxWidth()
            .clickable(onClick = onClickItem)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            data = NovelCover(
                url = novel.thumbnailUrl,
                site = site,
                isNovelFavorite = false,
                lastModified = novel.coverLastModified,
                novelId = novel.id,
            ),
            modifier = Modifier.width(48.dp),
            onClick = onClickCover,
        )
        Text(
            text = novel.title,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

class MigrateNovelScreenModel(
    private val sourceId: String,
    private val novelRepository: NovelRepository = Injekt.get(),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val novelPreferences: NovelPreferences = Injekt.get(),
) : StateScreenModel<MigrateNovelScreenModel.State>(State()) {

    init {
        val source = sourceManager.get(sourceId)
        val name = source?.name
            ?: novelPreferences.seenNovelSources().get()[sourceId]?.name
            ?: sourceId
        mutableState.update { it.copy(sourceName = name, sourceSite = source?.site) }

        novelRepository.getLibraryNovelAsFlow()
            .map { list ->
                list.asSequence()
                    .map { it.novel }
                    .filter { it.source == sourceId }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                    .toList()
            }
            .onEach { novels -> mutableState.update { it.copy(novels = novels, loading = false) } }
            .launchIn(screenModelScope)
    }

    fun toggleSelection(id: Long) {
        mutableState.update {
            it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    data class State(
        val loading: Boolean = true,
        val sourceName: String = "",
        val sourceSite: String? = null,
        val novels: List<Novel> = emptyList(),
        val selection: Set<Long> = emptySet(),
    ) {
        val isEmpty: Boolean get() = novels.isEmpty()
        val selectionMode: Boolean get() = selection.isNotEmpty()
    }
}
