package reikai.data.novel.tts

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.novel.tts.ReadAloudStop
import reikai.domain.novel.tts.TtsPauseReason
import reikai.domain.novel.tts.TtsPlayback

/**
 * App-scoped bridge between the read aloud session and the foreground [NovelTtsService]. Only one
 * session reads aloud at a time, so a singleton is enough.
 *
 * The session pushes [state]; the service renders it as a media-style notification + MediaSession
 * and routes lock-screen / headset / notification actions back through the callbacks.
 */
object NovelTtsSession {

    /**
     * Read-aloud steps by paragraph, so the service shows [paragraph] of [paragraphCount] as its seek bar.
     * [isAdult] is the novel's verdict, which the notification's privacy switches read. [novelId],
     * [chapterId] and [sourceScoped] name what is read, which the notification reopens the reader on;
     * [stopReason] is set on a Stopped that speech came to by itself.
     */
    data class State(
        val playback: TtsPlayback,
        val title: String,
        val paragraph: Int = 0,
        val paragraphCount: Int = 0,
        val isAdult: Boolean = false,
        val novelId: Long? = null,
        val chapterId: Long? = null,
        val sourceScoped: Boolean = false,
        val stopReason: ReadAloudStop? = null,
    )

    val state: StateFlow<State>
        field = MutableStateFlow(State(TtsPlayback.Stopped, ""))

    val sleepTimer = TtsSleepTimer(SystemClock::elapsedRealtime)

    var onPlay: () -> Unit = {}
    var onPause: (TtsPauseReason) -> Unit = {}
    var onStop: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onPrevious: () -> Unit = {}
    var onSeek: (paragraph: Int) -> Unit = {}

    fun publish(value: State) {
        state.value = value
        sleepTimer.onPublished(value.playback)
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
