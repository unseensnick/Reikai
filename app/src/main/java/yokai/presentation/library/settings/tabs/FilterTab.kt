package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import yokai.presentation.library.components.FilterChipOption
import yokai.presentation.library.components.FilterChipRow
import yokai.presentation.library.settings.FilterReorderList

/**
 * Filter tab content. Renders one FilterChipRow per filter in `preferences.filterOrder()` order.
 * Reads / writes the same `PreferencesHelper` int keys the legacy `FilterBottomSheet` uses, so
 * the two paths stay in sync.
 *
 * Conditional rows:
 * - **Series type** appears only when [detectedMangaTypes] is non-empty (i.e. the library
 *   contains entries beyond TYPE_MANGA). Mirrors `FilterBottomSheet.checkForManhwa`.
 * - **Tracker selection** appears below the Tracked row only when [loggedTrackerNames] has more
 *   than one entry and Tracked is set to INCLUDE. Mirrors the legacy `trackers` chip group.
 *
 * Reorder mode replaces the chip rows with [FilterReorderList], letting the user nudge filters
 * up or down. Order is saved to `preferences.filterOrder()` on each nudge.
 *
 * Footer carries Clear filters + Apply, matching the legacy filter sheet's bottom action row.
 * Filter prefs are written live as chips are tapped, so Apply just dismisses the sheet; it
 * exists for users who expect an explicit confirm action.
 */
