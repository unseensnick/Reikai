package reikai.presentation.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.util.toDurationString
import reikai.domain.reader.ReaderTimeLeft
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes

/** Time left in whole minutes, so a page turn does not tick seconds that an estimate cannot promise. */
@Composable
fun readerTimeLeftLabel(timeLeft: ReaderTimeLeft): String = when (timeLeft) {
    is ReaderTimeLeft.Learning -> stringResource(
        if (timeLeft.listening) {
            MR.strings.reader_time_left_learning_listening
        } else {
            MR.strings.reader_time_left_learning
        },
    )
    is ReaderTimeLeft.Remaining -> {
        val minutes = (timeLeft.duration.inWholeSeconds / 60.0).roundToLong()
        if (minutes == 0L) {
            stringResource(MR.strings.reader_time_left_under_minute)
        } else {
            val shown = minutes.minutes.toDurationString(LocalContext.current, fallback = "")
            stringResource(MR.strings.reader_time_left, shown)
        }
    }
}
