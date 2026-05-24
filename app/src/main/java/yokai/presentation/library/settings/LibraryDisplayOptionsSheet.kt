package yokai.presentation.library.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
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
 * The four tab indices are exposed as constants so callers (the library toolbar) can open the
 * sheet on a specific tab.
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(MR.strings.display_options),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            TabLabel(MR.strings.filter, selectedTab == TAB_FILTER) { selectedTab = TAB_FILTER }
            TabLabel(MR.strings.display, selectedTab == TAB_DISPLAY) { selectedTab = TAB_DISPLAY }
            TabLabel(MR.strings.badges, selectedTab == TAB_BADGES) { selectedTab = TAB_BADGES }
            TabLabel(MR.strings.categories, selectedTab == TAB_CATEGORIES) { selectedTab = TAB_CATEGORIES }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            when (selectedTab) {
                TAB_FILTER -> FilterTab()
                TAB_DISPLAY -> DisplayTab()
                TAB_BADGES -> BadgesTab()
                TAB_CATEGORIES -> CategoriesTab()
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
