package yokai.presentation.library.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsLibraryController
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.presentation.library.settings.tabs.BadgesTab
import yokai.presentation.library.settings.tabs.CategoriesTab
import yokai.presentation.library.settings.tabs.DisplayTab
import yokai.presentation.library.settings.tabs.FilterTab

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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    var filterReorderMode by rememberSaveable { mutableStateOf(false) }
    val preferences: PreferencesHelper = Injekt.get()
    val filterOrder by preferences.filterOrder().collectAsState()
    val router = LocalRouter.currentOrThrow

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Suppress the default drag handle. The title row + tab strip serve as the affordance
        // and keep the sheet header from feeling stacked vertically.
        dragHandle = null,
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
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            when (selectedTab) {
                TAB_FILTER -> FilterTab(
                    detectedMangaTypes = detectedMangaTypes,
                    loggedTrackerNames = loggedTrackerNames,
                    reorderMode = filterReorderMode,
                    filterOrder = filterOrder,
                    onFilterOrderChanged = { preferences.filterOrder().set(it) },
                )
                TAB_DISPLAY -> DisplayTab()
                TAB_BADGES -> BadgesTab()
                TAB_CATEGORIES -> CategoriesTab(onDismissSheet = onDismiss)
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
