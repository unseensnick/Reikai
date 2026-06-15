package reikai.presentation.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import reikai.domain.library.ContentType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import java.time.LocalDate

/**
 * The Novels / All side of the Updates tab (the Manga side stays Mihon's `UpdateScreen`). Builds a
 * single date-grouped feed: NOVELS shows only novel rows; ALL interleaves manga + novel rows by fetch
 * date. Selection + the bottom action bar act on novel rows only (manga multi-select stays on the
 * Manga chip); manga rows here are tap-to-open + per-row download.
 */
@Composable
fun ReikaiUpdatesScreen(
    contentType: ContentType,
    mangaState: UpdatesScreenModel.State,
    novelModel: NovelUpdatesScreenModel,
    snackbarHostState: SnackbarHostState,
    chip: @Composable () -> Unit,
    onRefresh: () -> Unit,
    onFilterClicked: () -> Unit,
    hasActiveFilters: Boolean,
    onCalendarClicked: () -> Unit,
    onOpenMangaChapter: (UpdatesItem) -> Unit,
    onClickMangaCover: (UpdatesItem) -> Unit,
    onMangaDownload: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onOpenNovelChapter: (NovelUpdatesItem) -> Unit,
) {
    val novelState by novelModel.state.collectAsState()
    val selectionMode = novelState.selectionMode

    BackHandler(enabled = selectionMode) { novelModel.selectAll(false) }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_recent_updates),
                actions = {
                    // Filter + calendar act on manga updates, so they appear on the All view (which
                    // contains manga rows); the filter live-tints the manga portion. Novels-only omits
                    // them until novel-side filtering lands. Manga-only keeps Mihon's own app bar.
                    val filterTint = if (hasActiveFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
                    AppBarActions(
                        actions = buildList {
                            if (contentType == ContentType.ALL) {
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_filter),
                                        icon = Icons.Outlined.FilterList,
                                        iconTint = filterTint,
                                        onClick = onFilterClicked,
                                    ),
                                )
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
                actionModeCounter = novelState.selected.size,
                onCancelActionMode = { novelModel.selectAll(false) },
                actionModeActions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = { novelModel.selectAll(true) },
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = novelModel::invertSelection,
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val selected = novelState.selected
            MangaBottomActionMenu(
                visible = selectionMode,
                modifier = Modifier.fillMaxWidth(),
                onBookmarkClicked = { novelModel.bookmark(selected, true) }
                    .takeIf { selected.fastAny { !it.update.bookmark } },
                onRemoveBookmarkClicked = { novelModel.bookmark(selected, false) }
                    .takeIf { selected.fastAll { it.update.bookmark } },
                onMarkAsReadClicked = { novelModel.markRead(selected, true) }
                    .takeIf { selected.fastAny { !it.update.read } },
                onMarkAsUnreadClicked = { novelModel.markRead(selected, false) }
                    .takeIf { selected.fastAny { it.update.read || it.update.lastTextProgress > 0L } },
                onDownloadClicked = { novelModel.downloadChapters(selected) }
                    .takeIf { selected.fastAny { it.downloadState != Download.State.DOWNLOADED } },
                onDeleteClicked = { novelModel.deleteChapters(selected) }
                    .takeIf { selected.fastAny { it.downloadState == Download.State.DOWNLOADED } },
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
            val isLoading = novelState.isLoading || (contentType == ContentType.ALL && mangaState.isLoading)
            val rows = rememberUpdateRows(contentType, mangaState.items, novelState.items)
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> LoadingScreen(Modifier.padding(bodyPadding))
                    rows.isEmpty() -> EmptyScreen(
                        stringRes = MR.strings.information_no_recent,
                        modifier = Modifier.padding(bodyPadding),
                    )
                    else -> FastScrollLazyColumn(contentPadding = bodyPadding) {
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
                                        selected = false,
                                        readProgress = item.update.lastPageRead
                                            .takeIf { !item.update.read && it > 0L }
                                            ?.let { stringResource(MR.strings.chapter_progress, it + 1) },
                                        onClick = { onOpenMangaChapter(item) },
                                        onLongClick = { onOpenMangaChapter(item) },
                                        onClickCover = { onClickMangaCover(item) },
                                        onDownloadChapter = { action -> onMangaDownload(listOf(item), action) },
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
                                        onLongClick = {
                                            novelModel.toggleSelection(item.update.chapterId, !item.selected)
                                        },
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
                }
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
 * novel rows; ALL interleaves both by fetch date. A pure derivation, cheap to recompute on state change.
 */
@Composable
private fun rememberUpdateRows(
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
