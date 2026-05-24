package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.clickable
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
import yokai.i18n.MR
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget

/**
 * Badges tab. Same five preferences the legacy
 * [eu.kanade.tachiyomi.ui.library.display.LibraryBadgesView] writes, in the same order; the
 * unread-badge selector renders as three inline radio rows (hide / show unread / show count) to
 * match the legacy RadioGroup instead of using a dropdown dialog.
 */
@Composable
fun BadgesTab() {
    val preferences: PreferencesHelper = remember { Injekt.get() }

    val unreadBadgeType by preferences.unreadBadgeType().collectAsState()
    val hideStartReadingButton by preferences.hideStartReadingButton().collectAsState()
    val downloadBadge by preferences.downloadBadge().collectAsState()
    val languageBadge by preferences.languageBadge().collectAsState()
    val categoryNumberOfItems by preferences.categoryNumberOfItems().collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        // Legacy unreadBadgeType values: -1 hide, 2 show unread badges (dot), 1 show count.
        // Order matches the legacy RadioGroup top-to-bottom.
        UnreadBadgeRadioRow(MR.strings.hide_unread_badges, -1, unreadBadgeType) {
            preferences.unreadBadgeType().set(it)
        }
        UnreadBadgeRadioRow(MR.strings.show_unread_badges, 2, unreadBadgeType) {
            preferences.unreadBadgeType().set(it)
        }
        UnreadBadgeRadioRow(MR.strings.show_unread_count, 1, unreadBadgeType) {
            preferences.unreadBadgeType().set(it)
        }
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.hide_start_reading_button),
            checked = hideStartReadingButton,
            onCheckedChanged = { preferences.hideStartReadingButton().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.language_badge),
            checked = languageBadge,
            onCheckedChanged = { preferences.languageBadge().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.download_badge),
            checked = downloadBadge,
            onCheckedChanged = { preferences.downloadBadge().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_number_of_items),
            checked = categoryNumberOfItems,
            onCheckedChanged = { preferences.categoryNumberOfItems().set(it) },
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
