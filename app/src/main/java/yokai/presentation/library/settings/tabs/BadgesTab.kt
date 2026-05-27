package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.base.BasePreferences
import yokai.domain.novel.NovelPreferences
import yokai.i18n.MR
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget

/**
 * Badges tab. Same five preferences the legacy
 * [eu.kanade.tachiyomi.ui.library.display.LibraryBadgesView] writes, in the same order; the
 * unread-badge selector renders as three inline radio rows (hide / show unread / show count) to
 * match the legacy RadioGroup instead of using a dropdown dialog.
 */
@Composable
fun BadgesTab(
    /** True when this tab is rendered inside the Novels-tab Display sheet. Routes all five
     *  badge prefs through `novelPrefs.*` when the shared toggle is off. */
    isNovelTab: Boolean = false,
) {
    val preferences: PreferencesHelper = remember { Injekt.get() }
    val novelPrefs: NovelPreferences = remember { Injekt.get() }
    val basePrefs: BasePreferences = remember { Injekt.get() }
    val useSharedLibraryDisplayPrefs by basePrefs.useSharedLibraryDisplayPrefs().collectAsState()
    val routeToNovel = isNovelTab && !useSharedLibraryDisplayPrefs

    val unreadBadgeTypePref = rememberRoutedPref(routeToNovel, preferences.unreadBadgeType(), novelPrefs.novelUnreadBadgeType())
    val unreadBadgeType by unreadBadgeTypePref.collectAsState()
    val hideStartReadingButtonPref = rememberRoutedPref(routeToNovel, preferences.hideStartReadingButton(), novelPrefs.novelHideStartReadingButton())
    val hideStartReadingButton by hideStartReadingButtonPref.collectAsState()
    val downloadBadgePref = rememberRoutedPref(routeToNovel, preferences.downloadBadge(), novelPrefs.novelDownloadBadge())
    val downloadBadge by downloadBadgePref.collectAsState()
    val languageBadgePref = rememberRoutedPref(routeToNovel, preferences.languageBadge(), novelPrefs.novelLanguageBadge())
    val languageBadge by languageBadgePref.collectAsState()
    val categoryNumberOfItemsPref = rememberRoutedPref(routeToNovel, preferences.categoryNumberOfItems(), novelPrefs.novelCategoryNumberOfItems())
    val categoryNumberOfItems by categoryNumberOfItemsPref.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Legacy `unreadBadgeType` values, matching the legacy RadioGroup's child-index binding
        // (see LibraryBadgesView.initGeneralPreferences and library_badges_layout.xml):
        //   0 = hide, 1 = show unread badges (dot), 2 = show unread count.
        // LibraryHolder.setUnreadBadge keys rendering off these values; the Compose grid cells
        // must agree (see `unreadDot` derivation in LibraryContent / LibraryGridCell).
        UnreadBadgeRadioRow(MR.strings.hide_unread_badges, 0, unreadBadgeType) {
            unreadBadgeTypePref.set(it)
        }
        UnreadBadgeRadioRow(MR.strings.show_unread_badges, 1, unreadBadgeType) {
            unreadBadgeTypePref.set(it)
        }
        UnreadBadgeRadioRow(MR.strings.show_unread_count, 2, unreadBadgeType) {
            unreadBadgeTypePref.set(it)
        }
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.hide_start_reading_button),
            checked = hideStartReadingButton,
            onCheckedChanged = { hideStartReadingButtonPref.set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.language_badge),
            checked = languageBadge,
            onCheckedChanged = { languageBadgePref.set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.download_badge),
            checked = downloadBadge,
            onCheckedChanged = { downloadBadgePref.set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_number_of_items),
            checked = categoryNumberOfItems,
            onCheckedChanged = { categoryNumberOfItemsPref.set(it) },
        )
    }
}

@Composable
private fun UnreadBadgeRadioRow(
    label: dev.icerock.moko.resources.StringResource,
    value: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected == value,
            onClick = null,
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
