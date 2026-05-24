package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.presentation.component.preference.widget.ListPreferenceWidget
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget

/**
 * Categories tab: visibility toggles for the active-category chip, all-categories grouping,
 * dynamic-categories-at-bottom, the empty-while-filtering option, plus two list-dialogs for the
 * hopper (visibility + long-press action). Same preferences the legacy
 * [eu.kanade.tachiyomi.ui.library.display.LibraryCategoryView] writes. The "Add categories"
 * button is intentionally omitted; that screen is a separate Conductor controller and a port
 * is out of Phase 3 scope.
 */
@Composable
fun CategoriesTab() {
    val preferences: PreferencesHelper = remember { Injekt.get() }

    val showAllCategories by preferences.showAllCategories().collectAsState()
    val showCategoryInTitle by preferences.showCategoryInTitle().collectAsState()
    val collapsedDynamicAtBottom by preferences.collapsedDynamicAtBottom().collectAsState()
    val showEmptyCategoriesWhileFiltering by preferences.showEmptyCategoriesWhileFiltering().collectAsState()
    val hideHopper by preferences.hideHopper().collectAsState()
    val autohideHopper by preferences.autohideHopper().collectAsState()
    val hopperLongPressAction by preferences.hopperLongPressAction().collectAsState()

    // Mirrors the legacy hideHopperSpinner: index 0 = always shown, 1 = autohide on scroll,
    // 2 = always hidden. Encoded as (hideHopper * 2) + autohideHopper, clamped to [0, 2].
    val hopperVisibility = (hideHopper.toInt() * 2 + autohideHopper.toInt()).coerceAtMost(2)
    val hopperVisibilityEntries: Map<Int, String> = mapOf(
        0 to stringResource(MR.strings.never),
        1 to stringResource(MR.strings.hides_on_scroll),
        2 to stringResource(MR.strings.always),
    )

    val hopperLongPressEntries: Map<Int, String> = mapOf(
        0 to stringResource(MR.strings.search),
        1 to stringResource(MR.strings.expand_collapse_all_categories),
        2 to stringResource(MR.strings.display_options),
        3 to stringResource(MR.strings.group_library_by),
        4 to stringResource(MR.strings.open_random_series),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.always_show_current_category),
            checked = showCategoryInTitle,
            onCheckedChanged = { preferences.showCategoryInTitle().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_all_categories),
            checked = showAllCategories,
            onCheckedChanged = { preferences.showAllCategories().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.move_dynamic_to_bottom),
            subtitle = stringResource(MR.strings.when_grouping_by_sources_tags),
            checked = collapsedDynamicAtBottom,
            onCheckedChanged = { preferences.collapsedDynamicAtBottom().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_categories_while_filtering),
            checked = showEmptyCategoriesWhileFiltering,
            onCheckedChanged = { preferences.showEmptyCategoriesWhileFiltering().set(it) },
        )
        ListPreferenceWidget(
            value = hopperVisibility,
            title = stringResource(MR.strings.hide_category_hopper),
            subtitle = hopperVisibilityEntries[hopperVisibility],
            icon = null,
            entries = hopperVisibilityEntries,
            onValueChange = { selection ->
                preferences.hideHopper().set(selection == 2)
                preferences.autohideHopper().set(selection == 1)
            },
        )
        ListPreferenceWidget(
            value = hopperLongPressAction,
            title = stringResource(MR.strings.category_hopper_long_press),
            subtitle = hopperLongPressEntries[hopperLongPressAction],
            icon = null,
            entries = hopperLongPressEntries,
            onValueChange = { preferences.hopperLongPressAction().set(it) },
        )
    }
}

private fun Boolean.toInt(): Int = if (this) 1 else 0