@Composable
fun FilterTab(
    detectedMangaTypes: Set<Int> = emptySet(),
    loggedTrackerNames: List<String> = emptyList(),
    reorderMode: Boolean = false,
    filterOrder: String,
    onFilterOrderChanged: (String) -> Unit,
    onApply: () -> Unit,
    /** True when this tab is rendered inside the Novels-tab Display sheet. Routes the
     *  shareable filter chips through novelPrefs when shared mode is off, and hides the
     *  manga-only Series type and Content type chips in both modes. */
    isNovelTab: Boolean = false,
) {
    val preferences: PreferencesHelper = remember { Injekt.get() }
    val novelPrefs: NovelPreferences = remember { Injekt.get() }
    // Filter chip values are per-library state ("show me unread novels" shouldn't also filter
    // the manga library), so route by tab regardless of the shared display-prefs toggle.
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

    // FILTER_TRACKER is the JVM static String the legacy sheet stores the selected tracker
    // name in. Not a Flow, so we mirror it into a state that resets on each FilterTab entry
    // and writes back to the companion on chip select. Both paths read the same companion so
    // they stay synchronised.
    var trackerName by remember { mutableStateOf(FilterBottomSheet.FILTER_TRACKER) }

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

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
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
                    FilterBottomSheet.Filters.UnreadProgress -> FilterChipRow(
                        label = stringResource(MR.strings.read_progress),
                        options = listOf(
                            FilterChipOption(0, stringResource(MR.strings.all)),
                            // Legacy FilterBottomSheet.setFilterStates maps pref 3 -> "Not started",
                            // pref 4 -> "In progress" (unreadProgress.setup(R.string.not_started,
                            // R.string.in_progress) with state = unreadP - 3). Preserve that mapping
                            // so existing prefs continue to point at the right chip and so the
                            // filter logic in MangaLibraryFilter (which uses the same 3/4 values)
                            // matches the user's intent.
                            FilterChipOption(3, stringResource(MR.strings.not_started)),
                            FilterChipOption(4, stringResource(MR.strings.in_progress)),
                        ),
                        selected = if (filterUnread in setOf(3, 4)) filterUnread else 0,
                        onSelect = { filterUnreadPref.set(it) },
                    )
                    FilterBottomSheet.Filters.Unread -> FilterChipRow(
                        label = stringResource(MR.strings.unread),
                        options = listOf(
                            FilterChipOption(0, stringResource(MR.strings.all)),
                            FilterChipOption(1, stringResource(MR.strings.unread)),
                            FilterChipOption(2, stringResource(MR.strings.read)),
                        ),
                        selected = if (filterUnread in setOf(1, 2)) filterUnread else 0,
                        onSelect = { filterUnreadPref.set(it) },
                    )
                    FilterBottomSheet.Filters.Downloaded -> FilterChipRow(
                        label = stringResource(MR.strings.downloaded),
                        options = listOf(
                            FilterChipOption(0, stringResource(MR.strings.all)),
                            FilterChipOption(1, stringResource(MR.strings.downloaded)),
                            FilterChipOption(2, stringResource(MR.strings.not_downloaded)),
                        ),
                        selected = filterDownloaded,
                        onSelect = { filterDownloadedPref.set(it) },
                    )
                    FilterBottomSheet.Filters.Completed -> FilterChipRow(
                        label = stringResource(MR.strings.status),
                        options = listOf(
                            FilterChipOption(0, stringResource(MR.strings.all)),
                            FilterChipOption(1, stringResource(MR.strings.completed)),
                            FilterChipOption(2, stringResource(MR.strings.ongoing)),
                        ),
                        selected = filterCompleted,
                        onSelect = { filterCompletedPref.set(it) },
                    )
                    // Manga-only: novels don't carry a series-type field, so the chip would
                    // have no effect. Hide the entire branch on the Novels tab.
                    FilterBottomSheet.Filters.SeriesType -> {
                        if (!isNovelTab && detectedMangaTypes.isNotEmpty()) {
                            FilterChipRow(
                                label = stringResource(MR.strings.series_type),
                                options = buildList {
                                    add(FilterChipOption(0, stringResource(MR.strings.all)))
                                    add(FilterChipOption(Manga.TYPE_MANGA, stringResource(MR.strings.manga)))
                                    if (Manga.TYPE_MANHWA in detectedMangaTypes) {
                                        add(FilterChipOption(Manga.TYPE_MANHWA, stringResource(MR.strings.manhwa)))
                                    }
                                    if (Manga.TYPE_MANHUA in detectedMangaTypes) {
                                        add(FilterChipOption(Manga.TYPE_MANHUA, stringResource(MR.strings.manhua)))
                                    }
                                    if (Manga.TYPE_COMIC in detectedMangaTypes) {
                                        add(FilterChipOption(Manga.TYPE_COMIC, stringResource(MR.strings.comic)))
                                    }
                                },
                                selected = filterMangaType,
                                onSelect = { preferences.filterMangaType().set(it) },
                            )
                        }
                    }
                    FilterBottomSheet.Filters.Bookmarked -> FilterChipRow(
                        label = stringResource(MR.strings.bookmarked),
                        options = listOf(
                            FilterChipOption(0, stringResource(MR.strings.all)),
                            FilterChipOption(1, stringResource(MR.strings.bookmarked)),
                            FilterChipOption(2, stringResource(MR.strings.not_bookmarked)),
                        ),
                        selected = filterBookmarked,
                        onSelect = { filterBookmarkedPref.set(it) },
                    )
                    FilterBottomSheet.Filters.Tracked -> {
                        if (loggedTrackerNames.isNotEmpty()) {
                            FilterChipRow(
                                label = stringResource(MR.strings.tracking),
                                options = listOf(
                                    FilterChipOption(0, stringResource(MR.strings.all)),
                                    FilterChipOption(1, stringResource(MR.strings.tracked)),
                                    FilterChipOption(2, stringResource(MR.strings.not_tracked)),
                                ),
                                selected = filterTracked,
                                onSelect = { filterTrackedPref.set(it) },
                            )
                            if (filterTracked == 1 && loggedTrackerNames.size > 1) {
                                FilterChipRow(
                                    label = stringResource(MR.strings.trackers),
                                    options = buildList {
                                        add(FilterChipOption(-1, stringResource(MR.strings.all)))
                                        loggedTrackerNames.forEachIndexed { i, name ->
                                            add(FilterChipOption(i, name))
                                        }
                                    },
                                    selected = loggedTrackerNames.indexOf(trackerName)
                                        .takeIf { it >= 0 } ?: -1,
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
                            FilterChipRow(
                                label = stringResource(MR.strings.content_type),
                                options = listOf(
                                    FilterChipOption(0, stringResource(MR.strings.all)),
                                    FilterChipOption(1, stringResource(MR.strings.sfw)),
                                    FilterChipOption(2, stringResource(MR.strings.nsfw)),
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
            OutlinedButton(
                onClick = clearAll,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(MR.strings.clear_filters))
            }
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(MR.strings.apply))
            }
        }
    }
}
