package reikai.presentation.recents

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
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
 * The filter sheet every recents surface opens. Every control is drawn from every mode, tabbed by what
 * it acts on, so a setting is never somewhere you must switch section to reach. Each tab states the
 * sections it reaches, which is the scope the old sheet stated by hiding things. The selection it
 * edits belongs to [surface], so two separate tabs cannot move each other's filters.
 */
@Composable
fun RecentsFilterSheet(
    surface: RecentsSurface,
    onDismissRequest: () -> Unit,
    initialTab: Int = 0,
) {
    val viewModel = assistedMetroViewModel<UpdatesSettingsViewModel, UpdatesSettingsViewModel.Factory> {
        create(surface = surface)
    }
    val tabTitles = listOf(
        stringResource(MR.strings.recents_filter_general),
        stringResource(MR.strings.chapters),
        stringResource(MR.strings.label_recent_updates),
    )
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, tabTitles.lastIndex)) { tabTitles.size },
        tabTitles = tabTitles,
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> GeneralPage(viewModel)
                1 -> ChaptersPage(viewModel)
                2 -> UpdatesPage(viewModel)
            }
        }
    }
}

/** What reaches every mode: which categories a feed draws from, and whether it keeps finished series. */
@Composable
private fun ColumnScope.GeneralPage(viewModel: UpdatesSettingsViewModel) {
    CategoryFilter(viewModel)
    ShowReadSwitch(viewModel)
}

/** The chapter-state filters, which narrow the updated lane and both combined modes. */
@Composable
private fun ColumnScope.ChaptersPage(viewModel: UpdatesSettingsViewModel) {
    ScopeCaption(stringResource(MR.strings.recents_filter_scope_chapters))
    ChapterStateFilters(viewModel)
    ExcludedScanlatorsSwitch(viewModel)
}

/** What only the updated lane has: how its several-chapters-in-a-day rows are drawn. */
@Composable
private fun ColumnScope.UpdatesPage(viewModel: UpdatesSettingsViewModel) {
    ScopeCaption(stringResource(MR.strings.recents_filter_scope_updates))
    GroupBySeriesSwitch(viewModel)
}

/**
 * Which sections a tab's settings reach. The sheet draws every control from every mode now, so the
 * scope the old sheet stated by hiding a control has to be said out loud instead; without this, the
 * chapter filters read as doing nothing when you set them from History, which does not obey them.
 */
@Composable
private fun ColumnScope.ScopeCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = SettingsItemsPaddings.Horizontal,
            vertical = SettingsItemsPaddings.Vertical,
        ),
    )
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

/**
 * Obeyed by the combined modes only: Updates and History are a record of what happened, so hiding a
 * caught-up series there would be hiding the event you came to look at. Drawn from every mode all the
 * same, since it is a property of the feed rather than of where you happen to be standing.
 */
@Composable
private fun ColumnScope.ShowReadSwitch(viewModel: UpdatesSettingsViewModel) {
    val showRead by viewModel.showRead.collectAsPrefState()

    SwitchRow(
        label = stringResource(MR.strings.recents_filter_show_read),
        checked = showRead,
        onToggle = { viewModel.showRead.getAndSet { !it } },
    )
}

@Composable
private fun ColumnScope.ExcludedScanlatorsSwitch(viewModel: UpdatesSettingsViewModel) {
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
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled) { onToggle() }
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
        Switch(checked = checked, enabled = enabled, onCheckedChange = { onToggle() })
    }
}
