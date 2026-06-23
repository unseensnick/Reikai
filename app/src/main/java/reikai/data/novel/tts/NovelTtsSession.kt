package reikai.data.novel.tts

import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.novel.tts.TtsPlayback

/**
 * App-scoped bridge between the per-reader TTS controller and the foreground [NovelTtsService]. Only
 * one read-aloud session is active at a time, so a singleton is enough.
 *
 * The controller pushes [state]; the service renders it as a media-style notification + MediaSession
 * and routes lock-screen / headset / notification actions back through [onPlay] / [onPause] / [onStop].
 */
object NovelTtsSession {
    data class State(val playback: TtsPlayback, val title: String)

    val state = MutableStateFlow(State(TtsPlayback.Stopped, ""))

    var onPlay: () -> Unit = {}
    var onPause: () -> Unit = {}
    var onStop: () -> Unit = {}

    fun reset() {
        onPlay = {}
        onPause = {}
        onStop = {}
        state.value = State(TtsPlayback.Stopped, "")
    }
}
