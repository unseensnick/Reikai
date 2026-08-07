package reikai.presentation.library

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
import eu.kanade.tachiyomi.ui.library.LibrarySettingsViewModel
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import reikai.domain.library.sortForCategory
import reikai.presentation.category.CategoryFilterRow
import reikai.presentation.category.CategoryFilterSection
import reikai.presentation.category.toLongIdSet
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
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
 * The library settings sheet, shared by both content types. Filter, Sort and Group render whatever the
 * active [LibrarySettingsBinding] describes, so a change reaches manga and novels at once and neither
 * can gain an option the other silently misses. [settingsViewModel] backs the Display tab, the
 * logged-in tracker list and the global "Downloaded only" mode, all library-wide. A null [categoryId]
 * is the global scope, and the Default category resolves to it too: that row is universal, so a sort
 * override on it could not mean one thing for manga and another for novels.
 */
@Composable
fun LibrarySettingsSheet(
    settings: LibrarySettingsBinding,
    settingsViewModel: LibrarySettingsViewModel,
    categoryId: Long?,
    initialTab: Int,
    onManageCategories: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.action_filter),
        stringResource(MR.strings.action_sort),
        stringResource(MR.strings.action_display),
        stringResource(MR.strings.group),
    )
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        // The hopper's settings actions and a category header can request a tab directly.
        pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, tabTitles.lastIndex)) { tabTitles.size },
        tabTitles = tabTitles,
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterPage(settings, settingsViewModel, onManageCategories)
                1 -> SortPage(settings, settingsViewModel, categoryId)
                2 -> EntryDisplayPage(
                    viewModel = settingsViewModel,
                    showLocalBadge = settings.showLocalBadge,
                    mergeToggles = {
                        // Master switch (also in Settings); the same-title suggestion moved there too.
                        CheckboxItem(
                            label = stringResource(MR.strings.action_series_merging),
                            pref = settingsViewModel.reikaiLibraryPreferences.seriesMergingEnabled,
                        )
                        CheckboxItem(
                            label = stringResource(MR.strings.action_merge_source_icons),
                            pref = settings.mergeSourceIcons,
                        )
                    },
                )
                3 -> GroupPage(settings)
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    settings: LibrarySettingsBinding,
    settingsViewModel: LibrarySettingsViewModel,
    onManageCategories: () -> Unit,
) {
    val axes by settings.filterAxes.collectAsState()
    val downloadedOnly by settingsViewModel.preferences.downloadedOnly.collectAsState()

    axes.forEach { axis ->
        // The global Downloaded-only mode forces its axis on and locks it, so the sheet cannot promise a
        // filter the mode overrides.
        val locked = axis.lockedByDownloadedOnly && downloadedOnly
        val state by axis.preference.collectAsState()
        TriStateItem(
            label = stringResource(axis.labelRes),
            state = if (locked) TriState.ENABLED_IS else state,
            enabled = !locked,
            onClick = { next -> axis.preference.set(next) },
        )
    }

    val trackers by settingsViewModel.trackersFlow.collectAsState()
    when (trackers.size) {
        0 -> Unit // No logged-in trackers: nothing to filter by.
        1 -> {
            val tracker = trackers[0]
            val filterTracker by settings.trackerFilter(tracker.id.toInt()).collectAsState()
            TriStateItem(
                label = stringResource(MR.strings.action_filter_tracked),
                state = filterTracker,
                onClick = { next -> settings.trackerFilter(tracker.id.toInt()).set(next) },
            )
        }
        else -> {
            HeadingItem(MR.strings.action_filter_tracked)
            trackers.forEach { tracker ->
                val filterTracker by settings.trackerFilter(tracker.id.toInt()).collectAsState()
                TriStateItem(
                    label = tracker.name,
                    state = filterTracker,
                    onClick = { next -> settings.trackerFilter(tracker.id.toInt()).set(next) },
                )
            }
        }
    }

    CategoriesFilter(settings, onManageCategories)
}

