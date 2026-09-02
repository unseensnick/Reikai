package reikai.presentation.browse

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelSource
import reikai.presentation.browse.components.ContentTypeBadge
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.novel.browse.SelectedNovel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One source's row: its name and language as a heading, and under it either a spinner, a failure, or
 * a horizontal row of what it returned. Shared by every surface that lists sources this way, so a row
 * reads the same whether the source was searched or asked for its latest.
 */
@Composable
fun SearchResultSection(
    row: BrowseSearchRow,
    favoritedKeys: Set<Pair<String, String>>,
    mangaSelection: List<Manga>,
    novelSelection: List<SelectedNovel>,
    getManga: @Composable (Manga) -> State<Manga>,
    onClickSource: (BrowseSearchRow) -> Unit,
    onClickManga: (Manga) -> Unit,
    onLongClickManga: (Manga) -> Unit,
    onClickNovel: (String, NovelItem) -> Unit,
    onLongClickNovel: (String, NovelItem) -> Unit,
    showContentType: Boolean = false,
    /** Replaces the source language under the title, where a row is not titled by its source. */
    subtitle: String? = null,
    onLongClickSource: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    EntrySearchSection(
        title = row.name,
        subtitle = subtitle
            ?: row.lang.takeIf { it.isNotBlank() }
                ?.let { LocaleHelper.getSourceDisplayName(it, context) }.orEmpty(),
        onClick = { onClickSource(row) },
        onLongClick = onLongClickSource,
        badge = { if (showContentType) ContentTypeBadge(row.key.contentType) },
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        when (val result = row.state) {
            is EntrySearchState.Loading -> GlobalSearchLoadingResultItem()
            // Falls back to a generic message: plenty of source failures carry none, and an empty
            // row under a source heading is indistinguishable from one that has not started.
            is EntrySearchState.Error -> GlobalSearchErrorResultItem(result.message)
            is EntrySearchState.Unavailable ->
                GlobalSearchErrorResultItem(stringResource(MR.strings.feed_source_unavailable))
            is EntrySearchState.Success -> when (row.key) {
                is SourceKey.Manga -> EntrySearchCardRow(
                    entries = result.entries.filterIsInstance<Manga>(),
                    key = { it.id },
                    // Resolved here rather than in the mapper, so the badge and the long press read
                    // one value: the row's own copy is whatever the source returned when it loaded.
                    resolve = {
                        val manga by getManga(it)
                        manga
                    },
                    toUi = { it.toEntryBrowseUi() },
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    isSelected = { manga -> mangaSelection.fastAny { it.id == manga.id } },
                )
                is SourceKey.Novel -> {
                    val source = row.source as NovelSource
                    EntrySearchCardRow(
                        entries = result.entries.filterIsInstance<NovelItem>(),
                        key = { it.path },
                        toUi = {
                            it.toEntryBrowseUi(
                                inLibrary = (source.id to it.path) in favoritedKeys,
                                site = source.site,
                            )
                        },
                        onClick = { onClickNovel(source.id, it) },
                        onLongClick = { onLongClickNovel(source.id, it) },
                        isSelected = { item ->
                            novelSelection.fastAny { it.sourceId == source.id && it.item.path == item.path }
                        },
                    )
                }
            }
        }
    }
}
