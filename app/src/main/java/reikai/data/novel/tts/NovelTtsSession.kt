package reikai.data.novel.tts

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.novel.tts.TtsPlayback

/**
 * App-scoped bridge between the per-reader TTS controller and the foreground [NovelTtsService]. Only
 * one read-aloud session is active at a time, so a singleton is enough.
 *
 * The controller pushes [state]; the service renders it as a media-style notification + MediaSession
 * and routes lock-screen / headset / notification actions back through the callbacks.
 */
object NovelTtsSession {

    /** What the owner playing can do beyond play, pause and stop, so no control it cannot answer is shown. */
    sealed interface Capability {

        data object PlayPauseStop : Capability

        /** Reads paragraph by paragraph, so it can step, seek, and stop at a chapter's end. */
        data class Paragraphs(val index: Int, val count: Int) : Capability
    }

    data class State(
        val playback: TtsPlayback,
        val title: String,
        val capability: Capability = Capability.PlayPauseStop,
    )

    val state: StateFlow<State>
        field = MutableStateFlow(State(TtsPlayback.Stopped, ""))

    val sleepTimer = TtsSleepTimer(SystemClock::elapsedRealtime)

    var onPlay: () -> Unit = {}
    var onPause: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onPrevious: () -> Unit = {}
    var onSeek: (paragraph: Int) -> Unit = {}

    fun publish(value: State) {
        state.value = value
        sleepTimer.onPublished(
            stopped = value.playback == TtsPlayback.Stopped,
            reportsChapterEnd = value.capability is Capability.Paragraphs,
        )
    }

    fun reset() {
        onPlay = {}
        onPause = {}
        onStop = {}
        onNext = {}
        onPrevious = {}
        onSeek = {}
        publish(State(TtsPlayback.Stopped, ""))
    }
}
