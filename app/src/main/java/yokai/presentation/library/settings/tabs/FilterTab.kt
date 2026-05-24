package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.presentation.library.components.FilterChipOption
import yokai.presentation.library.components.FilterChipRow

/**
 * Filter tab content for the Display options sheet. Renders one FilterChipRow per filter
 * preference in `preferences.filterOrder()` order. Each row reads / writes the same
 * `PreferencesHelper` int key the legacy `FilterBottomSheet` uses, so the two paths stay in
 * sync on the same library data.
 *
 * Commit 3 ships always-visible rows only: Read progress, Unread, Downloaded, Status (completed),
 * Bookmarked, Content type. The conditional Series-type and Tracker rows land in commit 5.
 */
@Composable
fun FilterTab() {
    val preferences: PreferencesHelper = remember { Injekt.get() }

    val filterUnread by preferences.filterUnread().collectAsState()
    val filterDownloaded by preferences.filterDownloaded().collectAsState()
    val filterCompleted by preferences.filterCompleted().collectAsState()
    val filterBookmarked by preferences.filterBookmarked().collectAsState()
    val filterContentType by preferences.filterContentType().collectAsState()
    val filterOrder by preferences.filterOrder().collectAsState()

    val all = stringResource(MR.strings.all)
    val notStarted = stringResource(MR.strings.not_started)
    val inProgress = stringResource(MR.strings.in_progress)
    val unread = stringResource(MR.strings.unread)
    val read = stringResource(MR.strings.read)
    val downloaded = stringResource(MR.strings.downloaded)
    val notDownloaded = stringResource(MR.strings.not_downloaded)
    val completed = stringResource(MR.strings.completed)
    val ongoing = stringResource(MR.strings.ongoing)
    val bookmarked = stringResource(MR.strings.bookmarked)
    val notBookmarked = stringResource(MR.strings.not_bookmarked)
    val sfw = stringResource(MR.strings.sfw)
    val nsfw = stringResource(MR.strings.nsfw)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        // Iterate the saved filter order (default "urdcmbts"); skip 'm' and 't' since those
        // are commit-5 conditional rows. Unknown chars fall through quietly so a stale or
        // future-extended order does not crash the UI.
        for (c in filterOrder.toCharArray().distinct()) {
            when (FilterBottomSheet.Filters.filterOf(c)) {
                FilterBottomSheet.Filters.UnreadProgress -> FilterChipRow(
                    label = stringResource(MR.strings.read_progress),
                    options = listOf(
                        FilterChipOption(0, all),
                        FilterChipOption(4, notStarted),
                        FilterChipOption(3, inProgress),
                    ),
                    selected = if (filterUnread in setOf(3, 4)) filterUnread else 0,
                    onSelect = { preferences.filterUnread().set(it) },
                )
                FilterBottomSheet.Filters.Unread -> FilterChipRow(
                    label = stringResource(MR.strings.unread),
                    options = listOf(
                        FilterChipOption(0, all),
                        FilterChipOption(1, unread),
                        FilterChipOption(2, read),
                    ),
                    selected = if (filterUnread in setOf(1, 2)) filterUnread else 0,
                    onSelect = { preferences.filterUnread().set(it) },
                )
                FilterBottomSheet.Filters.Downloaded -> FilterChipRow(
                    label = stringResource(MR.strings.downloaded),
                    options = listOf(
                        FilterChipOption(0, all),
                        FilterChipOption(1, downloaded),
                        FilterChipOption(2, notDownloaded),
                    ),
                    selected = filterDownloaded,
                    onSelect = { preferences.filterDownloaded().set(it) },
                )
                FilterBottomSheet.Filters.Completed -> FilterChipRow(
                    label = stringResource(MR.strings.status),
                    options = listOf(
                        FilterChipOption(0, all),
                        FilterChipOption(1, completed),
                        FilterChipOption(2, ongoing),
                    ),
                    selected = filterCompleted,
                    onSelect = { preferences.filterCompleted().set(it) },
                )
                FilterBottomSheet.Filters.Bookmarked -> FilterChipRow(
                    label = stringResource(MR.strings.bookmarked),
                    options = listOf(
                        FilterChipOption(0, all),
                        FilterChipOption(1, bookmarked),
                        FilterChipOption(2, notBookmarked),
                    ),
                    selected = filterBookmarked,
                    onSelect = { preferences.filterBookmarked().set(it) },
                )
                FilterBottomSheet.Filters.ContentType -> FilterChipRow(
                    label = stringResource(MR.strings.content_type),
                    options = listOf(
                        FilterChipOption(0, all),
                        FilterChipOption(1, sfw),
                        FilterChipOption(2, nsfw),
                    ),
                    selected = filterContentType,
                    onSelect = { preferences.filterContentType().set(it) },
                )
                // SeriesType / Tracked / null: commit 5 handles the conditional rows.
                else -> Unit
            }
        }

        if (hasAnyActive(filterUnread, filterDownloaded, filterCompleted, filterBookmarked, filterContentType)) {
            TextButton(
                onClick = {
                    preferences.filterUnread().set(0)
                    preferences.filterDownloaded().set(0)
                    preferences.filterCompleted().set(0)
                    preferences.filterBookmarked().set(0)
                    preferences.filterContentType().set(0)
                    preferences.filterMangaType().set(0)
                    preferences.filterTracked().set(0)
                    FilterBottomSheet.FILTER_TRACKER = ""
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.clear_filters),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun hasAnyActive(vararg states: Int): Boolean = states.any { it != 0 }
