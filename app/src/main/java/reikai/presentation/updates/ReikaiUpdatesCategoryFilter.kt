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
import reikai.domain.library.ContentType
import reikai.presentation.category.CategoryFilterRow
import reikai.presentation.category.CategoryFilterSection
import reikai.presentation.category.toLongIdSet
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsPrefState

/**
 * Include/exclude category filter for the Updates tab. Categories live in one shared table, but a
 * row is manga-, novel- or both-visible, so the picker shows a section per content type (universal
 * rows appear in both) and persists per-type selections; on the
 * Manga/Novels chips only that type's section appears, on All both do. The shared [CategoryFilterRow]
 * renders the row + dialog. Mounted inside the `// RK` island of Mihon's `UpdatesFilterDialog`.
 */
@Composable
fun ColumnScope.ReikaiUpdatesCategoryFilter(
    viewModel: UpdatesSettingsViewModel,
    contentType: ContentType,
) {
    val prefs = viewModel.reikaiSourcePreferences
    val mangaCategories by viewModel.mangaCategories.collectAsState()
    val novelCategories by viewModel.novelCategories.collectAsState()

    val showManga = contentType != ContentType.NOVELS && mangaCategories.isNotEmpty()
    val showNovel = contentType != ContentType.MANGA && novelCategories.isNotEmpty()

    val enabled by prefs.updatesFilterCategories.collectAsPrefState()
    val mangaInclude by prefs.updatesFilterMangaCategoriesInclude.collectAsPrefState()
    val mangaExclude by prefs.updatesFilterMangaCategoriesExclude.collectAsPrefState()
    val novelInclude by prefs.updatesFilterNovelCategoriesInclude.collectAsPrefState()
    val novelExclude by prefs.updatesFilterNovelCategoriesExclude.collectAsPrefState()

    // One section per visible content type; CategoryFilterRow shows headings only when both appear.
    val sections = buildList {
        if (showManga) {
            add(
                CategoryFilterSection(
                    headingRes = MR.strings.content_type_manga,
                    categories = mangaCategories,
                    included = mangaInclude.toLongIdSet(),
                    excluded = mangaExclude.toLongIdSet(),
                    onConfirm = viewModel::setMangaCategorySelections,
                ),
            )
        }
        if (showNovel) {
            add(
                CategoryFilterSection(
                    headingRes = MR.strings.content_type_novels,
                    categories = novelCategories,
                    included = novelInclude.toLongIdSet(),
                    excluded = novelExclude.toLongIdSet(),
                    onConfirm = viewModel::setNovelCategorySelections,
                ),
            )
        }
    }

    CategoryFilterRow(
        enabled = enabled,
        onToggleEnabled = viewModel::setFilterCategories,
        sections = sections,
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
