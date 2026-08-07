package reikai.presentation.library

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import eu.kanade.tachiyomi.ui.library.LibrarySettingsViewModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Reikai's category and hopper settings, rendered under the Display tab by [EntryDisplayPage]. They are
 * library-wide rather than per-content-type, which is why they hang off the shared display model rather
 * than off a [LibrarySettingsBinding].
 *
 * Follows Mihon's settings idiom (preferences read in the composable via `collectAsState`), consistent
 * with the rest of the settings sheet.
 */

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
 * of the Display tab.
 */
@Composable
fun ColumnScope.ReikaiCategoriesPage(viewModel: LibrarySettingsViewModel) {
    HeadingItem(MR.strings.categories)
    val categorySortOrder by viewModel.reikaiLibraryPreferences.categorySortOrder.collectAsState()
    SettingsChipRow(MR.strings.pref_category_sort_order) {
        categorySortOrders.forEach { (labelRes, value) ->
            FilterChip(
                selected = categorySortOrder == value,
                onClick = { viewModel.setCategorySortOrder(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
    CheckboxItem(
        label = stringResource(MR.strings.always_show_current_category),
        pref = viewModel.reikaiLibraryPreferences.showCategoryInTitle,
    )
    CheckboxItem(
        label = stringResource(MR.strings.move_dynamic_to_bottom),
        pref = viewModel.reikaiLibraryPreferences.collapsedDynamicAtBottom,
    )
    CheckboxItem(
        label = stringResource(MR.strings.show_hidden_categories),
        pref = viewModel.reikaiLibraryPreferences.showHiddenCategories,
    )
    CheckboxItem(
        label = stringResource(MR.strings.hide_category_hopper),
        pref = viewModel.reikaiLibraryPreferences.hideHopper,
    )
    CheckboxItem(
        label = stringResource(MR.strings.autohide_category_hopper),
        pref = viewModel.reikaiLibraryPreferences.autohideHopper,
    )
    val hopperLongPress by viewModel.reikaiLibraryPreferences.hopperLongPressAction.collectAsState()
    SettingsChipRow(MR.strings.hopper_long_press) {
        hopperLongPressActions.forEach { (labelRes, value) ->
            FilterChip(
                selected = hopperLongPress == value,
                onClick = { viewModel.setHopperLongPressAction(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}
