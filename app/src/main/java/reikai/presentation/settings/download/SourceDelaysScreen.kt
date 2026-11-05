package reikai.presentation.settings.download

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.util.Screen
import reikai.domain.download.DownloadPacing
import reikai.domain.library.ContentType
import reikai.domain.library.labelRes
import reikai.presentation.browse.browseLanguageLabel
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.components.KeepListAtTop
import reikai.presentation.components.rememberChipListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

/** Each installed source's delay between downloaded chapters, manga and novels in one list. */
class SourceDelaysScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<SourceDelaysViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    titleContent = { AppBarTitle(stringResource(MR.strings.pref_source_delay)) },
                    navigateUp = navigator::pop,
                    searchQuery = state.query,
                    onChangeSearchQuery = viewModel::setQuery,
                    placeholderText = stringResource(MR.strings.pref_source_delay_search),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(Modifier.padding(contentPadding)) {
                ContentTypeFilterChips(selected = state.contentType, onSelect = viewModel::setContentType)
                val sections = state.sections
                when {
                    sections == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    sections.isEmpty -> EmptyScreen(
                        stringRes = if (state.query.isNullOrBlank()) {
                            MR.strings.pref_source_delay_empty
                        } else {
                            MR.strings.no_results_found
                        },
                    )
                    else -> {
                        val listState = rememberChipListState(state.contentType)
                        // A row let go into Own delay lands above the first row on screen; keep it in view.
                        KeepListAtTop(listState, (sections.own + sections.manga + sections.novels).firstOrNull()?.key)
                        LazyColumn(state = listState) {
                            section(
                                MR.strings.pref_source_delay_set,
                                sections.own,
                                state,
                                viewModel::setDelay,
                                viewModel::settle,
                            )
                            section(
                                ContentType.MANGA.labelRes,
                                sections.manga,
                                state,
                                viewModel::setDelay,
                                viewModel::settle,
                            )
                            section(
                                ContentType.NOVELS.labelRes,
                                sections.novels,
                                state,
                                viewModel::setDelay,
                                viewModel::settle,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(
    title: dev.icerock.moko.resources.StringResource,
    rows: List<SourceDelay>,
    state: SourceDelaysViewModel.State,
    onPick: (SourceDelay, Long?) -> Unit,
    onLetGo: (SourceDelay) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "header:${title.resourceId}") { PreferenceGroupHeader(title = stringResource(title)) }
    items(rows, key = { it.key }) { row ->
        val globalMs = if (row.type == ContentType.MANGA) state.mangaGlobalMs else state.novelGlobalMs
        val language = browseLanguageLabel(row.lang, LocalContext.current)
        DownloadDelaySlider(
            title = row.name,
            selectedMs = row.delayMs,
            options = listOf(null) + DownloadPacing.delayOptions(row.minimumMs),
            defaultMs = DownloadPacing.effectiveDelay(null, globalMs, row.minimumMs),
            onChange = { onPick(row, it) },
            // The row moves to its new section only once the finger lifts, sliding there.
            modifier = Modifier.animateItem(),
            onChangeFinished = { onLetGo(row) },
            subtitle = if (row.minimumMs > 0L) {
                language + " · " +
                    stringResource(MR.strings.pref_source_delay_minimum, downloadDelayLabel(row.minimumMs))
            } else {
                language
            },
        )
    }
}
