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
 * Badges tab: unread / download / language badges, the "hide start reading" cover button, and
 * the per-category item-count toggle. Same five preferences the legacy
 * [eu.kanade.tachiyomi.ui.library.display.LibraryBadgesView] writes.
 */
@Composable
fun BadgesTab() {
    val preferences: PreferencesHelper = remember { Injekt.get() }

    val unreadBadgeType by preferences.unreadBadgeType().collectAsState()
    val hideStartReadingButton by preferences.hideStartReadingButton().collectAsState()
    val downloadBadge by preferences.downloadBadge().collectAsState()
    val languageBadge by preferences.languageBadge().collectAsState()
    val categoryNumberOfItems by preferences.categoryNumberOfItems().collectAsState()

    // Matches the legacy unreadBadgeGroup spinner values: -1 hide, 1 show count, 2 show dot.
    val unreadEntries: Map<Int, String> = mapOf(
        -1 to stringResource(MR.strings.hide_unread_badges),
        2 to stringResource(MR.strings.show_unread_badges),
        1 to stringResource(MR.strings.show_unread_count),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        ListPreferenceWidget(
            value = unreadBadgeType,
            title = stringResource(MR.strings.show_unread_badges),
            subtitle = unreadEntries[unreadBadgeType],
            icon = null,
            entries = unreadEntries,
            onValueChange = { preferences.unreadBadgeType().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.download_badge),
            checked = downloadBadge,
            onCheckedChanged = { preferences.downloadBadge().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.language_badge),
            checked = languageBadge,
            onCheckedChanged = { preferences.languageBadge().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.hide_start_reading_button),
            checked = hideStartReadingButton,
            onCheckedChanged = { preferences.hideStartReadingButton().set(it) },
        )
        SwitchPreferenceWidget(
            title = stringResource(MR.strings.show_number_of_items),
            checked = categoryNumberOfItems,
            onCheckedChanged = { preferences.categoryNumberOfItems().set(it) },
        )
    }
}
