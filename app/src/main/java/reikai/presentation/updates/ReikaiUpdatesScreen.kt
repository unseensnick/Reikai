package reikai.presentation.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.updates.UpdatesUiItem
import eu.kanade.presentation.updates.updatesLastUpdatedItem
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import reikai.domain.library.ContentType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

/**
 * The single Updates screen for all three content-type chips. Manga is driven entirely by Mihon's
 * untouched [UpdatesScreenModel] (state + actions); novels by [NovelUpdatesScreenModel]. This is the
 * consolidation that lets cross-cutting features (filters, by-category, group-by-series) apply to every
 * chip from one place, while Mihon's logic stays portable. The Manga chip renders identically to
 * Mihon's own `UpdateScreen` because it reuses the same row composable + the same model.
 */
@Composable
fun ReikaiUpdatesScreen(
    contentType: ContentType,
    mangaModel: UpdatesScreenModel,
    novelModel: NovelUpdatesScreenModel,
    snackbarHostState: SnackbarHostState,
    chip: @Composable () -> Unit,
    onRefresh: () -> Unit,
    onFilterClicked: () -> Unit,
    hasActiveFilters: Boolean,
    onCalendarClicked: () -> Unit,
    onOpenMangaChapter: (UpdatesItem) -> Unit,
    onClickMangaCover: (UpdatesItem) -> Unit,
    onOpenNovelChapter: (NovelUpdatesItem) -> Unit,
) {
    val mangaState by mangaModel.state.collectAsState()
    val novelState by novelModel.state.collectAsState()
    // Manga's hasActiveFilters covers the shared filters + the manga category filter; a novel-only
    // category selection isn't visible there, so the shell folds it in for the filter-icon tint.
    val novelCategoryFilterActive by novelModel.hasActiveCategoryFilter.collectAsState()
    val mangaSelected = mangaState.selected
    val novelSelected = novelState.selected
    val selectionMode = mangaState.selectionMode || novelState.selectionMode
    val showsManga = contentType != ContentType.NOVELS

    fun clearSelection() {
        mangaModel.toggleAllSelection(false)
        novelModel.selectAll(false)
    }

    BackHandler(enabled = selectionMode) { clearSelection() }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_recent_updates),
                actions = {
                    // The filter applies to both manga and novel rows (shared prefs), so it shows on
                    // every chip and live-tints when active. The calendar is the manga upcoming-releases
                    // view, so it only appears where manga rows are shown (Manga + All).
                    val filterTint = if (hasActiveFilters || novelCategoryFilterActive) {
                        MaterialTheme.colorScheme.active
                    } else {
                        LocalContentColor.current
                    }
                    AppBarActions(
                        actions = buildList {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_filter),
                                    icon = Icons.Outlined.FilterList,
                                    iconTint = filterTint,
                                    onClick = onFilterClicked,
                                ),
                            )
                            if (showsManga) {
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_view_upcoming),
                                        icon = Icons.Outlined.CalendarMonth,
                                        onClick = onCalendarClicked,
                                    ),
                                )
                            }
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_update_library),
                                    icon = Icons.Outlined.Refresh,
                                    onClick = onRefresh,
                                ),
                            )
                        },
                    )
                },
                actionModeCounter = mangaSelected.size + novelSelected.size,
                onCancelActionMode = { clearSelection() },
                actionModeActions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = {
                                    if (contentType != ContentType.NOVELS) mangaModel.toggleAllSelection(true)
                                    if (contentType != ContentType.MANGA) novelModel.selectAll(true)
                                },
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = {
                                    if (contentType != ContentType.NOVELS) mangaModel.invertSelection()
                                    if (contentType != ContentType.MANGA) novelModel.invertSelection()
                                },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            // One action bar over the combined selection; each action dispatches to whichever model
            // owns the selected rows (a selection is usually single-type, but mixed is handled).
            MangaBottomActionMenu(
                visible = selectionMode,
                modifier = Modifier.fillMaxWidth(),
                onBookmarkClicked = {
                    if (mangaSelected.isNotEmpty()) mangaModel.bookmarkUpdates(mangaSelected, true)
                    if (novelSelected.isNotEmpty()) novelModel.bookmark(novelSelected, true)
                }.takeIf { mangaSelected.fastAny { !it.update.bookmark } || novelSelected.fastAny { !it.update.bookmark } },
                onRemoveBookmarkClicked = {
                    if (mangaSelected.isNotEmpty()) mangaModel.bookmarkUpdates(mangaSelected, false)
                    if (novelSelected.isNotEmpty()) novelModel.bookmark(novelSelected, false)
                }.takeIf { mangaSelected.fastAll { it.update.bookmark } && novelSelected.fastAll { it.update.bookmark } },
                onMarkAsReadClicked = {
                    if (mangaSelected.isNotEmpty()) mangaModel.markUpdatesRead(mangaSelected, true)
                    if (novelSelected.isNotEmpty()) novelModel.markRead(novelSelected, true)
                }.takeIf { mangaSelected.fastAny { !it.update.read } || novelSelected.fastAny { !it.update.read } },
                onMarkAsUnreadClicked = {
                    if (mangaSelected.isNotEmpty()) mangaModel.markUpdatesRead(mangaSelected, false)
                    if (novelSelected.isNotEmpty()) novelModel.markRead(novelSelected, false)
                }.takeIf {
                    mangaSelected.fastAny { it.update.read || it.update.lastPageRead > 0L } ||
                        novelSelected.fastAny { it.update.read || it.update.lastTextProgress > 0L }
                },
                onDownloadClicked = {
                    if (mangaSelected.isNotEmpty()) mangaModel.downloadChapters(mangaSelected, ChapterDownloadAction.START)
                    if (novelSelected.isNotEmpty()) novelModel.downloadChapters(novelSelected)
                }.takeIf {
                    mangaSelected.fastAny { it.downloadStateProvider() != Download.State.DOWNLOADED } ||
                        novelSelected.fastAny { it.downloadState != Download.State.DOWNLOADED }
                },
                onDeleteClicked = {
                    if (mangaSelected.isNotEmpty()) mangaModel.showConfirmDeleteChapters(mangaSelected)
                    if (novelSelected.isNotEmpty()) novelModel.deleteChapters(novelSelected)
                }.takeIf {
                    mangaSelected.fastAny { it.downloadStateProvider() == Download.State.DOWNLOADED } ||
                        novelSelected.fastAny { it.downloadState == Download.State.DOWNLOADED }
                },
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
            val isLoading = novelState.isLoading || (showsManga && mangaState.isLoading)
            val rows = buildUpdateRows(contentType, mangaState.items, novelState.items)
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> LoadingScreen(Modifier.padding(bodyPadding))
                    rows.isEmpty() -> EmptyScreen(
                        stringRes = MR.strings.information_no_recent,
                        modifier = Modifier.padding(bodyPadding),
                    )
                    else -> {
                        val scope = rememberCoroutineScope()
                        var isRefreshing by remember { mutableStateOf(false) }
                        PullRefresh(
                            refreshing = isRefreshing,
                            onRefresh = {
                                onRefresh()
                                scope.launch {
                                    isRefreshing = true
                                    delay(1.seconds)
                                    isRefreshing = false
                                }
                            },
                            enabled = !selectionMode,
                            indicatorPadding = bodyPadding,
                        ) {
                            FastScrollLazyColumn(contentPadding = bodyPadding) {
                                if (showsManga) updatesLastUpdatedItem(mangaModel.lastUpdated)
                                updateRows(
                                    rows = rows,
                                    selectionMode = selectionMode,
                                    mangaModel = mangaModel,
                                    novelModel = novelModel,
                                    onOpenMangaChapter = onOpenMangaChapter,
                                    onClickMangaCover = onClickMangaCover,
                                    onOpenNovelChapter = onOpenNovelChapter,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.updateRows(
    rows: List<UpdateRow>,
    selectionMode: Boolean,
    mangaModel: UpdatesScreenModel,
    novelModel: NovelUpdatesScreenModel,
    onOpenMangaChapter: (UpdatesItem) -> Unit,
    onClickMangaCover: (UpdatesItem) -> Unit,
    onOpenNovelChapter: (NovelUpdatesItem) -> Unit,
) {
    items(
        items = rows,
        contentType = {
            when (it) {
                is UpdateRow.Header -> "header"
                is UpdateRow.Manga -> "manga"
                is UpdateRow.Novel -> "novel"
            }
        },
        key = {
            when (it) {
                is UpdateRow.Header -> "header-${it.date}"
                is UpdateRow.Manga -> "manga-${it.item.update.mangaId}-${it.item.update.chapterId}"
                is UpdateRow.Novel -> "novel-${it.item.update.novelId}-${it.item.update.chapterId}"
            }
        },
    ) { row ->
        when (row) {
            is UpdateRow.Header -> ListGroupHeader(text = relativeDateText(row.date))
            is UpdateRow.Manga -> {
                val item = row.item
                UpdatesUiItem(
                    update = item.update,
                    selected = item.selected,
                    readProgress = item.update.lastPageRead
                        .takeIf { !item.update.read && it > 0L }
                        ?.let { stringResource(MR.strings.chapter_progress, it + 1) },
                    onClick = {
                        if (selectionMode) {
                            mangaModel.toggleSelection(item, !item.selected, false)
                        } else {
                            onOpenMangaChapter(item)
                        }
                    },
                    onLongClick = { mangaModel.toggleSelection(item, !item.selected, true) },
                    onClickCover = if (selectionMode) null else ({ onClickMangaCover(item) }),
                    onDownloadChapter = if (selectionMode) {
                        null
                    } else {
                        { action -> mangaModel.downloadChapters(listOf(item), action) }
                    },
                    downloadStateProvider = item.downloadStateProvider,
                    downloadProgressProvider = item.downloadProgressProvider,
                )
            }
            is UpdateRow.Novel -> {
                val item = row.item
                NovelUpdatesUiItem(
                    item = item,
                    onClick = {
                        if (selectionMode) {
                            novelModel.toggleSelection(item.update.chapterId, !item.selected)
                        } else {
                            onOpenNovelChapter(item)
                        }
                    },
                    onLongClick = { novelModel.toggleSelection(item.update.chapterId, !item.selected) },
                    onDownloadClick = if (selectionMode) {
                        null
                    } else {
                        { action -> novelModel.onDownloadAction(item, action) }
                    },
                )
            }
        }
    }
}

private sealed interface UpdateRow {
    data class Header(val date: LocalDate) : UpdateRow
    data class Manga(val item: UpdatesItem) : UpdateRow
    data class Novel(val item: NovelUpdatesItem) : UpdateRow
}

/**
 * Merge the manga + novel feeds into one date-grouped list, newest first. NOVELS contributes only
 * novel rows; ALL interleaves both by fetch date; MANGA only manga rows.
 */
private fun buildUpdateRows(
    contentType: ContentType,
    mangaItems: List<UpdatesItem>,
    novelItems: List<NovelUpdatesItem>,
): List<UpdateRow> {
    data class Entry(val dateFetch: Long, val row: UpdateRow)

    val entries = buildList {
        if (contentType != ContentType.NOVELS) {
            mangaItems.forEach { add(Entry(it.update.dateFetch, UpdateRow.Manga(it))) }
        }
        if (contentType != ContentType.MANGA) {
            novelItems.forEach { add(Entry(it.update.dateFetch, UpdateRow.Novel(it))) }
        }
    }.sortedByDescending { it.dateFetch }

    val result = ArrayList<UpdateRow>(entries.size + 8)
    var lastDate: LocalDate? = null
    entries.forEach { entry ->
        val date = entry.dateFetch.toLocalDate()
        if (date != lastDate) {
            result.add(UpdateRow.Header(date))
            lastDate = date
        }
        result.add(entry.row)
    }
    return result
}
