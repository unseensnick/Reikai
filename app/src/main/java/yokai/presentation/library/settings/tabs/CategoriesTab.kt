package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.category.CategoryController
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.category.interactor.GetCategories
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.i18n.MR
import yokai.presentation.category.novel.NovelCategoriesScreen
import yokai.presentation.component.preference.widget.ListPreferenceWidget
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget
import yokai.presentation.library.components.GroupLibraryByPicker
import yokai.presentation.library.components.rememberGroupByEntries

/**
 * Categories tab. Top section carries the library-shaping actions that used to live in the
 * legacy filter sheet's bottom toolbar: "Group library by" (dialog) and an Expand / Collapse
 * all categories toggle. Below that, the legacy display sub-view's toggles and lists in the
 * same order they appear in [eu.kanade.tachiyomi.ui.library.display.LibraryCategoryView].
 */
@Composable
fun CategoriesTab(
    onDismissSheet: () -> Unit = {},
    /** True when this tab is rendered inside the Novels-tab Display sheet. Combined with the
     *  shared/independent toggle, drives whether shareable prefs route through `novelPrefs.*`
     *  or `preferences.*`. Also hides manga-only toggles (hopper trio, showCategoryInTitle)
     *  regardless of the shared/independent mode. */
    isNovelTab: Boolean = false,
) {
    val preferences: PreferencesHelper = remember { Injekt.get() }
    val novelPrefs: NovelPreferences = remember { Injekt.get() }
    val getCategories: GetCategories = remember { Injekt.get() }
    val getNovelCategories: GetNovelCategories = remember { Injekt.get() }
    val router = LocalRouter.currentOrThrow
    // LocalNavigator is provided by the Voyager root that hosts LibraryScreen; pushing onto it
    // navigates to NovelCategoriesScreen for the novel-tab Edit/Add button. Manga side keeps
    // the existing Conductor router push to CategoryController.
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()

    // Category state (which IDs are collapsed, which dimension you're grouping by, etc.) is
    // per-library by nature — the manga and novel libraries have entirely different category
    // sets — so it routes by tab regardless of the shared display-prefs toggle (which is for
    // truly visual settings like grid size and badges, not library state).
    val routeToNovel = isNovelTab

    val groupLibraryByPref = rememberRoutedPref(routeToNovel, preferences.groupLibraryBy(), novelPrefs.groupLibraryBy())
    val groupLibraryBy by groupLibraryByPref.collectAsState()
    val collapsedCategoriesPref = rememberRoutedPref(routeToNovel, preferences.collapsedCategories(), novelPrefs.collapsedCategories())
    val collapsedCategories by collapsedCategoriesPref.collectAsState()
    // Drives the "Expand / Collapse all" button's enabled state. Read the right category set
    // for the active tab so the button reports the right "is this library empty?" answer.
    val mangaCategories by remember { getCategories.subscribe() }.collectAsState(initial = emptyList())
    val novelCategories by remember { getNovelCategories.subscribe() }.collectAsState(initial = emptyList())
    val allCategoriesEmpty = if (isNovelTab) novelCategories.isEmpty() else mangaCategories.isEmpty()
    val showAllCategoriesPref = rememberRoutedPref(routeToNovel, preferences.showAllCategories(), novelPrefs.showAllCategories())
    val showAllCategories by showAllCategoriesPref.collectAsState()
    val showCategoryInTitle by preferences.showCategoryInTitle().collectAsState()
    val collapsedDynamicAtBottomPref = rememberRoutedPref(routeToNovel, preferences.collapsedDynamicAtBottom(), novelPrefs.collapsedDynamicAtBottom())
    val collapsedDynamicAtBottom by collapsedDynamicAtBottomPref.collectAsState()
    val autoMergeSameTitlePref = rememberRoutedPref(routeToNovel, preferences.autoMergeSameTitle(), novelPrefs.autoMergeSameTitle())
    val autoMergeSameTitle by autoMergeSameTitlePref.collectAsState()
    val showEmptyCategoriesWhileFilteringPref = rememberRoutedPref(routeToNovel, preferences.showEmptyCategoriesWhileFiltering(), novelPrefs.showEmptyCategoriesWhileFiltering())
    val showEmptyCategoriesWhileFiltering by showEmptyCategoriesWhileFilteringPref.collectAsState()
    val hideHopper by preferences.hideHopper().collectAsState()
    val autohideHopper by preferences.autohideHopper().collectAsState()
    val hopperLongPressAction by preferences.hopperLongPressAction().collectAsState()
    val categorySortOrderPref = rememberRoutedPref(routeToNovel, preferences.categorySortOrder(), novelPrefs.categorySortOrder())
    val categorySortOrder by categorySortOrderPref.collectAsState()

    val groupByEntries = rememberGroupByEntries()

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
        5 to stringResource(MR.strings.open_random_series_global),
    )

    // Reikai-fork pref: how the category list itself is ordered. Default category is always
    // pinned at the top regardless. Mirrors the SettingsLibraryController intListPreference.
    val categorySortOrderEntries: Map<Int, String> = mapOf(
        0 to stringResource(MR.strings.category_sort_off),
        1 to stringResource(MR.strings.category_sort_a_to_z),
        2 to stringResource(MR.strings.category_sort_z_to_a),
    )

    // Order mirrors the legacy library_category_layout.xml row order so users moving between
    // the two paths see the same affordances in the same positions:
    //   1. always_show_current_category, show_all_categories, move_dynamic_to_bottom,
    //      show_categories_while_filtering, hide_category_hopper, category_hopper_long_press
    //   2. Divider
    //   3. Library-shape actions: group_library_by, expand/collapse-all (which the legacy
    //      surfaces in the Filter sheet's action bar; we keep them here so the Categories tab
    //      remains self-contained, but separate them visually since they reshape the library
    //      rather than tweaking display).
    //   4. Divider
    //   5. add_edit_categories button.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // "Always show current category" is a manga-only feature (it writes the category name
        // into the top toolbar title, which the novel toolbar doesn't surface). Hide on the
        // Novels tab in both modes.
        if (!isNovelTab) {
            SwitchPreferenceWidget(
                title = stringResource(MR.strings.always_show_current_category),
                checked = showCategoryInTitle,
                onCheckedChanged = { preferences.showCategoryInTitle().set(it) },
            )
        }
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_all_categories),
            checked = showAllCategories,
            onCheckedChanged = { showAllCategoriesPref.set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.move_dynamic_to_bottom),
            subtitle = stringResource(MR.strings.when_grouping_by_sources_tags),
            checked = collapsedDynamicAtBottom,
            onCheckedChanged = { collapsedDynamicAtBottomPref.set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.auto_merge_same_title),
            subtitle = stringResource(MR.strings.auto_merge_same_title_summary),
            checked = autoMergeSameTitle,
            onCheckedChanged = { autoMergeSameTitlePref.set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_categories_while_filtering),
            checked = showEmptyCategoriesWhileFiltering,
            onCheckedChanged = { showEmptyCategoriesWhileFilteringPref.set(it) },
        )
        // The category hopper is a manga-only library affordance (no novel-side equivalent yet),
        // so its visibility + long-press toggles aren't meaningful on the Novels tab.
        if (!isNovelTab) {
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        ListPreferenceWidget(
            value = categorySortOrder,
            title = stringResource(MR.strings.pref_category_sort_order),
            subtitle = categorySortOrderEntries[categorySortOrder],
            icon = null,
            entries = categorySortOrderEntries,
            onValueChange = { categorySortOrderPref.set(it) },
        )

        GroupLibraryByPicker(
            selected = groupLibraryBy,
            entries = groupByEntries,
            onSelect = { groupLibraryByPref.set(it) },
        )

        // Expand / collapse all only operates on BY_DEFAULT grouping. The pref is a
        // Set<String> of category IDs the legacy treats as collapsed
        // (see LibraryPresenter.toggleAllCategoryVisibility). Dynamic grouping uses
        // collapsedDynamicCategories keyed by group-name, which the Compose path doesn't
        // enumerate yet; we defer the dynamic branch until Phase 6 ports multi-source grouping.
        //
        // The two action buttons share a row: expand/collapse-all carries a leading chevron
        // matching the legacy filter sheet's expand_categories MaterialButton (app:icon =
        // ic_expand_more_24dp), and add/edit-categories sits beside it. No divider between
        // them since they are now a single action cluster.
        val allExpanded = collapsedCategories.isEmpty()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (groupLibraryBy != LibraryGroup.BY_DEFAULT) return@TextButton
                    scope.launch {
                        if (allExpanded) {
                            // Collect IDs from the active tab's category set; otherwise we'd
                            // write manga category IDs into the novel collapsed-set (or vice
                            // versa) and the library wouldn't recognise any of them.
                            val ids = if (isNovelTab) {
                                getNovelCategories.await().map { it.id.toString() }.toMutableSet()
                            } else {
                                getCategories.await().map { it.id.toString() }.toMutableSet()
                            }
                            collapsedCategoriesPref.set(ids)
                        } else {
                            collapsedCategoriesPref.set(mutableSetOf())
                        }
                    }
                },
                enabled = groupLibraryBy == LibraryGroup.BY_DEFAULT && !allCategoriesEmpty,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = if (allExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (allExpanded) MR.strings.collapse_all_categories
                        else MR.strings.expand_all_categories,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            TextButton(
                onClick = {
                    // Tab-aware navigation: Novels tab goes to the Compose NovelCategoriesScreen
                    // via Voyager; Manga tab keeps the legacy Conductor CategoryController push.
                    if (isNovelTab) {
                        navigator.push(NovelCategoriesScreen())
                    } else {
                        router.pushController(CategoryController().withFadeTransaction())
                    }
                    onDismissSheet()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(MR.strings.add_edit_categories),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun Boolean.toInt(): Int = if (this) 1 else 0
