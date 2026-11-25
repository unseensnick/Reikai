package reikai.presentation.novel.details

import androidx.compose.runtime.Composable
import eu.kanade.presentation.manga.ChapterSettingsTabs
import eu.kanade.presentation.manga.DisplayPage
import eu.kanade.presentation.manga.FilterPage
import eu.kanade.presentation.manga.SortPage
import reikai.domain.novel.model.NovelChapterFlags.SHOW_BOOKMARKED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_DOWNLOADED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_NOT_BOOKMARKED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_NOT_DOWNLOADED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_READ
import reikai.domain.novel.model.NovelChapterFlags.SHOW_UNREAD
import reikai.domain.reader.ChapterListFilters
import reikai.domain.reader.toFlag
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga

/**
 * The novel's chapter settings in the tabs and pages manga's `ChapterSettingsDialog` draws
 * ([ChapterSettingsTabs], [FilterPage]), so the two dialogs list the same things in the same order. The sort page hands back the picked mode; which way it then
 * sorts is `ChapterSortPick`, applied by `SetNovelChapterFlags`.
 */
@Composable
fun NovelChapterSettingsDialog(
    sorting: Long,
    sortDescending: Boolean,
    filters: ChapterListFilters,
    downloadedFilterLocked: Boolean,
    hideChapterTitles: Boolean,
    onDismiss: () -> Unit,
    onSortModeChange: (Long) -> Unit,
    onReadFilterChange: (Long) -> Unit,
    onBookmarkedFilterChange: (Long) -> Unit,
    onDownloadedFilterChange: (Long) -> Unit,
    onDisplayChange: (Boolean) -> Unit,
    onSetAsDefault: (applyToLibrary: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    ChapterSettingsTabs(
        onDismissRequest = onDismiss,
        onSetAsDefault = onSetAsDefault,
        onReset = onReset,
        // Under the global Downloaded only switch the filter shows forced on and is not editable, as
        // manga's is. Each row writes its own filter only, so the forced one is never saved.
        filterPage = {
            FilterPage(
                downloadFilter = filters.downloaded,
                onDownloadFilterChanged = { state: TriState ->
                    onDownloadedFilterChange(state.toFlag(SHOW_DOWNLOADED, SHOW_NOT_DOWNLOADED))
                }.takeUnless { downloadedFilterLocked },
                unreadFilter = filters.unread,
                onUnreadFilterChanged = {
                    onReadFilterChange(it.toFlag(SHOW_UNREAD, SHOW_READ))
                },
                bookmarkedFilter = filters.bookmarked,
                onBookmarkedFilterChanged = {
                    onBookmarkedFilterChange(it.toFlag(SHOW_BOOKMARKED, SHOW_NOT_BOOKMARKED))
                },
            )
        },
        // The novel sort bits equal manga's, pinned by ReadingOrderConformanceTest.
        sortPage = {
            SortPage(sortingMode = sorting, sortDescending = sortDescending, onItemSelected = onSortModeChange)
        },
        displayPage = {
            DisplayPage(
                displayMode = if (hideChapterTitles) Manga.CHAPTER_DISPLAY_NUMBER else Manga.CHAPTER_DISPLAY_NAME,
                onItemSelected = { onDisplayChange(it == Manga.CHAPTER_DISPLAY_NUMBER) },
            )
        },
    )
}
