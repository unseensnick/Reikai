package reikai.presentation.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsScreenModel
import reikai.domain.library.ContentType
import reikai.presentation.category.CategoryTriStateRows
import reikai.presentation.category.idsWith
import reikai.presentation.category.rememberCategoryStates
import reikai.presentation.category.toLongIdSet
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsPrefState

/**
 * Include/exclude category filter for the Updates tab, modeled on the library's
 * [reikai.presentation.library.ReikaiCategoriesFilter]. Manga and novel categories are separate id
 * spaces, so the picker shows a section per content type and persists per-type selections; on the
 * Manga/Novels chips only that type's section appears, on All both do. Rendered inside the `// RK`
 * island of Mihon's `UpdatesFilterDialog`.
 */
@Composable
fun ColumnScope.ReikaiUpdatesCategoryFilter(
    screenModel: UpdatesSettingsScreenModel,
    contentType: ContentType,
) {
    val prefs = screenModel.reikaiSourcePreferences
    val mangaCategories by screenModel.mangaCategories.collectAsState()
    val novelCategories by screenModel.novelCategories.collectAsState()

    val showManga = contentType != ContentType.NOVELS && mangaCategories.isNotEmpty()
    val showNovel = contentType != ContentType.MANGA && novelCategories.isNotEmpty()
    // No categories of the visible type means nothing to pick: hide the control entirely.
    if (!showManga && !showNovel) return

    val enabled by prefs.updatesFilterCategories.collectAsPrefState()
    val mangaInclude by prefs.updatesFilterMangaCategoriesInclude.collectAsPrefState()
    val mangaExclude by prefs.updatesFilterMangaCategoriesExclude.collectAsPrefState()
    val novelInclude by prefs.updatesFilterNovelCategoriesInclude.collectAsPrefState()
    val novelExclude by prefs.updatesFilterNovelCategoriesExclude.collectAsPrefState()

    var showDialog by rememberSaveable { mutableStateOf(false) }

    if (showDialog) {
        UpdatesCategoryFilterDialog(
            mangaCategories = if (showManga) mangaCategories else emptyList(),
            novelCategories = if (showNovel) novelCategories else emptyList(),
            mangaIncluded = mangaInclude.toLongIdSet(),
            mangaExcluded = mangaExclude.toLongIdSet(),
            novelIncluded = novelInclude.toLongIdSet(),
            novelExcluded = novelExclude.toLongIdSet(),
            onConfirm = { mInc, mExc, nInc, nExc ->
                if (showManga) screenModel.setMangaCategorySelections(mInc, mExc)
                if (showNovel) screenModel.setNovelCategorySelections(nInc, nExc)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }

    Row(
        modifier = Modifier
            .clickable { screenModel.setFilterCategories(!enabled) }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Checkbox(checked = enabled, onCheckedChange = null)
        Text(text = stringResource(MR.strings.categories), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = { showDialog = true }) {
            Text(stringResource(MR.strings.action_edit))
        }
    }
}

/** "Group by series" switch for the Updates filter dialog (a // RK addition, like the category row). */
@Composable
fun ColumnScope.ReikaiUpdatesGroupToggle(screenModel: UpdatesSettingsScreenModel) {
    val grouped by screenModel.reikaiSourcePreferences.updatesGroupBySeries.collectAsPrefState()
    fun toggle() {
        screenModel.reikaiSourcePreferences.updatesGroupBySeries.getAndSet { !it }
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

@Composable
private fun UpdatesCategoryFilterDialog(
    mangaCategories: List<Category>,
    novelCategories: List<Category>,
    mangaIncluded: Set<Long>,
    mangaExcluded: Set<Long>,
    novelIncluded: Set<Long>,
    novelExcluded: Set<Long>,
    onConfirm: (
        mangaInclude: Set<Long>,
        mangaExclude: Set<Long>,
        novelInclude: Set<Long>,
        novelExclude: Set<Long>,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultLabel = stringResource(MR.strings.label_default)
    val mangaStates = rememberCategoryStates(mangaCategories, mangaIncluded, mangaExcluded)
    val novelStates = rememberCategoryStates(novelCategories, novelIncluded, novelExcluded)
    // Only label the sections when both are present; a single section needs no heading.
    val bothSections = mangaCategories.isNotEmpty() && novelCategories.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.categories)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (mangaCategories.isNotEmpty()) {
                    if (bothSections) HeadingItem(MR.strings.content_type_manga)
                    CategoryTriStateRows(mangaCategories, mangaStates, defaultLabel)
                }
                if (novelCategories.isNotEmpty()) {
                    if (bothSections) HeadingItem(MR.strings.content_type_novels)
                    CategoryTriStateRows(novelCategories, novelStates, defaultLabel)
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.action_cancel))
                }
                TextButton(
                    onClick = {
                        onConfirm(
                            mangaStates.idsWith(TriState.ENABLED_IS),
                            mangaStates.idsWith(TriState.ENABLED_NOT),
                            novelStates.idsWith(TriState.ENABLED_IS),
                            novelStates.idsWith(TriState.ENABLED_NOT),
                        )
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            }
        },
    )
}
