package reikai.presentation.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelHistoryWithRelations
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.time.LocalDate

/**
 * The single History screen for all three content-type chips, the History twin of [ReikaiUpdatesScreen].
 * Manga rows are driven by Mihon's untouched [HistoryScreenModel] (and rendered with its own
 * [HistoryItem]); novels by [NovelHistoryScreenModel]. One search box and one clear-all action drive
 * both feeds; the rows interleave by read time (newest first) with shared date headers.
 */
@Composable
fun ReikaiHistoryScreen(
    contentType: ContentType,
    mangaModel: HistoryScreenModel,
    novelModel: NovelHistoryScreenModel,
    snackbarHostState: SnackbarHostState,
    chip: @Composable () -> Unit,
    onClickMangaCover: (mangaId: Long) -> Unit,
    onClickMangaResume: (mangaId: Long, chapterId: Long) -> Unit,
    onClickMangaFavorite: (mangaId: Long) -> Unit,
    onClickNovelCover: (novelId: Long) -> Unit,
    onClickNovelResume: (NovelHistoryWithRelations) -> Unit,
) {
    val mangaState by mangaModel.state.collectAsState()
    val novelState by novelModel.state.collectAsState()
    val showsManga = contentType != ContentType.NOVELS
    val showsNovel = contentType != ContentType.MANGA
    val searchQuery = if (showsNovel) novelState.searchQuery else mangaState.searchQuery

    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = { AppBarTitle(stringResource(MR.strings.history)) },
                searchQuery = searchQuery,
                onChangeSearchQuery = { query ->
                    // One field drives both feeds (each filters its own titles in SQL).
                    mangaModel.updateSearchQuery(query)
                    novelModel.updateSearchQuery(query)
                },
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.pref_clear_history),
                                icon = Icons.Outlined.DeleteSweep,
                                // Clear-all is chip-scoped: ALL clears both, otherwise just the shown type.
                                onClick = {
                                    if (showsManga) mangaModel.setDialog(HistoryScreenModel.Dialog.DeleteAll)
                                    if (showsNovel) novelModel.setDialog(NovelHistoryScreenModel.Dialog.DeleteAll)
                                },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
            chip()
            val bodyPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding(),
            )
            val isLoading = (showsManga && mangaState.list == null) || (showsNovel && novelState.list == null)
            val rows = buildHistoryRows(contentType, mangaState.list, novelState.list)
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> LoadingScreen(Modifier.padding(bodyPadding))
                    rows.isEmpty() -> EmptyScreen(
                        stringRes = if (!searchQuery.isNullOrEmpty()) {
                            MR.strings.no_results_found
                        } else {
                            MR.strings.information_no_recent
                        },
                        modifier = Modifier.padding(bodyPadding),
                    )
                    else -> FastScrollLazyColumn(contentPadding = bodyPadding) {
                        historyRows(
                            rows = rows,
                            mangaModel = mangaModel,
                            novelModel = novelModel,
                            onClickMangaCover = onClickMangaCover,
                            onClickMangaResume = onClickMangaResume,
                            onClickMangaFavorite = onClickMangaFavorite,
                            onClickNovelCover = onClickNovelCover,
                            onClickNovelResume = onClickNovelResume,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.historyRows(
    rows: List<HistoryRow>,
    mangaModel: HistoryScreenModel,
    novelModel: NovelHistoryScreenModel,
    onClickMangaCover: (Long) -> Unit,
    onClickMangaResume: (Long, Long) -> Unit,
    onClickMangaFavorite: (Long) -> Unit,
    onClickNovelCover: (Long) -> Unit,
    onClickNovelResume: (NovelHistoryWithRelations) -> Unit,
) {
    items(
        items = rows,
        contentType = {
            when (it) {
                is HistoryRow.Header -> "header"
                is HistoryRow.Manga -> "manga"
                is HistoryRow.Novel -> "novel"
            }
        },
        key = {
            when (it) {
                is HistoryRow.Header -> "header-${it.date}"
                is HistoryRow.Manga -> "manga-${it.item.id}"
                is HistoryRow.Novel -> "novel-${it.item.id}"
            }
        },
    ) { row ->
        when (row) {
            is HistoryRow.Header -> ListGroupHeader(text = relativeDateText(row.date))
            is HistoryRow.Manga -> {
                val value = row.item
                HistoryItem(
                    history = value,
                    onClickCover = { onClickMangaCover(value.mangaId) },
                    onClickResume = { onClickMangaResume(value.mangaId, value.chapterId) },
                    onClickDelete = { mangaModel.setDialog(HistoryScreenModel.Dialog.Delete(value)) },
                    onClickFavorite = { onClickMangaFavorite(value.mangaId) },
                )
            }
            is HistoryRow.Novel -> {
                val value = row.item
                NovelHistoryUiItem(
                    history = value,
                    onClickCover = { onClickNovelCover(value.novelId) },
                    onClickResume = { onClickNovelResume(value) },
                    onClickDelete = { novelModel.setDialog(NovelHistoryScreenModel.Dialog.Delete(value)) },
                )
            }
        }
    }
}

internal sealed interface HistoryRow {
    data class Header(val date: LocalDate) : HistoryRow
    data class Manga(val item: HistoryWithRelations) : HistoryRow
    data class Novel(val item: NovelHistoryWithRelations) : HistoryRow
}

/**
 * Merge the manga + novel history into one date-grouped list, newest first. NOVELS contributes only
 * novel rows; ALL interleaves both by read time; MANGA only manga rows. The manga list arrives as
 * [HistoryUiModel] (its own headers are dropped and re-derived here so both feeds share one header set).
 */
internal fun buildHistoryRows(
    contentType: ContentType,
    mangaList: List<HistoryUiModel>?,
    novelList: List<NovelHistoryWithRelations>?,
): List<HistoryRow> {
    data class Entry(val readAt: Long, val row: HistoryRow)

    val entries = buildList {
        if (contentType != ContentType.NOVELS) {
            mangaList.orEmpty().forEach { model ->
                if (model is HistoryUiModel.Item) {
                    add(Entry(model.item.readAt?.time ?: 0L, HistoryRow.Manga(model.item)))
                }
            }
        }
        if (contentType != ContentType.MANGA) {
            novelList.orEmpty().forEach { add(Entry(it.readAt ?: 0L, HistoryRow.Novel(it))) }
        }
    }.sortedByDescending { it.readAt }

    val result = ArrayList<HistoryRow>(entries.size + 8)
    var lastDate: LocalDate? = null
    entries.forEach { entry ->
        val date = entry.readAt.toLocalDate()
        if (date != lastDate) {
            result.add(HistoryRow.Header(date))
            lastDate = date
        }
        result.add(entry.row)
    }
    return result
}
