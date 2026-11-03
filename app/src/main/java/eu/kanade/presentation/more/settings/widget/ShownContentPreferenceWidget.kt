package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import reikai.domain.library.ContentType
import reikai.domain.library.labelRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The Show setting, drawn the same in Appearance and on the first-launch theme step. Follows Mihon's
 * AppThemeModePreferenceWidget rather than sharing it: that one is typed to ThemeMode and has no title.
 * Labelled All / Manga / Novels, as the library's own switch is, so no label wraps on a phone.
 */
@Composable
internal fun ShownContentPreferenceWidget(
    value: ContentType,
    onItemClick: (ContentType) -> Unit,
) {
    BasePreferenceWidget(
        title = stringResource(MR.strings.pref_shown_content),
        subcomponent = {
            MultiChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PrefsHorizontalPadding),
            ) {
                ContentType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        checked = type == value,
                        onCheckedChange = { onItemClick(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, ContentType.entries.size),
                    ) {
                        Text(stringResource(type.labelRes))
                    }
                }
            }
        },
    )
}
