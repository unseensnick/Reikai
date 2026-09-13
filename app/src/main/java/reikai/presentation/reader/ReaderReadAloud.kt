package reikai.presentation.reader

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow
import reikai.data.novel.tts.SleepTimer
import reikai.domain.novel.tts.TtsPlayback

@Immutable
data class ReaderReadAloudState(
    val playback: TtsPlayback = TtsPlayback.Stopped,
    val controlsVisible: Boolean = false,
    val sleepTimer: SleepTimer = SleepTimer.Off,
)

/**
 * Reading the chapter aloud, for a content type whose text the reader draws. Null for one whose pages
 * are images, so the bar button and its controls are absent rather than present and dead.
 *
 * Speech belongs to the session rather than the chrome, so hiding the controls never stops it.
 */
@Stable
interface ReaderReadAloud {

    val state: Flow<ReaderReadAloudState>

    fun play()

    fun pause()

    fun stop()

    fun readFromHere()

    fun previousParagraph()

    fun nextParagraph()

    /** A flip rather than a set, because the button shows the state it is inverting. */
    fun toggleControls()

    fun setSleepTimer(minutes: Int)

    fun setSleepTimerEndOfChapter()

    fun clearSleepTimer()

    /** Asked at the time of reading, since a countdown's remainder moves while nothing else does. */
    fun minutesLeft(timer: SleepTimer.At): Int
}
