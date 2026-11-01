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
import reikai.domain.library.ContentType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private val options = mapOf(
    ContentType.ALL to MR.strings.category_content_type_all,
    ContentType.MANGA to MR.strings.category_content_type_manga,
    ContentType.NOVELS to MR.strings.category_content_type_novels,
)

/** The Show setting, drawn the same in Appearance and on the first-launch theme step. */
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
                options.onEachIndexed { index, (type, labelRes) ->
                    SegmentedButton(
                        checked = type == value,
                        onCheckedChange = { onItemClick(type) },
                        // The longest label wraps on a phone, and the row stays one height.
                        modifier = Modifier.fillMaxHeight(),
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }
        },
    )
}
