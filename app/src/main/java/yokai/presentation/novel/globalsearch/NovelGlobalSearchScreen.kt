package yokai.presentation.novel.globalsearch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.novel.NovelDetailsController
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.novel.host.NovelItem
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.novel.browse.NovelBrowseGridCell
import yokai.util.Screen

/** Fixed cover width for a source's horizontal results row. */
private val RESULT_CELL_WIDTH = 112.dp

/**
 * Cross-source light-novel search results. Each installed source has its own row that shows a
 * spinner, then its covers / "no results" / an error as it completes. Tapping a cover routes to the
 * shared details screen via [onSelectNovel] (wired by the host controller). State lives in
 * [NovelGlobalSearchScreenModel].
 */
class NovelGlobalSearchScreen(
    private val initialQuery: String = "",
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val backPress = LocalBackPress.current
        val router = LocalRouter.current
        val onSelectNovel: (sourceId: String, novelUrl: String) -> Unit = { sourceId, novelUrl ->
            router?.pushController(NovelDetailsController(sourceId, novelUrl).withFadeTransaction())
        }
        val screenModel = rememberScreenModel { NovelGlobalSearchScreenModel(initialQuery) }
        val state by screenModel.state.collectAsState()
        var queryDraft by remember { mutableStateOf(initialQuery) }

        Scaffold(
            topBar = {
                ReikaiTopBar(
                    title = {
                        TextField(
                            value = queryDraft,
                            onValueChange = { queryDraft = it },
                            placeholder = { Text("Search all sources") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { screenModel.search(queryDraft) }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { backPress?.invoke() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                items(items = state.results, key = { it.source.id }) { result ->
                    SourceSection(
                        result = result,
                        favoritedKeys = state.favoritedKeys,
                        onPick = { item -> onSelectNovel(result.source.id, item.path) },
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
    onPick: (NovelItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val lang = result.source.lang.takeIf { it.isNotBlank() }?.let { " - ${LocaleHelper.getLocalizedDisplayName(it)}" } ?: ""
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
                    text = "No results",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                LazyRow(modifier = Modifier.fillMaxWidth()) {
                    items(items = s.novels, key = { it.path }) { item ->
                        NovelBrowseGridCell(
                            item = item,
                            inLibrary = (result.source.id to item.path) in favoritedKeys,
                            libraryLayout = LibraryItem.LAYOUT_COMFORTABLE_GRID,
                            outlineOnCovers = false,
                            onClick = { onPick(item) },
                            onLongClick = { onPick(item) },
                            modifier = Modifier.width(RESULT_CELL_WIDTH).padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
