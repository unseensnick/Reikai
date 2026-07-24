package reikai.presentation.library.novels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.library.LibrarySettingsScreenModel
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import reikai.presentation.library.EntryDisplayPage
import reikai.presentation.library.LibraryGroup
import reikai.presentation.library.ResetToGlobalSortItem
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BaseSortItem
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Novel library settings sheet. Mirrors Mihon's `LibrarySettingsDialog` with Filter /
 * Sort / Display / Group tabs. Filter + Sort + Group bind to [NovelLibraryScreenModel]; the Display tab
 * edits the shared library display prefs through a [LibrarySettingsScreenModel], so it stays in sync
 * with the manga library's Display tab.
 */
@Composable
fun NovelLibrarySettingsDialog(
    onDismissRequest: () -> Unit,
    screenModel: NovelLibraryScreenModel,
    settingsScreenModel: LibrarySettingsScreenModel,
    categoryId: Long,
    initialTab: Int,
    onManageCategories: () -> Unit,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.action_filter),
        stringResource(MR.strings.action_sort),
        stringResource(MR.strings.action_display),
        stringResource(MR.strings.group),
    )
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        // The hopper's settings actions can request the Group tab (index 3) directly.
        pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, tabTitles.lastIndex)) { tabTitles.size },
        tabTitles = tabTitles,
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterPage(screenModel, onManageCategories)
                1 -> SortPage(screenModel, categoryId)
                2 -> NovelDisplayPage(settingsScreenModel)
                3 -> NovelGroupPage(screenModel)
            }
        }
    }
}

private val novelGroupModes = listOf(
    LibraryGroup.BY_DEFAULT to MR.strings.group_by_default,
    LibraryGroup.BY_TAG to MR.strings.group_by_tag,
    LibraryGroup.BY_SOURCE to MR.strings.group_by_source,
    LibraryGroup.BY_STATUS to MR.strings.group_by_status,
    LibraryGroup.BY_TRACK_STATUS to MR.strings.group_by_tracking_status,
    LibraryGroup.BY_AUTHOR to MR.strings.group_by_author,
    LibraryGroup.BY_LANGUAGE to MR.strings.group_by_language,
    LibraryGroup.UNGROUPED to MR.strings.group_ungrouped,
)

@Composable
private fun ColumnScope.NovelGroupPage(screenModel: NovelLibraryScreenModel) {
    val groupBy by screenModel.groupLibraryBy.collectAsState()
    novelGroupModes.forEach { (mode, labelRes) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { screenModel.setGrouping(mode) }
                .padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = SettingsItemsPaddings.Vertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            RadioButton(selected = groupBy == mode, onClick = null)
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    screenModel: NovelLibraryScreenModel,
    onManageCategories: () -> Unit,
) {
    // Downloaded is rendered on its own so the global "Downloaded only" mode can force it on and lock it
    // (mirrors the manga LibrarySettingsDialog).
    val downloadedOnly by screenModel.downloadedOnly.collectAsState()
    val filterDownloaded by screenModel.filterDownloaded.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = if (downloadedOnly) TriState.ENABLED_IS else filterDownloaded,
        enabled = !downloadedOnly,
        onClick = { screenModel.toggleFilter(screenModel.filterDownloaded) },
    )
    val filters = listOf(
        MR.strings.action_filter_unread to screenModel.filterUnread,
        MR.strings.label_started to screenModel.filterStarted,
        MR.strings.completed to screenModel.filterCompleted,
        MR.strings.action_filter_bookmarked to screenModel.filterBookmarked,
        // Adult-content filter, genre-tag based for novels (novel sources carry no nsfw flag); above Tracked.
        MR.strings.lewd to screenModel.filterLewd,
    )
    filters.forEach { (labelRes, pref) ->
        val state by pref.collectAsState()
        TriStateItem(
            label = stringResource(labelRes),
            state = state,
            onClick = { screenModel.toggleFilter(pref) },
        )
    }

    // Per-logged-in-tracker filter (mirrors the manga LibrarySettingsDialog: one row, or a heading + row
    // per tracker). Bound to the novel model's own tracker prefs, not the shared settings model.
    val trackers by screenModel.trackersFlow.collectAsState()
    when (trackers.size) {
        0 -> {
            // No logged-in trackers: nothing to filter by.
        }
        1 -> {
            val service = trackers[0]
            val filterTracker by screenModel.novelFilterTracking(service.id.toInt()).collectAsState()
            TriStateItem(
                label = stringResource(MR.strings.action_filter_tracked),
                state = filterTracker,
                onClick = { screenModel.toggleNovelTracker(service.id.toInt()) },
            )
        }
        else -> {
            HeadingItem(MR.strings.action_filter_tracked)
            trackers.map { service ->
                val filterTracker by screenModel.novelFilterTracking(service.id.toInt()).collectAsState()
                TriStateItem(
                    label = service.name,
                    state = filterTracker,
                    onClick = { screenModel.toggleNovelTracker(service.id.toInt()) },
                )
            }
        }
    }

    NovelCategoriesFilter(screenModel = screenModel, onManageCategories = onManageCategories)
}

