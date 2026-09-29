package reikai.presentation.browse

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.components.formatLabel
import reikai.presentation.browse.components.sourceDetail
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.components.ContentTypeBadge
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One source's row: its name and language as a heading, and under it either a spinner, a failure, or
 * a horizontal row of what it returned. Shared by every surface that lists sources this way, so a row
 * reads the same whether the source was searched or asked for its latest. A result is a neutral
 * [EntryBrowseRow], so this draws both content types without asking which it holds.
 */
@Composable
fun SearchResultSection(
    row: BrowseSearchRow,
    /** [EntryBrowseRow.key]s of the selected results, across both content types. */
    selectedKeys: Set<String>,
    onClickSource: (BrowseSearchRow) -> Unit,
    onClickEntry: (EntryBrowseRow) -> Unit,
    onLongClickEntry: (EntryBrowseRow) -> Unit,
    showContentType: Boolean = false,
    /** Replaces the source language under the title, where a row is not titled by its source. */
    subtitle: String? = null,
    /** Whether the list holds novel sources of more than one packaging, so this heading names its own. */
    showsFormat: Boolean = false,
    onLongClickSource: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    EntrySearchSection(
        title = row.name,
        subtitle = sourceDetail(
            language = subtitle
                ?: row.lang.takeIf { it.isNotBlank() }?.let { LocaleHelper.getSourceDisplayName(it, context) },
            format = formatLabel(row.format, showsFormat),
        ).orEmpty(),
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
            is EntrySearchState.Success -> EntrySearchCardRow(
                entries = result.entries,
                key = { it.key },
                toUi = { it.content.collectAsState().value.ui },
                onClick = onClickEntry,
                onLongClick = onLongClickEntry,
                isSelected = { it.key in selectedKeys },
            )
        }
    }
}
