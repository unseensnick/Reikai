package reikai.presentation.library.novels

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.library.LibrarySettingsScreenModel
import reikai.domain.novel.model.NovelLibrarySort
import reikai.presentation.library.ReikaiCategoriesPage
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BaseSortItem
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Novel library settings sheet (P5 S6 slice 4). Mirrors Mihon's `LibrarySettingsDialog` with Filter /
 * Sort / Display tabs (the Group tab is deferred until novel dynamic grouping exists). Filter + Sort
 * bind to [NovelLibraryScreenModel]; the Display tab edits the shared library display prefs through a
 * [LibrarySettingsScreenModel], so it stays in sync with the manga library's Display tab.
 */
@Composable
fun NovelLibrarySettingsDialog(
    onDismissRequest: () -> Unit,
    screenModel: NovelLibraryScreenModel,
    settingsScreenModel: LibrarySettingsScreenModel,
    categoryId: Long,
    initialTab: Int,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.action_filter),
        stringResource(MR.strings.action_sort),
        stringResource(MR.strings.action_display),
    )
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        // The hopper's settings actions can request the manga "Group" tab (index 3); novels have no
        // Group tab, so clamp to a valid page instead of an out-of-range initial page.
        pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, tabTitles.lastIndex)) { tabTitles.size },
        tabTitles = tabTitles,
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterPage(screenModel)
                1 -> SortPage(screenModel, categoryId)
                2 -> NovelDisplayPage(settingsScreenModel)
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(screenModel: NovelLibraryScreenModel) {
    val filters = listOf(
        MR.strings.label_downloaded to screenModel.filterDownloaded,
        MR.strings.action_filter_unread to screenModel.filterUnread,
        MR.strings.label_started to screenModel.filterStarted,
        MR.strings.completed to screenModel.filterCompleted,
        MR.strings.action_filter_bookmarked to screenModel.filterBookmarked,
    )
    filters.forEach { (labelRes, pref) ->
        val state by pref.collectAsState()
        TriStateItem(
            label = stringResource(labelRes),
            state = state,
            onClick = { screenModel.toggleFilter(pref) },
        )
    }
}

private val sortModes: List<Pair<StringResource, NovelLibrarySort.Type>> = listOf(
    MR.strings.action_sort_alpha to NovelLibrarySort.Type.Alphabetical,
    MR.strings.action_sort_last_read to NovelLibrarySort.Type.LastRead,
    MR.strings.action_sort_last_manga_update to NovelLibrarySort.Type.LastUpdate,
    MR.strings.action_sort_latest_chapter to NovelLibrarySort.Type.LatestChapter,
    MR.strings.action_sort_chapter_fetch_date to NovelLibrarySort.Type.ChapterFetchDate,
    MR.strings.action_sort_total to NovelLibrarySort.Type.TotalChapters,
    MR.strings.action_sort_unread_count to NovelLibrarySort.Type.UnreadCount,
    MR.strings.action_sort_date_added to NovelLibrarySort.Type.DateAdded,
    MR.strings.label_downloaded to NovelLibrarySort.Type.Downloaded,
    MR.strings.action_sort_random to NovelLibrarySort.Type.Random,
)

@Composable
private fun ColumnScope.SortPage(screenModel: NovelLibraryScreenModel, categoryId: Long) {
    val state by screenModel.state.collectAsState()
    val current = state.sortFor(categoryId)
    val sortDescending = !current.isAscending

    sortModes.forEach { (labelRes, mode) ->
        if (mode == NovelLibrarySort.Type.Random) {
            BaseSortItem(
                label = stringResource(labelRes),
                icon = Icons.Default.Refresh.takeIf { current.type == NovelLibrarySort.Type.Random },
                onClick = { screenModel.setSort(categoryId, mode, isAscending = true) },
            )
            return@forEach
        }
        SortItem(
            label = stringResource(labelRes),
            sortDescending = sortDescending.takeIf { current.type == mode },
            onClick = {
                // Toggling the active mode flips direction; switching modes keeps the current direction.
                val isAscending = if (current.type == mode) sortDescending else current.isAscending
                screenModel.setSort(categoryId, mode, isAscending)
            },
        )
    }
}

private val displayModes = listOf(
    MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
    MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
    MR.strings.action_display_list to LibraryDisplayMode.List,
    MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
    MR.strings.action_display_comfortable_grid_panorama to LibraryDisplayMode.ComfortableGridPanorama,
)

/**
 * Novel Display tab: the shared display prefs (display mode, columns, badges, tabs) plus continue
 * reading and the Reikai category/hopper settings. Omits the manga-only merge toggles (those land
 * with the novel merge system in S8).
 */
@Composable
private fun ColumnScope.NovelDisplayPage(screenModel: LibrarySettingsScreenModel) {
    val displayMode by screenModel.libraryPreferences.displayMode.collectAsState()
    SettingsChipRow(MR.strings.action_display_mode) {
        displayModes.map { (titleRes, mode) ->
            FilterChip(
                selected = displayMode == mode,
                onClick = { screenModel.setDisplayMode(mode) },
                label = { Text(stringResource(titleRes)) },
            )
        }
    }

    if (displayMode != LibraryDisplayMode.List) {
        val configuration = LocalConfiguration.current
        val columnPreference = remember {
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                screenModel.libraryPreferences.landscapeColumns
            } else {
                screenModel.libraryPreferences.portraitColumns
            }
        }
        val columns by columnPreference.collectAsState()
        SliderItem(
            value = columns,
            valueRange = 0..10,
            label = stringResource(MR.strings.pref_library_columns),
            valueString = if (columns > 0) columns.toString() else stringResource(MR.strings.label_auto),
            onChange = columnPreference::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    HeadingItem(MR.strings.overlay_header)
    CheckboxItem(
        label = stringResource(MR.strings.action_display_download_badge),
        pref = screenModel.libraryPreferences.downloadBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_unread_badge),
        pref = screenModel.libraryPreferences.unreadBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_language_badge),
        pref = screenModel.libraryPreferences.languageBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_source_badge),
        pref = screenModel.reikaiLibraryPreferences.sourceBadge,
    )
    // RK --> novel merge toggles (P5 S8). Source-icons toggle + rendering land in S8b.
    CheckboxItem(
        label = stringResource(MR.strings.action_merge_same_title),
        pref = screenModel.reikaiLibraryPreferences.novelAutoMergeSameTitle,
    )
    val autoMergeNovels by screenModel.reikaiLibraryPreferences.novelAutoMergeSameTitle.collectAsState()
    if (autoMergeNovels) {
        CheckboxItem(
            label = stringResource(MR.strings.action_merge_require_author),
            pref = screenModel.reikaiLibraryPreferences.novelAutoMergeRequireAuthor,
        )
    }
    // RK <--
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_continue_reading_button),
        pref = screenModel.libraryPreferences.showContinueReadingButton,
    )

    HeadingItem(MR.strings.tabs_header)
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_all_categories),
        pref = screenModel.reikaiLibraryPreferences.showAllCategories,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_tabs),
        pref = screenModel.libraryPreferences.categoryTabs,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_number_of_items),
        pref = screenModel.libraryPreferences.categoryNumberOfItems,
    )

    ReikaiCategoriesPage(screenModel = screenModel)
}