@Composable
private fun ColumnScope.CategoriesFilter(
    settings: LibrarySettingsBinding,
    onManageCategories: () -> Unit,
) {
    val categoryFilter = settings.categoryFilter
    val enabled by categoryFilter.enabled.collectAsState()
    val included by categoryFilter.included.collectAsState()
    val excluded by categoryFilter.excluded.collectAsState()
    val categories by settings.categories.collectAsState()

    CategoryFilterRow(
        enabled = enabled,
        onToggleEnabled = categoryFilter.enabled::set,
        sections = listOf(
            CategoryFilterSection(
                headingRes = null,
                categories = categories,
                included = included.toLongIdSet(),
                excluded = excluded.toLongIdSet(),
                onConfirm = { include, exclude ->
                    categoryFilter.included.set(include.mapTo(mutableSetOf()) { it.toString() })
                    categoryFilter.excluded.set(exclude.mapTo(mutableSetOf()) { it.toString() })
                },
            ),
        ),
        onManageCategories = onManageCategories,
    )
}

@Composable
private fun ColumnScope.SortPage(
    settings: LibrarySettingsBinding,
    settingsViewModel: LibrarySettingsViewModel,
    categoryId: Long?,
) {
    val trackers by settingsViewModel.trackersFlow.collectAsState()
    val globalSort by settings.globalSort.collectAsState()
    val categories by settings.categories.collectAsState()

    // The Default row is universal, so it has no override of its own and follows the global sort.
    val scopeId = categoryId?.takeUnless { it == Category.UNCATEGORIZED_ID }
    val flags = scopeId?.let { id -> categories.find { it.id == id }?.flags } ?: 0L
    val currentSort = sortForCategory(flags, globalSort)
    val sortDescending = !currentSort.isAscending

    // Tracker-score sort only shows with a logged-in tracker: nothing else could score an entry.
    val options = remember(trackers.isEmpty()) {
        listOfNotNull(
            MR.strings.action_sort_alpha to LibrarySort.Type.Alphabetical,
            MR.strings.action_sort_total to LibrarySort.Type.TotalChapters,
            MR.strings.action_sort_last_read to LibrarySort.Type.LastRead,
            MR.strings.action_sort_last_manga_update to LibrarySort.Type.LastUpdate,
            MR.strings.action_sort_unread_count to LibrarySort.Type.UnreadCount,
            MR.strings.action_sort_latest_chapter to LibrarySort.Type.LatestChapter,
            MR.strings.action_sort_chapter_fetch_date to LibrarySort.Type.ChapterFetchDate,
            MR.strings.action_sort_date_added to LibrarySort.Type.DateAdded,
            (MR.strings.action_sort_tracker_score to LibrarySort.Type.TrackerMean).takeIf { trackers.isNotEmpty() },
            MR.strings.action_sort_downloaded to LibrarySort.Type.Downloaded,
            MR.strings.action_sort_random to LibrarySort.Type.Random,
        )
    }

    options.forEach { (titleRes, mode) ->
        if (mode == LibrarySort.Type.Random) {
            BaseSortItem(
                label = stringResource(titleRes),
                icon = Icons.Default.Refresh.takeIf { currentSort.type == LibrarySort.Type.Random },
                onClick = { settings.setSort(scopeId, mode, LibrarySort.Direction.Ascending) },
            )
            return@forEach
        }
        SortItem(
            label = stringResource(titleRes),
            sortDescending = sortDescending.takeIf { currentSort.type == mode },
            onClick = {
                // Tapping the active mode flips direction; switching modes keeps the current one.
                val direction = if (currentSort.type == mode) {
                    if (sortDescending) LibrarySort.Direction.Ascending else LibrarySort.Direction.Descending
                } else {
                    currentSort.direction
                }
                settings.setSort(scopeId, mode, direction)
            },
        )
    }

    // Clear this category's override so it follows the global sort again (only when overridden).
    if (scopeId != null && (flags and CATEGORY_SORT_CUSTOMIZED) != 0L) {
        ResetToGlobalSortItem(onClick = { settings.resetSort(scopeId) })
    }
}

private val groupModes = listOf(
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
private fun ColumnScope.GroupPage(settings: LibrarySettingsBinding) {
    val groupMode = settings.groupMode
    val groupBy by groupMode.collectAsState()
    groupModes.forEach { (mode, labelRes) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { groupMode.set(mode) }
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
