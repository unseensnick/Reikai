package reikai.presentation.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
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
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import tachiyomi.presentation.core.util.selectedBackground
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
    onClickNovelCover: (NovelUpdatesItem) -> Unit,
) {
    val mangaState by mangaModel.state.collectAsState()
    val novelState by novelModel.state.collectAsState()
    // Manga's hasActiveFilters covers the shared filters + the manga category filter; a novel-only
    // category selection isn't visible there, so the shell folds it in for the filter-icon tint.
    val novelCategoryFilterActive by novelModel.hasActiveCategoryFilter.collectAsState()
    val groupBySeries by novelModel.groupBySeries.collectAsState()
    // Merge-aware grouping keys (per-series id -> merge-group key); empty unless grouping is on.
    val mangaSeriesKeys by novelModel.mangaSeriesKeys.collectAsState()
    val novelSeriesKeys by novelModel.novelSeriesKeys.collectAsState()
    // Which series groups are expanded (keyed by series+date); ephemeral UI state.
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
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
                }.takeIf {
                    mangaSelected.fastAny { !it.update.bookmark } ||
                        novelSelected.fastAny { !it.update.bookmark }
                },
                onRemoveBookmarkClicked = {
                    if (mangaSelected.isNotEmpty()) mangaModel.bookmarkUpdates(mangaSelected, false)
                    if (novelSelected.isNotEmpty()) novelModel.bookmark(novelSelected, false)
                }.takeIf {
                    mangaSelected.fastAll { it.update.bookmark } && novelSelected.fastAll { it.update.bookmark }
                },
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
                    if (mangaSelected.isNotEmpty()) {
                        mangaModel.downloadChapters(
                            mangaSelected,
                            ChapterDownloadAction.START,
                        )
                    }
                    if (novelSelected.isNotEmpty()) novelModel.downloadChapters(novelSelected)
                }.takeIf {
                    mangaSelected.fastAny { it.downloadStateProvider() != Download.State.DOWNLOADED } ||
                        novelSelected.fastAny { it.downloadState != Download.State.DOWNLOADED }
                },
                onDeleteClicked = {
                    showDeleteConfirm = true
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
            val expandedKeys = expandedGroups.filterValues { it }.keys
            val rows = buildUpdateRows(
                contentType,
                mangaState.items,
                novelState.items,
                groupBySeries,
                expandedKeys,
                mangaSeriesKeys,
                novelSeriesKeys,
            )
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
                                // One "Last updated" line per chip; All shows the more recent of the two.
                                val lastUpdated = when (contentType) {
                                    ContentType.MANGA -> mangaModel.lastUpdated
                                    ContentType.NOVELS -> novelModel.lastUpdated
                                    ContentType.ALL -> maxOf(mangaModel.lastUpdated, novelModel.lastUpdated)
                                }
                                updatesLastUpdatedItem(lastUpdated)
                                updateRows(
                                    rows = rows,
                                    selectionMode = selectionMode,
                                    mangaModel = mangaModel,
                                    novelModel = novelModel,
                                    onOpenMangaChapter = onOpenMangaChapter,
                                    onClickMangaCover = onClickMangaCover,
                                    onOpenNovelChapter = onOpenNovelChapter,
                                    onClickNovelCover = onClickNovelCover,
                                    onToggleExpand = { key ->
                                        expandedGroups[key] = !(expandedGroups[key] ?: false)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // RK: one confirmation over the whole selection (manga + novel). Novel chapters were previously
    // deleted with no confirm, so a mixed selection lost the novel files before the manga dialog
    // even appeared. Both models' deleteChapters run only after the user confirms.
    if (showDeleteConfirm) {
        UpdatesDeleteConfirmationDialog(
            onDismissRequest = { showDeleteConfirm = false },
            onConfirm = {
                if (mangaSelected.isNotEmpty()) mangaModel.deleteChapters(mangaSelected)
                if (novelSelected.isNotEmpty()) novelModel.deleteChapters(novelSelected)
            },
        )
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
    onClickNovelCover: (NovelUpdatesItem) -> Unit,
    onToggleExpand: (String) -> Unit,
) {
    items(
        items = rows,
        contentType = {
            when (it) {
                is UpdateRow.Header -> "header"
                is UpdateRow.Manga -> "manga"
                is UpdateRow.Novel -> "novel"
                is UpdateRow.Group -> "group"
                is UpdateRow.Child -> "child"
            }
        },
        key = {
            when (it) {
                is UpdateRow.Header -> "header-${it.date}"
                is UpdateRow.Manga -> "manga-${it.item.update.mangaId}-${it.item.update.chapterId}"
                is UpdateRow.Novel -> "novel-${it.item.update.novelId}-${it.item.update.chapterId}"
                is UpdateRow.Group -> "group-${it.key}"
                is UpdateRow.Child -> "child-${it.member.rowKey()}"
            }
        },
    ) { row ->
        when (row) {
            is UpdateRow.Header -> ListGroupHeader(text = relativeDateText(row.date))
            is UpdateRow.Manga -> {
                val item = row.item
                EntryUpdatesRow(
                    cover = item.update.coverData,
                    title = item.update.mangaTitle,
                    chapterName = item.update.chapterName,
                    read = item.update.read,
                    bookmark = item.update.bookmark,
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
                EntryUpdatesRow(
                    cover = item.update.coverData,
                    title = item.update.novelTitle,
                    chapterName = item.update.chapterName,
                    read = item.update.read,
                    bookmark = item.update.bookmark,
                    selected = item.selected,
                    readProgress = (item.update.lastTextProgress / 100L).toInt()
                        .takeIf { !item.update.read && it > 0 }
                        ?.let { "$it%" },
                    onClick = {
                        if (selectionMode) {
                            novelModel.toggleSelection(item.update.chapterId, !item.selected)
                        } else {
                            onOpenNovelChapter(item)
                        }
                    },
                    onLongClick = { novelModel.toggleSelection(item.update.chapterId, !item.selected) },
                    onClickCover = if (selectionMode) null else ({ onClickNovelCover(item) }),
                    onDownloadChapter = if (selectionMode) {
                        null
                    } else {
                        { action -> novelModel.onDownloadAction(item, action) }
                    },
                    downloadStateProvider = { item.downloadState },
                    downloadProgressProvider = { 0 },
                )
            }
            is UpdateRow.Group -> {
                val groupSelected = row.members.isNotEmpty() && row.members.all { it.memberSelected() }
                val first = row.members.first()
                val toggleAll = {
                    row.members.forEach { toggleMemberSelection(it, !groupSelected, mangaModel, novelModel) }
                }
                UpdatesGroupRow(
                    cover = first.memberCover(),
                    title = first.memberTitle(),
                    count = row.members.size,
                    expanded = row.expanded,
                    selected = groupSelected,
                    anyUnread = row.members.any { !it.memberRead() },
                    onClick = { if (selectionMode) toggleAll() else onToggleExpand(row.key) },
                    onLongClick = toggleAll,
                    // Cover opens the representative series' details, matching the flat row; the rest of
                    // the row still expands the group.
                    onClickCover = if (selectionMode) {
                        null
                    } else {
                        first.memberCoverClick(onClickMangaCover, onClickNovelCover)
                    },
                )
            }
            is UpdateRow.Child -> when (val member = row.member) {
                is UpdateRow.Manga -> {
                    val item = member.item
                    UpdatesGroupChildRow(
                        name = item.update.chapterName,
                        read = item.update.read,
                        selected = item.selected,
                        readProgress = item.update.lastPageRead
                            .takeIf { !item.update.read && it > 0L }
                            ?.let { stringResource(MR.strings.chapter_progress, it + 1) },
                        downloadStateProvider = item.downloadStateProvider,
                        downloadProgressProvider = item.downloadProgressProvider,
                        onClick = {
                            if (selectionMode) {
                                mangaModel.toggleSelection(item, !item.selected, false)
                            } else {
                                onOpenMangaChapter(item)
                            }
                        },
                        onLongClick = { mangaModel.toggleSelection(item, !item.selected, true) },
                        onDownloadClick = if (selectionMode) {
                            null
                        } else {
                            { action -> mangaModel.downloadChapters(listOf(item), action) }
                        },
                    )
                }
                is UpdateRow.Novel -> {
                    val item = member.item
                    UpdatesGroupChildRow(
                        name = item.update.chapterName,
                        read = item.update.read,
                        selected = item.selected,
                        readProgress = (item.update.lastTextProgress / 100L).toInt()
                            .takeIf { !item.update.read && it > 0 }
                            ?.let { "$it%" },
                        downloadStateProvider = { item.downloadState },
                        downloadProgressProvider = { 0 },
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
                else -> {}
            }
        }
    }
}

/** Collapsed "N new chapters" row for a series with several same-date updates. */
@Composable
private fun UpdatesGroupRow(
    cover: Any?,
    title: String,
    count: Int,
    expanded: Boolean,
    selected: Boolean,
    anyUnread: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (anyUnread) 1f else DISABLED_ALPHA
    Row(
        modifier = Modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .height(56.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Square(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxHeight(),
            data = cover,
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            Text(
                text = title,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (anyUnread) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = null,
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(MR.strings.updates_group_chapter_count, count),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Indented, cover-less child chapter row shown when a series group is expanded. */
@Composable
private fun UpdatesGroupChildRow(
    name: String,
    read: Boolean,
    selected: Boolean,
    readProgress: String?,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadClick: ((ChapterDownloadAction) -> Unit)?,
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (read) DISABLED_ALPHA else 1f
    Row(
        modifier = Modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .height(48.dp)
            // Indent so children sit under the group title (cover width + paddings).
            .padding(start = 72.dp, end = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!read) {
            Icon(
                imageVector = Icons.Filled.Circle,
                contentDescription = null,
                modifier = Modifier
                    .height(8.dp)
                    .padding(end = 4.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight = 1f, fill = false),
            )
            if (readProgress != null) {
                DotSeparatorText()
                Text(
                    text = readProgress,
                    maxLines = 1,
                    color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ChapterDownloadIndicator(
            enabled = onDownloadClick != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onClick = { onDownloadClick?.invoke(it) },
        )
    }
}

private sealed interface UpdateRow {
    data class Header(val date: LocalDate) : UpdateRow
    data class Manga(val item: UpdatesItem) : UpdateRow
    data class Novel(val item: NovelUpdatesItem) : UpdateRow
    data class Group(
        val key: String,
        val date: LocalDate,
        val members: List<UpdateRow>,
        val expanded: Boolean,
    ) : UpdateRow
    data class Child(val member: UpdateRow) : UpdateRow
}

private fun UpdateRow.seriesKey(mangaKeys: Map<Long, String>, novelKeys: Map<Long, String>): String = when (this) {
    // Use the merge-group key when known (sources of a merged series share it); else the per-source id.
    is UpdateRow.Manga -> "manga-${mangaKeys[item.update.mangaId] ?: item.update.mangaId}"
    is UpdateRow.Novel -> "novel-${novelKeys[item.update.novelId] ?: item.update.novelId}"
    else -> ""
}

private fun UpdateRow.rowKey(): String = when (this) {
    is UpdateRow.Manga -> "manga-${item.update.mangaId}-${item.update.chapterId}"
    is UpdateRow.Novel -> "novel-${item.update.novelId}-${item.update.chapterId}"
    else -> ""
}

private fun UpdateRow.memberSelected(): Boolean = when (this) {
    is UpdateRow.Manga -> item.selected
    is UpdateRow.Novel -> item.selected
    else -> false
}

private fun UpdateRow.memberRead(): Boolean = when (this) {
    is UpdateRow.Manga -> item.update.read
    is UpdateRow.Novel -> item.update.read
    else -> true
}

private fun UpdateRow.memberCover(): Any? = when (this) {
    is UpdateRow.Manga -> item.update.coverData
    is UpdateRow.Novel -> item.update.coverData
    else -> null
}

private fun UpdateRow.memberTitle(): String = when (this) {
    is UpdateRow.Manga -> item.update.mangaTitle
    is UpdateRow.Novel -> item.update.novelTitle
    else -> ""
}

/** Cover-tap for a group's representative member: open its details via the type's own callback. */
private fun UpdateRow.memberCoverClick(
    onClickMangaCover: (UpdatesItem) -> Unit,
    onClickNovelCover: (NovelUpdatesItem) -> Unit,
): (() -> Unit)? = when (this) {
    is UpdateRow.Manga -> ({ onClickMangaCover(item) })
    is UpdateRow.Novel -> ({ onClickNovelCover(item) })
    else -> null
}

private fun toggleMemberSelection(
    row: UpdateRow,
    selected: Boolean,
    mangaModel: UpdatesScreenModel,
    novelModel: NovelUpdatesScreenModel,
) {
    when (row) {
        is UpdateRow.Manga -> mangaModel.toggleSelection(row.item, selected, false)
        is UpdateRow.Novel -> novelModel.toggleSelection(row.item.update.chapterId, selected)
        else -> {}
    }
}

/**
 * Merge the manga + novel feeds into one date-grouped list, newest first. NOVELS contributes only
 * novel rows; ALL interleaves both by fetch date; MANGA only manga rows. When [groupBySeries] is on,
 * a series' 2+ same-date chapters collapse into one [UpdateRow.Group] (its members follow as
 * [UpdateRow.Child] rows when the group key is in [expandedKeys]); singles stay flat.
 */
private fun buildUpdateRows(
    contentType: ContentType,
    mangaItems: List<UpdatesItem>,
    novelItems: List<NovelUpdatesItem>,
    groupBySeries: Boolean,
    expandedKeys: Set<String>,
    mangaSeriesKeys: Map<Long, String>,
    novelSeriesKeys: Map<Long, String>,
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
    if (!groupBySeries) {
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

    val byDate = LinkedHashMap<LocalDate, MutableList<UpdateRow>>()
    entries.forEach { byDate.getOrPut(it.dateFetch.toLocalDate()) { mutableListOf() }.add(it.row) }
    byDate.forEach { (date, dayRows) ->
        result.add(UpdateRow.Header(date))
        val bySeries = LinkedHashMap<String, MutableList<UpdateRow>>()
        dayRows.forEach {
            bySeries.getOrPut(it.seriesKey(mangaSeriesKeys, novelSeriesKeys)) { mutableListOf() }.add(it)
        }
        bySeries.forEach { (seriesKey, members) ->
            if (members.size >= 2) {
                val key = "$seriesKey@$date"
                val expanded = key in expandedKeys
                result.add(UpdateRow.Group(key = key, date = date, members = members, expanded = expanded))
                if (expanded) members.forEach { result.add(UpdateRow.Child(it)) }
            } else {
                result.add(members.first())
            }
        }
    }
    return result
}
