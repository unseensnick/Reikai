package reikai.presentation.novel.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import eu.kanade.presentation.components.SearchToolbar
import reikai.domain.novel.model.NovelChapter
import reikai.novel.content.ChapterSearchSnippet
import reikai.presentation.novel.search.NovelChapterSearchScreen.SearchOptions
import reikai.presentation.novel.search.NovelChapterSearchScreen.SearchResult
import reikai.presentation.novel.search.NovelChapterSearchScreen.State
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun NovelChapterSearchContent(
    state: State,
    navigateUp: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOptionsChange: (SearchOptions) -> Unit,
    onResultClick: (NovelChapter) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                Box {
                    SearchToolbar(
                        searchQuery = state.query,
                        onChangeSearchQuery = { onQueryChange(it.orEmpty()) },
                        placeholderText = stringResource(MR.strings.novel_text_search_hint),
                        onSearch = { onSearch() },
                        onClickCloseSearch = navigateUp,
                        navigateUp = navigateUp,
                        scrollBehavior = scrollBehavior,
                    )
                    val total = state.chapters?.size ?: 0
                    if (state.isSearching && total > 0) {
                        LinearProgressIndicator(
                            progress = { state.searchedCount / total.toFloat() },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(),
                        )
                    }
                }
                SearchOptionsRow(options = state.options, onOptionsChange = onOptionsChange)
            }
        },
    ) { contentPadding ->
        val chapters = state.chapters
        when {
            chapters == null -> LoadingScreen(Modifier.padding(contentPadding))
            chapters.isEmpty() -> EmptyScreen(
                stringRes = MR.strings.novel_text_search_no_chapters,
                modifier = Modifier.padding(contentPadding),
            )
            state.regexError != null -> EmptyScreen(
                message = state.regexError,
                modifier = Modifier.padding(contentPadding),
            )
            state.submittedQuery == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(MaterialTheme.padding.large),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(MR.strings.novel_text_search_empty),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
            else -> SearchResults(
                state = state,
                totalChapters = chapters.size,
                contentPadding = contentPadding,
                onResultClick = onResultClick,
            )
        }
    }
}

/** The find options the find-and-replace rule editor offers, under its labels; whole words hides for a
 *  pattern there too, since the pattern says that itself. */
@Composable
private fun SearchOptionsRow(
    options: SearchOptions,
    onOptionsChange: (SearchOptions) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        FilterChip(
            selected = options.isRegex,
            onClick = { onOptionsChange(options.copy(isRegex = !options.isRegex)) },
            label = { Text(stringResource(MR.strings.novel_regex_use_pattern)) },
        )
        if (!options.isRegex) {
            FilterChip(
                selected = options.wholeWord,
                onClick = { onOptionsChange(options.copy(wholeWord = !options.wholeWord)) },
                label = { Text(stringResource(MR.strings.novel_regex_whole_words)) },
            )
        }
        FilterChip(
            selected = options.caseSensitive,
            onClick = { onOptionsChange(options.copy(caseSensitive = !options.caseSensitive)) },
            label = { Text(stringResource(MR.strings.novel_regex_match_case)) },
        )
    }
}

@Composable
private fun SearchResults(
    state: State,
    totalChapters: Int,
    contentPadding: PaddingValues,
    onResultClick: (NovelChapter) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "summary") {
            SearchSummary(state = state, totalChapters = totalChapters)
        }
        items(items = state.results, key = { it.chapter.id }) { result ->
            SearchResultItem(result = result, onClick = { onResultClick(result.chapter) })
        }
    }
}

@Composable
private fun SearchSummary(
    state: State,
    totalChapters: Int,
) {
    val text = when {
        state.isSearching -> stringResource(
            MR.strings.novel_text_search_progress,
            state.searchedCount,
            totalChapters,
        )
        state.results.isEmpty() -> stringResource(MR.strings.no_results_found)
        else -> pluralStringResource(
            MR.plurals.novel_text_search_matches,
            state.totalMatches,
            state.totalMatches,
        ) + " " + pluralStringResource(
            MR.plurals.novel_text_search_matched_chapters,
            state.results.size,
            state.results.size,
        )
    }
    Column(
        modifier = Modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.secondaryItemAlpha(),
        )
        if (state.failedCount > 0) {
            Text(
                text = pluralStringResource(
                    MR.plurals.novel_text_search_failed_chapters,
                    state.failedCount,
                    state.failedCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SearchResultItem(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.chapter.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(MR.plurals.novel_text_search_matches, result.matchCount, result.matchCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = MaterialTheme.padding.small),
            )
        }
        result.snippets.forEach { snippet ->
            SnippetText(snippet = snippet)
        }
    }
}

@Composable
private fun SnippetText(snippet: ChapterSearchSnippet) {
    val highlight = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    )
    val positionColor = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        append(snippet.text.substring(0, snippet.matchStart))
        withStyle(highlight) { append(snippet.text.substring(snippet.matchStart, snippet.matchEnd)) }
        append(snippet.text.substring(snippet.matchEnd))
        withStyle(SpanStyle(color = positionColor)) {
            append(" (%.1f%%)".format(snippet.position * 100))
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
    )
}
