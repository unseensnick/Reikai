package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelPreferences
import yokai.i18n.MR
import yokai.presentation.library.settings.FilterReorderList

/**
 * Filter tab content. Renders one segmented-button row per filter in `preferences.filterOrder()`
 * order. Reads / writes the same `PreferencesHelper` int keys the legacy `FilterBottomSheet` uses,
 * so the two paths stay in sync.
 *
 * Conditional rows:
 * - **Series type** appears only when [detectedMangaTypes] is non-empty (i.e. the library
 *   contains entries beyond TYPE_MANGA). Mirrors `FilterBottomSheet.checkForManhwa`.
 * - **Tracker selection** appears below the Tracked row only when [loggedTrackerNames] has more
 *   than one entry and Tracked is set to INCLUDE. Mirrors the legacy `trackers` chip group.
 *
 * A Reorder toggle at the top-right swaps the segmented rows for [FilterReorderList]; order is
 * saved to `preferences.filterOrder()` on each nudge. Footer carries Clear filters + Apply (filter
 * prefs are written live as options are tapped, so Apply just dismisses the sheet).
 */
@Composable
fun FilterTab(
    detectedMangaTypes: Set<Int> = emptySet(),
    loggedTrackerNames: List<String> = emptyList(),
    filterOrder: String,
    onFilterOrderChanged: (String) -> Unit,
    onApply: () -> Unit,
    /** True when this tab is rendered inside the Novels-tab Display sheet. Routes the
     *  shareable filter chips through novelPrefs when shared mode is off, and hides the
     *  manga-only Series type and Content type rows in both modes. */
    isNovelTab: Boolean = false,
) {
    val preferences: PreferencesHelper = remember { Injekt.get() }
    val novelPrefs: NovelPreferences = remember { Injekt.get() }
    // Filter values are per-library state ("show me unread novels" shouldn't also filter the manga
    // library), so route by tab regardless of the shared display-prefs toggle.
    val routeToNovel = isNovelTab

    val filterUnreadPref = rememberRoutedPref(routeToNovel, preferences.filterUnread(), novelPrefs.filterUnread())
    val filterUnread by filterUnreadPref.collectAsState()
    val filterDownloadedPref = rememberRoutedPref(routeToNovel, preferences.filterDownloaded(), novelPrefs.filterDownloaded())
    val filterDownloaded by filterDownloadedPref.collectAsState()
    val filterCompletedPref = rememberRoutedPref(routeToNovel, preferences.filterCompleted(), novelPrefs.filterCompleted())
    val filterCompleted by filterCompletedPref.collectAsState()
    val filterBookmarkedPref = rememberRoutedPref(routeToNovel, preferences.filterBookmarked(), novelPrefs.filterBookmarked())
    val filterBookmarked by filterBookmarkedPref.collectAsState()
    val filterContentType by preferences.filterContentType().collectAsState()
    val filterMangaType by preferences.filterMangaType().collectAsState()
    val filterTrackedPref = rememberRoutedPref(routeToNovel, preferences.filterTracked(), novelPrefs.filterTracked())
    val filterTracked by filterTrackedPref.collectAsState()

    // FILTER_TRACKER is the JVM static String the legacy sheet stores the selected tracker name in.
    // Not a Flow, so mirror it into state that resets on each entry and writes back on select.
    var trackerName by remember { mutableStateOf(FilterBottomSheet.FILTER_TRACKER) }
    // Reorder mode lives here now (the global sheet header no longer carries the toggle).
    var reorderMode by rememberSaveable { mutableStateOf(false) }

    val clearAll = {
        filterUnreadPref.set(0)
        filterDownloadedPref.set(0)
        filterCompletedPref.set(0)
        filterBookmarkedPref.set(0)
        if (!isNovelTab) {
            // The manga-only filters don't exist on the novel side, so don't reach across to
            // mutate them from the Novels tab even though the prefs are physically reachable.
            preferences.filterContentType().set(0)
            preferences.filterMangaType().set(0)
        }
        filterTrackedPref.set(0)
        FilterBottomSheet.FILTER_TRACKER = ""
        trackerName = ""
    }

    // One scroll column (no weight-based footer pinning): a weight(fill=false) child fed its
    // height back into the pager's animateContentSize, which oscillated and flickered while the
    // sheet grew. A single scroll wrapping everything keeps the measured height stable so the
    // height animation is smooth.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    ) {
        // Reorder toggle, top-right and contextual to this tab.
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { reorderMode = !reorderMode }) {
                Icon(
                    imageVector = Icons.Outlined.SwapVert,
                    contentDescription = null,
                    tint = if (reorderMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(MR.strings.reorder),
                    color = if (reorderMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (reorderMode) {
                FilterReorderList(
                    order = filterOrder,
                    onOrderChanged = onFilterOrderChanged,
                )
                return@Column
            }

            for (c in filterOrder.toCharArray().distinct()) {
                when (FilterBottomSheet.Filters.filterOf(c)) {
                    FilterBottomSheet.Filters.UnreadProgress -> FilterSegmentedRow(
                        label = stringResource(MR.strings.read_progress),
                        options = listOf(
                            0 to stringResource(MR.strings.all),
                            // Legacy maps pref 3 -> "Not started", 4 -> "In progress".
                            3 to stringResource(MR.strings.not_started),
                            4 to stringResource(MR.strings.in_progress),
                        ),
                        selected = if (filterUnread in setOf(3, 4)) filterUnread else 0,
                        onSelect = { filterUnreadPref.set(it) },
                    )
                    FilterBottomSheet.Filters.Unread -> FilterSegmentedRow(
                        label = stringResource(MR.strings.unread),
                        options = listOf(
                            0 to stringResource(MR.strings.all),
                            1 to stringResource(MR.strings.unread),
                            2 to stringResource(MR.strings.read),
                        ),
                        selected = if (filterUnread in setOf(1, 2)) filterUnread else 0,
                        onSelect = { filterUnreadPref.set(it) },
                    )
                    FilterBottomSheet.Filters.Downloaded -> FilterSegmentedRow(
                        label = stringResource(MR.strings.downloaded),
                        options = listOf(
                            0 to stringResource(MR.strings.all),
                            1 to stringResource(MR.strings.downloaded),
                            2 to stringResource(MR.strings.not_downloaded),
                        ),
                        selected = filterDownloaded,
                        onSelect = { filterDownloadedPref.set(it) },
                    )
                    FilterBottomSheet.Filters.Completed -> FilterSegmentedRow(
                        label = stringResource(MR.strings.status),
                        options = listOf(
                            0 to stringResource(MR.strings.all),
                            1 to stringResource(MR.strings.completed),
                            2 to stringResource(MR.strings.ongoing),
                        ),
                        selected = filterCompleted,
                        onSelect = { filterCompletedPref.set(it) },
                    )
                    // Manga-only: novels don't carry a series-type field, so hide on the Novels tab.
                    FilterBottomSheet.Filters.SeriesType -> {
                        if (!isNovelTab && detectedMangaTypes.isNotEmpty()) {
                            FilterSegmentedRow(
                                label = stringResource(MR.strings.series_type),
                                options = buildList {
                                    add(0 to stringResource(MR.strings.all))
                                    add(Manga.TYPE_MANGA to stringResource(MR.strings.manga))
                                    if (Manga.TYPE_MANHWA in detectedMangaTypes) {
                                        add(Manga.TYPE_MANHWA to stringResource(MR.strings.manhwa))
                                    }
                                    if (Manga.TYPE_MANHUA in detectedMangaTypes) {
                                        add(Manga.TYPE_MANHUA to stringResource(MR.strings.manhua))
                                    }
                                    if (Manga.TYPE_COMIC in detectedMangaTypes) {
                                        add(Manga.TYPE_COMIC to stringResource(MR.strings.comic))
                                    }
                                },
                                selected = filterMangaType,
                                onSelect = { preferences.filterMangaType().set(it) },
                            )
                        }
                    }
                    FilterBottomSheet.Filters.Bookmarked -> FilterSegmentedRow(
                        label = stringResource(MR.strings.bookmarked),
                        options = listOf(
                            0 to stringResource(MR.strings.all),
                            1 to stringResource(MR.strings.bookmarked),
                            2 to stringResource(MR.strings.not_bookmarked),
                        ),
                        selected = filterBookmarked,
                        onSelect = { filterBookmarkedPref.set(it) },
                    )
                    FilterBottomSheet.Filters.Tracked -> {
                        if (loggedTrackerNames.isNotEmpty()) {
                            FilterSegmentedRow(
                                label = stringResource(MR.strings.tracking),
                                options = listOf(
                                    0 to stringResource(MR.strings.all),
                                    1 to stringResource(MR.strings.tracked),
                                    2 to stringResource(MR.strings.not_tracked),
                                ),
                                selected = filterTracked,
                                onSelect = { filterTrackedPref.set(it) },
                            )
                            if (filterTracked == 1 && loggedTrackerNames.size > 1) {
                                FilterSegmentedRow(
                                    label = stringResource(MR.strings.trackers),
                                    options = buildList {
                                        add(-1 to stringResource(MR.strings.all))
                                        loggedTrackerNames.forEachIndexed { i, name -> add(i to name) }
                                    },
                                    selected = loggedTrackerNames.indexOf(trackerName).takeIf { it >= 0 } ?: -1,
                                    onSelect = { idx ->
                                        val name = if (idx >= 0) loggedTrackerNames[idx] else ""
                                        trackerName = name
                                        FilterBottomSheet.FILTER_TRACKER = name
                                    },
                                )
                            }
                        }
                    }
                    // Manga-only: novels don't carry the SFW/NSFW content-type dimension.
                    FilterBottomSheet.Filters.ContentType -> {
                        if (!isNovelTab) {
                            FilterSegmentedRow(
                                label = stringResource(MR.strings.content_type),
                                options = listOf(
                                    0 to stringResource(MR.strings.all),
                                    1 to stringResource(MR.strings.sfw),
                                    2 to stringResource(MR.strings.nsfw),
                                ),
                                selected = filterContentType,
                                onSelect = { preferences.filterContentType().set(it) },
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = clearAll, modifier = Modifier.weight(1f)) {
                Text(stringResource(MR.strings.clear_filters))
            }
            Button(onClick = onApply, modifier = Modifier.weight(1f)) {
                Text(stringResource(MR.strings.apply))
            }
        }
    }
}

/**
 * One filter as a label over a full-width single-choice segmented button group. Replaces the old
 * loose chip rows: the connected segments read clearly as a mutually-exclusive choice.
 */
@Composable
private fun FilterSegmentedRow(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, text) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
