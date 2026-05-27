package yokai.presentation.library.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsLibraryController
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelPreferences
import yokai.i18n.MR
import yokai.presentation.library.settings.tabs.BadgesTab
import yokai.presentation.library.settings.tabs.CategoriesTab
import yokai.presentation.library.settings.tabs.DisplayTab
import yokai.presentation.library.settings.tabs.FilterTab
import yokai.presentation.library.settings.tabs.rememberRoutedPref

/**
 * Compose-side replacement for the legacy FilterBottomSheet and TabbedLibraryDisplaySheet.
 * One sheet, four tabs: Filter / Display / Badges / Categories. The legacy bottom sheets stay
 * alive untouched and remain wired into [eu.kanade.tachiyomi.ui.library.LibraryController]; the
 * Compose path uses this sheet exclusively.
 *
 * Sheet shape: no drag handle (the title + tab row carry the affordance), title and Reorder
 * action share one row, tab row blends into the sheet surface so the tabs feel like part of the
 * header rather than a separate band.
 */

const val TAB_FILTER = 0
const val TAB_DISPLAY = 1
const val TAB_BADGES = 2
const val TAB_CATEGORIES = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDisplayOptionsSheet(
    initialTab: Int,
    onDismiss: () -> Unit,
    detectedMangaTypes: Set<Int> = emptySet(),
    loggedTrackerNames: List<String> = emptyList(),
    /**
     * Which library tab the sheet was opened from. Tab composables route shareable visual prefs
     * via this flag combined with [yokai.domain.base.BasePreferences.useSharedLibraryDisplayPrefs];
     * tab-aware management actions (Edit categories, hidden manga-only toggles) read this directly.
     */
    isNovelTab: Boolean = false,
) {
    // Faithful port of the legacy TabbedLibraryDisplaySheet sizing: opens at the equivalent of
    // STATE_EXPANDED (skip the M3 PartiallyExpanded state entirely) so the user sees the full
    // tab content immediately rather than the M3 default ~30-40% partial peek. We still cap
    // the content height to half-screen below so the sheet does not balloon to 95% on tablets.
    // Drag-down naturally dismisses; tap-outside dismisses via the scrim. No confirmValueChange
    // veto — that would block the scrim's Hidden transition and break tap-outside.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    var filterReorderMode by rememberSaveable { mutableStateOf(false) }
    val preferences: PreferencesHelper = Injekt.get()
    val novelPrefs: NovelPreferences = Injekt.get()
    // filterOrder lives alongside the per-library filter values, so route by tab regardless of
    // the shared display-prefs toggle (which is for truly visual settings — grid size, badges).
    val filterOrderPref = rememberRoutedPref(isNovelTab, preferences.filterOrder(), novelPrefs.filterOrder())
    val filterOrder by filterOrderPref.collectAsState()
    val router = LocalRouter.currentOrThrow
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    // containerColor reads ?attr/background straight off the theme (same pattern as the top
    // bar and CategoryPickerSheet). createMdc3Theme does not surface the legacy Reikai
    // custom attr as any M3 ColorScheme token; reading the same attr the library body uses
    // keeps the sheet visually flush with the content above it.
    val context = LocalContext.current
    val sheetContainerColor = androidx.compose.runtime.remember(context) {
        Color(context.getResourceColor(eu.kanade.tachiyomi.R.attr.background))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Suppress the default drag handle. The title row + tab strip serve as the affordance
        // and keep the sheet header from feeling stacked vertically. sheetMaxWidth left at the
        // M3 default (640.dp) so the sheet renders at the same width as CategoryPickerSheet —
        // on tablets that's centered with breathing room either side, on phones it's full
        // width (screen narrower than the cap).
        dragHandle = null,
        containerColor = sheetContainerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(MR.strings.display_options),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (selectedTab == TAB_FILTER) {
                TextButton(
                    onClick = { filterReorderMode = !filterReorderMode },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapVert,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(MR.strings.reorder),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        // Transparent containerColor + a thin secondary-color indicator make the tab strip
        // blend into the sheet surface (no separate colored band). SecondaryTabRow is used
        // instead of PrimaryTabRow so the indicator is the thinner Material3 style. The
        // settings gear icon sits on the same row as the tabs (matches the legacy
        // TabbedLibraryDisplaySheet, which exposes "More library settings" as a corner action).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.weight(1f),
                containerColor = Color.Transparent,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(selectedTab),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                divider = {},
            ) {
                TabLabel(MR.strings.filter, selectedTab == TAB_FILTER) { selectedTab = TAB_FILTER }
                TabLabel(MR.strings.display, selectedTab == TAB_DISPLAY) { selectedTab = TAB_DISPLAY }
                TabLabel(MR.strings.badges, selectedTab == TAB_BADGES) { selectedTab = TAB_BADGES }
                TabLabel(MR.strings.categories, selectedTab == TAB_CATEGORIES) { selectedTab = TAB_CATEGORIES }
            }
            IconButton(
                onClick = {
                    router.pushController(SettingsLibraryController().withFadeTransaction())
                    onDismiss()
                },
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(MR.strings.more_library_settings),
                )
            }
        }
        // Cap body height to half the screen so the Expanded sheet does not grow to ~95% on
        // tablets. Content uses its own verticalScroll (each tab provides its own), so overflow
        // scrolls inside the capped area instead of resizing the sheet.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(horizontal = 16.dp),
        ) {
            when (selectedTab) {
                TAB_FILTER -> FilterTab(
                    detectedMangaTypes = detectedMangaTypes,
                    loggedTrackerNames = loggedTrackerNames,
                    reorderMode = filterReorderMode,
                    filterOrder = filterOrder,
                    onFilterOrderChanged = { filterOrderPref.set(it) },
                    onApply = onDismiss,
                    isNovelTab = isNovelTab,
                )
                TAB_DISPLAY -> DisplayTab(isNovelTab = isNovelTab)
                TAB_BADGES -> BadgesTab(isNovelTab = isNovelTab)
                TAB_CATEGORIES -> CategoriesTab(onDismissSheet = onDismiss, isNovelTab = isNovelTab)
            }
        }
    }
}

@Composable
private fun TabLabel(
    stringRes: dev.icerock.moko.resources.StringResource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = { Text(stringResource(stringRes), style = MaterialTheme.typography.labelLarge) },
    )
}
