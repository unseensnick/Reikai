package eu.kanade.presentation.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsViewModel
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun UpdatesFilterDialog(
    onDismissRequest: () -> Unit,
    viewModel: UpdatesSettingsViewModel,
    // RK -->
    reikaiCategoryRow: @Composable ColumnScope.() -> Unit = {},
    reikaiAfterFilters: @Composable ColumnScope.() -> Unit = {},
    // RK <--
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(
            stringResource(MR.strings.action_filter),
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            // RK: thread the category row (above the divider) + the post-filter section into the sheet
            FilterSheet(
                viewModel = viewModel,
                reikaiCategoryRow = reikaiCategoryRow,
                reikaiAfterFilters = reikaiAfterFilters,
            )
        }
    }
}

@Composable
private fun ColumnScope.FilterSheet(
    viewModel: UpdatesSettingsViewModel,
    // RK -->
    reikaiCategoryRow: @Composable ColumnScope.() -> Unit = {},
    reikaiAfterFilters: @Composable ColumnScope.() -> Unit = {},
    // RK <--
) {
    val filterDownloaded by viewModel.updatesPreferences.filterDownloaded.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = filterDownloaded,
        onClick = { viewModel.toggleFilter(UpdatesPreferences::filterDownloaded) },
    )

    val filterUnread by viewModel.updatesPreferences.filterUnread.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unread),
        state = filterUnread,
        onClick = { viewModel.toggleFilter(UpdatesPreferences::filterUnread) },
    )

    val filterStarted by viewModel.updatesPreferences.filterStarted.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = filterStarted,
        onClick = { viewModel.toggleFilter(UpdatesPreferences::filterStarted) },
    )

    val filterBookmarked by viewModel.updatesPreferences.filterBookmarked.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = filterBookmarked,
        onClick = { viewModel.toggleFilter(UpdatesPreferences::filterBookmarked) },
    )

    // RK: include/exclude category filter sits with the other content filters, above the divider
    reikaiCategoryRow()

    HorizontalDivider(modifier = Modifier.padding(MaterialTheme.padding.small))

    val filterExcludedScanlators by viewModel.updatesPreferences.filterExcludedScanlators.collectAsState()

    fun toggleScanlatorFilter() = viewModel.updatesPreferences.filterExcludedScanlators.getAndSet { !it }

    Row(
        modifier = Modifier
            .clickable { toggleScanlatorFilter() }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(MR.strings.action_filter_excluded_scanlators),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )

        Switch(
            checked = filterExcludedScanlators,
            onCheckedChange = { toggleScanlatorFilter() },
        )
    }

    // RK: display/grouping options (e.g. group by series) after the filters
    reikaiAfterFilters()
}
