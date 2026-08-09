package reikai.presentation.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsViewModel
import reikai.presentation.category.CategoryFilterRow
import reikai.presentation.category.CategoryFilterSection
import reikai.presentation.category.toLongIdSet
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsPrefState

/**
 * Include/exclude category filter for the Updates tab. One selection over the whole category table
 * rather than a section per content type: the ids are one space, so a manga-only category simply
 * matches no novel. The dialog carries its own All / Manga / Novels chip, since scanning one library's
 * categories out of a long list is the slow part of picking; it narrows only what is drawn, and the
 * confirm still merges over every stored id, so nothing hidden is lost. The shared [CategoryFilterRow]
 * renders the row + dialog. Mounted inside the `// RK` island of Mihon's `UpdatesFilterDialog`.
 */
@Composable
fun ColumnScope.ReikaiUpdatesCategoryFilter(viewModel: UpdatesSettingsViewModel) {
    val prefs = viewModel.reikaiSourcePreferences
    val categories by viewModel.categories.collectAsState()

    val enabled by prefs.updatesFilterCategories.collectAsPrefState()
    val include by prefs.updatesFilterCategoriesInclude.collectAsPrefState()
    val exclude by prefs.updatesFilterCategoriesExclude.collectAsPrefState()

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

/** "Group by series" switch for the Updates filter dialog (a Reikai addition, like the category row). */
@Composable
fun ColumnScope.ReikaiUpdatesGroupToggle(viewModel: UpdatesSettingsViewModel) {
    val grouped by viewModel.reikaiSourcePreferences.updatesGroupBySeries.collectAsPrefState()
    fun toggle() {
        viewModel.reikaiSourcePreferences.updatesGroupBySeries.getAndSet { !it }
    }
    Row(
        modifier = Modifier
            .clickable { toggle() }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(MR.strings.updates_group_by_series),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = grouped, onCheckedChange = { toggle() })
    }
}
