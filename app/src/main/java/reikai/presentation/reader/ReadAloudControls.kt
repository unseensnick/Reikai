package reikai.presentation.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import reikai.presentation.icons.Bedtime
import reikai.presentation.icons.FastForward
import reikai.presentation.icons.FastRewind
import reikai.presentation.icons.ReikaiIcons
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The floating read-aloud controls. Stateless: [playing] comes from the session's own playback, so
 * the play button cannot drift from what is actually being spoken.
 */
@Composable
fun ReadAloudControls(
    playing: Boolean,
    sleepTimerActive: Boolean,
    onReadFromHere: () -> Unit,
    onPreviousParagraph: () -> Unit,
    onPlayPause: () -> Unit,
    onNextParagraph: () -> Unit,
    onClickSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onReadFromHere) {
                Icon(MaterialSymbols.Rounded.Visibility, stringResource(MR.strings.tts_read_from_here))
            }
            IconButton(onClick = onPreviousParagraph) {
                Icon(ReikaiIcons.FastRewind, stringResource(MR.strings.tts_previous_paragraph))
            }
            IconButton(onClick = onPlayPause) {
                if (playing) {
                    Icon(MaterialSymbols.RoundedFilled.Pause, stringResource(MR.strings.action_pause))
                } else {
                    Icon(MaterialSymbols.RoundedFilled.PlayArrow, stringResource(MR.strings.tts_play))
                }
            }
            IconButton(onClick = onNextParagraph) {
                Icon(ReikaiIcons.FastForward, stringResource(MR.strings.tts_next_paragraph))
            }
            IconButton(onClick = onClickSleepTimer) {
                Icon(
                    imageVector = ReikaiIcons.Bedtime,
                    contentDescription = stringResource(MR.strings.tts_sleep_timer),
                    tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
    }
}
