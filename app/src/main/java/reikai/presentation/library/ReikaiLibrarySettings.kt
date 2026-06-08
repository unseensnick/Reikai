package reikai.presentation.library

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibrarySettingsScreenModel
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Reikai-specific settings-sheet pieces grafted into Mihon's `LibrarySettingsDialog` (Stage 4):
 * a Group tab (Y3 dynamic grouping) and the include/exclude category filter (ported from Komikku's
 * `CategoriesFilter`, re-typed onto Mihon's `Category` + Reikai prefs). The category picker's Edit
 * also reaches Mihon's full category management screen via [onManageCategories].
 *
 * Follows Mihon's settings idiom (prefs read in the composable via `collectAsState`), consistent
 * with the rest of `LibrarySettingsDialog`.
 */

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
fun ColumnScope.ReikaiGroupPage(screenModel: LibrarySettingsScreenModel) {
    val groupLibraryBy by screenModel.reikaiLibraryPreferences.groupLibraryBy.collectAsState()
    groupModes.forEach { (mode, labelRes) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { screenModel.setGrouping(mode) }
                .padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = SettingsItemsPaddings.Vertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            RadioButton(selected = groupLibraryBy == mode, onClick = null)
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val categorySortOrders = listOf(
    MR.strings.category_sort_off to 0,
    MR.strings.category_sort_a_to_z to 1,
    MR.strings.category_sort_z_to_a to 2,
)

private val hopperLongPressActions = listOf(
    MR.strings.hopper_action_search to 0,
    MR.strings.hopper_action_expand_collapse to 1,
    MR.strings.hopper_action_display to 2,
    MR.strings.hopper_action_group to 3,
    MR.strings.hopper_action_random to 4,
    MR.strings.hopper_action_random_global to 5,
)

/**
 * The wired Reikai category/hopper settings, rendered under a "Categories" heading at the bottom
 * of the Display tab. The category-in-title, show-empty-while-filtering, hopper autohide-on-scroll,
 * and hopper long-press settings are intentionally absent until their backing behavior is wired
 * (they would otherwise be inert toggles).
 */
@Composable
fun ColumnScope.ReikaiCategoriesPage(screenModel: LibrarySettingsScreenModel) {
    HeadingItem(MR.strings.categories)
    val categorySortOrder by screenModel.reikaiLibraryPreferences.categorySortOrder.collectAsState()
    SettingsChipRow(MR.strings.pref_category_sort_order) {
        categorySortOrders.forEach { (labelRes, value) ->
            FilterChip(
                selected = categorySortOrder == value,
                onClick = { screenModel.setCategorySortOrder(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
    CheckboxItem(
        label = stringResource(MR.strings.always_show_current_category),
        pref = screenModel.reikaiLibraryPreferences.showCategoryInTitle,
    )
    CheckboxItem(
        label = stringResource(MR.strings.move_dynamic_to_bottom),
        pref = screenModel.reikaiLibraryPreferences.collapsedDynamicAtBottom,
    )
    CheckboxItem(
        label = stringResource(MR.strings.show_categories_while_filtering),
        pref = screenModel.reikaiLibraryPreferences.showEmptyCategoriesWhileFiltering,
    )
    CheckboxItem(
        label = stringResource(MR.strings.show_hidden_categories),
        pref = screenModel.reikaiLibraryPreferences.showHiddenCategories,
    )
    CheckboxItem(
        label = stringResource(MR.strings.hide_category_hopper),
        pref = screenModel.reikaiLibraryPreferences.hideHopper,
    )
    CheckboxItem(
        label = stringResource(MR.strings.autohide_category_hopper),
        pref = screenModel.reikaiLibraryPreferences.autohideHopper,
    )
    val hopperLongPress by screenModel.reikaiLibraryPreferences.hopperLongPressAction.collectAsState()
    SettingsChipRow(MR.strings.hopper_long_press) {
        hopperLongPressActions.forEach { (labelRes, value) ->
            FilterChip(
                selected = hopperLongPress == value,
                onClick = { screenModel.setHopperLongPressAction(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
fun ColumnScope.ReikaiCategoriesFilter(
    screenModel: LibrarySettingsScreenModel,
    categories: List<Category>,
    onManageCategories: () -> Unit,
) {
    val filterCategories by screenModel.reikaiLibraryPreferences.filterCategories.collectAsState()
    val included by screenModel.reikaiLibraryPreferences.filterCategoriesInclude.collectAsState()
    val excluded by screenModel.reikaiLibraryPreferences.filterCategoriesExclude.collectAsState()
    var showDialog by rememberSaveable { mutableStateOf(false) }

    if (showDialog) {
        ReikaiCategoryFilterDialog(
            categories = categories,
            included = included.mapNotNull { it.toLongOrNull() }.toSet(),
            excluded = excluded.mapNotNull { it.toLongOrNull() }.toSet(),
            onConfirm = { include, exclude ->
                screenModel.setCategoryFilterSelections(include, exclude)
                showDialog = false
            },
            onManageCategories = {
                showDialog = false
                onManageCategories()
            },
            onDismiss = { showDialog = false },
        )
    }

    Row(
        modifier = Modifier
            .clickable { screenModel.setFilterCategories(!filterCategories) }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Checkbox(checked = filterCategories, onCheckedChange = null)
        Text(text = stringResource(MR.strings.categories), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = { showDialog = true }) {
            Text(stringResource(MR.strings.action_edit))
        }
    }
}

@Composable
private fun ReikaiCategoryFilterDialog(
    categories: List<Category>,
    included: Set<Long>,
    excluded: Set<Long>,
    onConfirm: (include: Set<Long>, exclude: Set<Long>) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultLabel = stringResource(MR.strings.label_default)
    // Per-category tri-state: checked = include, inverted = exclude, blank = ignore.
    val states = remember(categories, included, excluded) {
        mutableStateMapOf<Long, TriState>().apply {
            categories.forEach { category ->
                this[category.id] = when (category.id) {
                    in included -> TriState.ENABLED_IS
                    in excluded -> TriState.ENABLED_NOT
                    else -> TriState.DISABLED
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.categories)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                categories.forEach { category ->
                    TriStateItem(
                        label = category.name.ifBlank { defaultLabel },
                        state = states[category.id] ?: TriState.DISABLED,
                        onClick = { next -> states[category.id] = next },
                    )
                }
            }
        },
        // "Edit categories" sits on the left of the action row, opposite Cancel / OK.
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onManageCategories) {
                    Text(stringResource(MR.strings.action_edit_categories))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.action_cancel))
                }
                TextButton(
                    onClick = {
                        val include = states.filterValues { it == TriState.ENABLED_IS }.keys.toSet()
                        val exclude = states.filterValues { it == TriState.ENABLED_NOT }.keys.toSet()
                        onConfirm(include, exclude)
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            }
        },
    )
}
