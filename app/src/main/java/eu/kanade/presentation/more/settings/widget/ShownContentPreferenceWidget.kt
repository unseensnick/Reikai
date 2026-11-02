package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import reikai.domain.category.toCategoryContentType
import reikai.domain.library.ContentType
import reikai.presentation.category.categoryContentTypeLabel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The Show setting, drawn the same in Appearance and on the first-launch theme step. Follows Mihon's
 * AppThemeModePreferenceWidget rather than sharing it: that one is typed to ThemeMode and has no title,
 * and this one needs a row that keeps one height when a label wraps.
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
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = PrefsHorizontalPadding),
            ) {
                ContentType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        checked = type == value,
                        onCheckedChange = { onItemClick(type) },
                        // The longest label wraps on a phone, and the row stays one height.
                        modifier = Modifier.fillMaxHeight(),
                        shape = SegmentedButtonDefaults.itemShape(index, ContentType.entries.size),
                    ) {
                        Text(stringResource(categoryContentTypeLabel(type.toCategoryContentType())))
                    }
                }
            }
        },
    )
}
