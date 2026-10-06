package reikai.presentation.details

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.shouldExpandFAB

/** The resume/start FAB both details shells (phone and two-pane) put in their Scaffold. It collapses to
 *  its icon while [chapterListState] scrolls. */
@Composable
internal fun EntryDetailsFab(
    chapterListState: LazyListState,
    visible: Boolean,
    isResume: Boolean,
    onClick: () -> Unit,
) {
    SmallExtendedFloatingActionButton(
        text = {
            Text(text = stringResource(if (isResume) MR.strings.action_resume else MR.strings.action_start))
        },
        icon = { Icon(imageVector = MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null) },
        onClick = onClick,
        expanded = chapterListState.shouldExpandFAB(),
        modifier = Modifier.animateFloatingActionButton(
            visible = visible,
            alignment = Alignment.BottomEnd,
        ),
    )
}
