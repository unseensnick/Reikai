package reikai.presentation.reader

import android.content.Context
import reikai.data.novel.tts.NovelTtsService
import reikai.data.novel.tts.NovelTtsSession
import reikai.domain.novel.tts.TtsPlayback

/**
 * [ReadAloudTransport] over the app-wide [NovelTtsSession]. It only writes to that singleton while the
 * callbacks it installed are still the session's.
 */
class NovelTtsSessionTransport(private val context: Context) : ReadAloudTransport {

    private var installed: (() -> Unit)? = null

    override fun connect(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onStop: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
        onSeek: (paragraph: Int) -> Unit,
    ) {
        NovelTtsSession.onPlay = onPlay
        NovelTtsSession.onPause = onPause
        NovelTtsSession.onStop = onStop
        NovelTtsSession.onNext = onNext
        NovelTtsSession.onPrevious = onPrevious
        NovelTtsSession.onSeek = onSeek
        installed = onPlay
        NovelTtsService.start(context)
    }

    override fun publish(playback: TtsPlayback, title: String, paragraph: Int, paragraphCount: Int) {
        if (!owns()) return
        val capability = NovelTtsSession.Capability.Paragraphs(paragraph, paragraphCount)
        NovelTtsSession.publish(NovelTtsSession.State(playback, title, capability))
    }

    override fun takeStopAtChapterEnd() = owns() && NovelTtsSession.sleepTimer.takeEndOfChapter()

    override fun release() {
        if (owns()) NovelTtsSession.reset()
        installed = null
    }

    private fun owns() = installed != null && NovelTtsSession.onPlay === installed
}
