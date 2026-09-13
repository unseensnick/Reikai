package reikai.presentation.reader

import android.content.Context
import reikai.data.novel.tts.NovelTtsService
import reikai.data.novel.tts.NovelTtsSession
import reikai.domain.novel.tts.TtsPlayback

/**
 * [ReadAloudTransport] over the app-wide [NovelTtsSession]. The legacy reader drives that singleton
 * too, so this only writes to it while the callbacks it installed are still the session's.
 */
class NovelTtsSessionTransport(private val context: Context) : ReadAloudTransport {

    private var installed: (() -> Unit)? = null

    override fun connect(onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
        NovelTtsSession.onPlay = onPlay
        NovelTtsSession.onPause = onPause
        NovelTtsSession.onStop = onStop
        installed = onPlay
        NovelTtsService.start(context)
    }

    override fun publish(playback: TtsPlayback, title: String) {
        if (owns()) NovelTtsSession.state.value = NovelTtsSession.State(playback, title)
    }

    override fun release() {
        if (owns()) NovelTtsSession.reset()
        installed = null
    }

    private fun owns() = installed != null && NovelTtsSession.onPlay === installed
}
