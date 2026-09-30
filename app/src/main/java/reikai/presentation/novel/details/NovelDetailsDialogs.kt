package reikai.presentation.novel.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.DisplayPage
import eu.kanade.presentation.manga.FilterPage
import eu.kanade.presentation.manga.SetAsDefaultDialog
import eu.kanade.presentation.manga.SortPage
import reikai.domain.novel.model.NovelChapterFlags.SHOW_BOOKMARKED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_DOWNLOADED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_NOT_BOOKMARKED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_NOT_DOWNLOADED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_READ
import reikai.domain.novel.model.NovelChapterFlags.SHOW_UNREAD
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The novel's chapter settings over the pages manga's `ChapterSettingsDialog` renders, so the two dialogs
 * list the same filters in the same order. The sort page hands back the picked mode; which way it then
 * sorts is `ChapterSortPick`, applied by `SetNovelChapterFlags`.
 */
@Composable
fun NovelChapterSettingsDialog(
    sorting: Long,
    sortDescending: Boolean,
    readFilter: Long,
    bookmarkedFilter: Long,
    downloadedFilter: Long,
    downloadedFilterLocked: Boolean,
    hideChapterTitles: Boolean,
    onDismiss: () -> Unit,
    onSortModeChange: (Long) -> Unit,
    onFilterChange: (read: Long, bookmarked: Long, downloaded: Long) -> Unit,
    onDisplayChange: (Boolean) -> Unit,
    onSetAsDefault: (applyToLibrary: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    var showSetAsDefaultDialog by rememberSaveable { mutableStateOf(false) }
    if (showSetAsDefaultDialog) {
        SetAsDefaultDialog(onDismissRequest = { showSetAsDefaultDialog = false }, onConfirmed = onSetAsDefault)
    }

    TabbedDialog(
        onDismissRequest = onDismiss,
        tabTitles = listOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
        tabOverflowMenuContent = { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.set_chapter_settings_as_default)) },
                onClick = {
                    showSetAsDefaultDialog = true
                    closeMenu()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_reset)) },
                onClick = {
                    onReset()
                    closeMenu()
                },
            )
        },
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                // Under the global Downloaded only switch the filter is forced on and not editable, as
                // manga's is. The other two rows still hand back the novel's own setting, never the forced one.
                0 -> FilterPage(
                    downloadFilter = (if (downloadedFilterLocked) SHOW_DOWNLOADED else downloadedFilter)
                        .toTriState(SHOW_DOWNLOADED, SHOW_NOT_DOWNLOADED),
                    onDownloadFilterChanged = { state: TriState ->
                        onFilterChange(readFilter, bookmarkedFilter, state.toFlag(SHOW_DOWNLOADED, SHOW_NOT_DOWNLOADED))
                    }.takeUnless { downloadedFilterLocked },
                    unreadFilter = readFilter.toTriState(SHOW_UNREAD, SHOW_READ),
                    onUnreadFilterChanged = {
                        onFilterChange(it.toFlag(SHOW_UNREAD, SHOW_READ), bookmarkedFilter, downloadedFilter)
                    },
                    bookmarkedFilter = bookmarkedFilter.toTriState(SHOW_BOOKMARKED, SHOW_NOT_BOOKMARKED),
                    onBookmarkedFilterChanged = {
                        onFilterChange(readFilter, it.toFlag(SHOW_BOOKMARKED, SHOW_NOT_BOOKMARKED), downloadedFilter)
                    },
                )
                // The novel sort bits equal manga's, pinned by ReadingOrderConformanceTest.
                1 -> SortPage(sortingMode = sorting, sortDescending = sortDescending, onItemSelected = onSortModeChange)
                2 -> DisplayPage(
                    displayMode = if (hideChapterTitles) Manga.CHAPTER_DISPLAY_NUMBER else Manga.CHAPTER_DISPLAY_NAME,
                    onItemSelected = { onDisplayChange(it == Manga.CHAPTER_DISPLAY_NUMBER) },
                )
            }
        }
    }
}

private fun Long.toTriState(isFlag: Long, notFlag: Long): TriState = when (this) {
    isFlag -> TriState.ENABLED_IS
    notFlag -> TriState.ENABLED_NOT
    else -> TriState.DISABLED
}

private fun TriState.toFlag(isFlag: Long, notFlag: Long): Long = when (this) {
    TriState.DISABLED -> 0L
    TriState.ENABLED_IS -> isFlag
    TriState.ENABLED_NOT -> notFlag
}
