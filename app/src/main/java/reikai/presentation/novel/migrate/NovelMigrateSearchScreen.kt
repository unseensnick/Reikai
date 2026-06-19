package reikai.presentation.novel.migrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.update
import reikai.data.novel.refreshNovelFromSource
import reikai.data.novel.toNovel
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.globalsearch.NovelGlobalSearchResults
import reikai.presentation.novel.globalsearch.NovelGlobalSearchScreenModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.data.Database
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Pick a migration target for a novel by searching the title across novel sources, reusing the global
 * search fan-out ([NovelGlobalSearchScreenModel] / [NovelGlobalSearchResults]). The novel's current
 * source is hidden. Tapping a result materialises that novel (insert + chapter sync), then opens the
 * [MigrateNovelDialog]; on confirm it opens the target's details.
 */
class NovelMigrateSearchScreen(
    private val fromNovelId: Long,
    private val initialQuery: String,
    private val fromSourceId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val searchModel = rememberScreenModel(tag = "search") { NovelGlobalSearchScreenModel(initialQuery) }
        val migrateModel = rememberScreenModel(tag = "migrate") { NovelMigrateSearchScreenModel(fromNovelId) }
        val searchState by searchModel.state.collectAsState()
        val migrateState by migrateModel.state.collectAsState()
        var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = searchQuery,
                    onChangeSearchQuery = { searchQuery = it ?: "" },
                    navigateUp = navigator::pop,
                    placeholderText = stringResource(MR.strings.action_search),
                    onSearch = searchModel::search,
                    onClickCloseSearch = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            NovelGlobalSearchResults(
                state = searchState,
                contentPadding = contentPadding,
                onSetSourceFilter = searchModel::setSourceFilter,
                onToggleHasResults = searchModel::toggleHasResults,
                onResultClick = { source, item -> migrateModel.resolveTarget(source.id, item.path) },
                onClickSource = null,
                sourceFilter = { it.source.id != fromSourceId },
            )
        }

        if (migrateState.resolving) {
            LoadingScreen(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
            )
        }

        val current = migrateState.current
        val target = migrateState.pendingTarget
        if (current != null && target != null) {
            MigrateNovelDialog(
                current = current,
                target = target,
                onDismissRequest = migrateModel::clearTarget,
                onComplete = {
                    migrateModel.clearTarget()
                    navigator.replace(NovelScreen(target.source, target.url))
                },
            )
        }
    }
}

private class NovelMigrateSearchScreenModel(
    private val fromNovelId: Long,
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val database: Database = Injekt.get(),
) : StateScreenModel<NovelMigrateSearchScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            mutableState.update { it.copy(current = novelRepository.getById(fromNovelId)) }
        }
    }

    /** Materialise the tapped result (insert a shadow row + sync its chapters), then arm the dialog. */
    fun resolveTarget(sourceId: String, url: String) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(resolving = true) }
            val target = runCatching { materialize(sourceId, url) }.getOrNull()
            mutableState.update { it.copy(resolving = false, pendingTarget = target) }
        }
    }

    private suspend fun materialize(sourceId: String, url: String): Novel? {
        val source = sourceManager.get(sourceId) ?: return null
        val sourceNovel = source.parseNovel(url)
        novelRepository.insertOrGet(sourceNovel.toNovel(sourceId = source.id, favorite = false)) ?: return null
        val stored = novelRepository.getByUrlAndSource(url, source.id) ?: return null
        refreshNovelFromSource(stored, source, novelChapterRepository, novelRepository, database)
        return novelRepository.getByUrlAndSource(url, source.id)
    }

    fun clearTarget() {
        mutableState.update { it.copy(pendingTarget = null) }
    }

    data class State(
        val current: Novel? = null,
        val resolving: Boolean = false,
        val pendingTarget: Novel? = null,
    )
}
