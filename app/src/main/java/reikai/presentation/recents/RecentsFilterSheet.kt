package reikai.presentation.recents

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsViewModel
import reikai.domain.category.RecentsSurface
import reikai.presentation.category.CategoryFilterRow
import reikai.presentation.category.CategoryFilterSection
import reikai.presentation.category.toLongIdSet
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsPrefState

/**
 * The filter sheet every recents surface opens, drawing only what its [mode] can answer for: the
 * chapter-state filters reach the updated lane alone, so a history feed would show five controls that
 * change nothing, while the category filter reaches every lane and is always here. The selection it
 * edits belongs to [surface], because two separate tabs must not move each other's filters. Replaces
 * Mihon's UpdatesFilterDialog, whose filters and scanlator switch are carried across unchanged.
 */
@Composable
fun RecentsFilterSheet(
    mode: RecentsMode,
    surface: RecentsSurface,
    onDismissRequest: () -> Unit,
) {
    val viewModel = viewModel<UpdatesSettingsViewModel>(
        factory = UpdatesSettingsViewModel.Factory,
        extras = CreationExtras { set(UpdatesSettingsViewModel.SURFACE_KEY, surface) },
    )
    val filtersChapters = mode.can(RecentsCapability.CHAPTER_FILTER)
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(stringResource(MR.strings.action_filter)),
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            if (filtersChapters) {
                ChapterStateFilters(viewModel)
            }
            CategoryFilter(viewModel)
            if (filtersChapters) {
                ExcludedScanlatorsSwitch(viewModel)
            }
            if (mode.can(RecentsCapability.GROUPING)) {
                GroupBySeriesSwitch(viewModel)
            }
        }
    }
}

@Composable
private fun ColumnScope.ChapterStateFilters(viewModel: UpdatesSettingsViewModel) {
    val filterDownloaded by viewModel.updatesPreferences.filterDownloaded.collectAsPrefState()
    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = filterDownloaded,
        onClick = { viewModel.toggleFilter(UpdatesPreferences::filterDownloaded) },
    )

    val filterUnread by viewModel.updatesPreferences.filterUnread.collectAsPrefState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unread),
        state = filterUnread,
        onClick = { viewModel.toggleFilter(UpdatesPreferences::filterUnread) },
    )

    val filterStarted by viewModel.updatesPreferences.filterStarted.collectAsPrefState()
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = filterStarted,
        onClick = { viewModel.toggleFilter(UpdatesPreferences::filterStarted) },
    )

    val filterBookmarked by viewModel.updatesPreferences.filterBookmarked.collectAsPrefState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = filterBookmarked,
        onClick = { viewModel.toggleFilter(UpdatesPreferences::filterBookmarked) },
    )
}

/**
 * Include/exclude over the whole category table rather than a section per content type: the ids are one
 * space, so a manga-only category simply matches no novel. The dialog carries its own All / Manga /
 * Novels chip, since scanning one library's categories out of a long list is the slow part of picking;
 * it narrows only what is drawn, and the confirm still merges over every stored id.
 */
@Composable
private fun ColumnScope.CategoryFilter(viewModel: UpdatesSettingsViewModel) {
    val categories by viewModel.categories.collectAsState()

    val enabled by viewModel.filterCategories.collectAsPrefState()
    val include by viewModel.filterCategoriesInclude.collectAsPrefState()
    val exclude by viewModel.filterCategoriesExclude.collectAsPrefState()

    if (categories.isEmpty()) return

    CategoryFilterRow(
        enabled = enabled,
        onToggleEnabled = viewModel::setFilterCategories,
        sections = listOf(
            CategoryFilterSection(
                headingRes = null,
                categories = categories,
                included = include.toLongIdSet(),
                excluded = exclude.toLongIdSet(),
                onConfirm = viewModel::setCategorySelections,
            ),
        ),
        showContentTypeChip = true,
    )
}

@Composable
private fun ColumnScope.ExcludedScanlatorsSwitch(viewModel: UpdatesSettingsViewModel) {
    HorizontalDivider(modifier = Modifier.padding(MaterialTheme.padding.small))

    val filterExcludedScanlators by viewModel.updatesPreferences.filterExcludedScanlators.collectAsPrefState()

    fun toggle() = viewModel.updatesPreferences.filterExcludedScanlators.getAndSet { !it }

    SwitchRow(
        label = stringResource(MR.strings.action_filter_excluded_scanlators),
        checked = filterExcludedScanlators,
        onToggle = ::toggle,
    )
}

@Composable
private fun ColumnScope.GroupBySeriesSwitch(viewModel: UpdatesSettingsViewModel) {
    val grouped by viewModel.reikaiSourcePreferences.updatesGroupBySeries.collectAsPrefState()

    fun toggle() = viewModel.reikaiSourcePreferences.updatesGroupBySeries.getAndSet { !it }

    SwitchRow(
        label = stringResource(MR.strings.updates_group_by_series),
        checked = grouped,
        onToggle = ::toggle,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable { onToggle() }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
