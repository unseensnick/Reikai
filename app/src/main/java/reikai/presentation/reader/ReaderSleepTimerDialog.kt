package reikai.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.coroutines.delay
import reikai.data.novel.tts.SleepTimer
import reikai.data.novel.tts.TtsSleepTimer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

/** The read-aloud sleep timer picker. A pick applies at once and closes the sheet. */
@Composable
fun ReaderSleepTimerDialog(
    timer: SleepTimer,
    minutesLeft: (SleepTimer.At) -> Int,
    onOff: () -> Unit,
    onMinutes: (Int) -> Unit,
    onEndOfChapter: () -> Unit,
    onDismiss: () -> Unit,
) {
    val at = timer as? SleepTimer.At
    // Asked again each second while the sheet is open, since the remainder moves while the timer does not.
    val left by produceState(at?.let(minutesLeft), at) {
        while (at != null) {
            value = minutesLeft(at)
            delay(1.seconds)
        }
    }
    val runningFrom = at?.minutes
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = stringResource(MR.strings.tts_sleep_timer),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            left?.let { minutes ->
                Text(
                    text = pluralStringResource(MR.plurals.tts_sleep_timer_minutes_left, minutes, minutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            RadioItem(stringResource(MR.strings.off), timer == SleepTimer.Off) {
                onOff()
                onDismiss()
            }
            TtsSleepTimer.MINUTES.forEach { minutes ->
                RadioItem(
                    label = pluralStringResource(MR.plurals.tts_sleep_timer_minutes, minutes, minutes),
                    selected = minutes == runningFrom,
                ) {
                    onMinutes(minutes)
                    onDismiss()
                }
            }
            RadioItem(stringResource(MR.strings.tts_sleep_timer_end_of_chapter), timer == SleepTimer.EndOfChapter) {
                onEndOfChapter()
                onDismiss()
            }
        }
    }
}
