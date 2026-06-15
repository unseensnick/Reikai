package reikai.presentation.novel.globalsearch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.novel.host.NovelItem
import reikai.presentation.novel.browse.NovelBrowseGridCell
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/** Fixed cover width for a source's horizontal results row. */
private val RESULT_CELL_WIDTH = 112.dp

/**
 * Cross-source light-novel search. Each installed source has its own row that shows a spinner, then
 * its covers / "no results" / an error as it completes. Mirrors Mihon's global search layout. Tapping
 * a cover is stubbed until the novel details screen lands. State lives in
 * [NovelGlobalSearchScreenModel].
 */
class NovelGlobalSearchScreen(
    private val initialQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelGlobalSearchScreenModel(initialQuery) }
        val state by screenModel.state.collectAsState()
        var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = searchQuery,
                    onChangeSearchQuery = { searchQuery = it ?: "" },
                    navigateUp = navigator::pop,
                    placeholderText = stringResource(MR.strings.action_search),
                    onSearch = screenModel::search,
                    onClickCloseSearch = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = contentPadding,
            ) {
                items(items = state.results, key = { it.source.id }) { result ->
                    SourceSection(
                        result = result,
                        favoritedKeys = state.favoritedKeys,
                        onResultClick = { navigator.push(NovelScreen(result.source.id, it.path)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSection(
    result: SourceSearchResult,
    favoritedKeys: Set<Pair<String, String>>,
    onResultClick: (NovelItem) -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val lang = result.source.lang.takeIf { it.isNotBlank() }
            ?.let { " · ${LocaleHelper.getSourceDisplayName(it, context)}" }.orEmpty()
        Text(
            text = result.source.name + lang,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        when (val s = result.state) {
            is SearchState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().height(RESULT_CELL_WIDTH),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is SearchState.Error -> Text(
                text = s.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            is SearchState.Success -> if (s.novels.isEmpty()) {
                Text(
                    text = stringResource(MR.strings.no_results_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                LazyRow(modifier = Modifier.fillMaxWidth()) {
                    items(items = s.novels, key = { it.path }) { item ->
                        Box(modifier = Modifier.width(RESULT_CELL_WIDTH).padding(horizontal = 4.dp)) {
                            NovelBrowseGridCell(
                                item = item,
                                inLibrary = (result.source.id to item.path) in favoritedKeys,
                                site = result.source.site,
                                onClick = { onResultClick(item) },
                                onLongClick = { onResultClick(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}