@Composable
private fun ColumnScope.SortPage(screenModel: NovelLibraryScreenModel, categoryId: Long) {
    val state by screenModel.state.collectAsState()
    val trackers by screenModel.trackersFlow.collectAsState()
    val current = state.sortFor(categoryId)
    val sortDescending = !current.isAscending

    // Tracker-score sort only shows with a logged-in tracker (mirrors the manga LibrarySettingsDialog).
    val sortModes = remember(trackers.isEmpty()) {
        val trackerMeanPair = if (trackers.isNotEmpty()) {
            MR.strings.action_sort_tracker_score to LibrarySort.Type.TrackerMean
        } else {
            null
        }
        listOfNotNull(
            MR.strings.action_sort_alpha to LibrarySort.Type.Alphabetical,
            MR.strings.action_sort_last_read to LibrarySort.Type.LastRead,
            MR.strings.action_sort_last_manga_update to LibrarySort.Type.LastUpdate,
            MR.strings.action_sort_latest_chapter to LibrarySort.Type.LatestChapter,
            MR.strings.action_sort_chapter_fetch_date to LibrarySort.Type.ChapterFetchDate,
            MR.strings.action_sort_total to LibrarySort.Type.TotalChapters,
            MR.strings.action_sort_unread_count to LibrarySort.Type.UnreadCount,
            MR.strings.action_sort_date_added to LibrarySort.Type.DateAdded,
            MR.strings.label_downloaded to LibrarySort.Type.Downloaded,
            trackerMeanPair,
            MR.strings.action_sort_random to LibrarySort.Type.Random,
        )
    }

    sortModes.forEach { (labelRes, mode) ->
        if (mode == LibrarySort.Type.Random) {
            BaseSortItem(
                label = stringResource(labelRes),
                icon = Icons.Default.Refresh.takeIf { current.type == LibrarySort.Type.Random },
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

    // RK: clear this category's override so it follows the global sort again (only when overridden).
    if ((state.flagsForCategory(categoryId) and CATEGORY_SORT_CUSTOMIZED) != 0L) {
        ResetToGlobalSortItem(onClick = { screenModel.resetSort(categoryId) })
    }
}

/**
 * Novel Display tab: renders through the shared [EntryDisplayPage]. Omits the manga-only local badge
 * (`showLocalBadge = false`) and fills the shared merge-toggle slot with the novel merge toggles
 * (auto-merge, the conditional require-author, and source icons).
 */
@Composable
private fun ColumnScope.NovelDisplayPage(screenModel: LibrarySettingsScreenModel) {
    EntryDisplayPage(
        screenModel = screenModel,
        showLocalBadge = false,
        mergeToggles = {
            // RK: master switch (also in Settings). The same-title suggestion + require-author toggles
            // moved to Settings.
            CheckboxItem(
                label = stringResource(MR.strings.action_series_merging),
                pref = screenModel.reikaiLibraryPreferences.seriesMergingEnabled,
            )
            CheckboxItem(
                label = stringResource(MR.strings.action_merge_source_icons),
                pref = screenModel.reikaiLibraryPreferences.showNovelMergeSourceIcons,
            )
        },
    )
}
