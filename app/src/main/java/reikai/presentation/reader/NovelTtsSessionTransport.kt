package reikai.presentation.reader

import android.content.Context
import reikai.data.novel.tts.NovelTtsService
import reikai.data.novel.tts.NovelTtsSession
import reikai.domain.novel.tts.TtsPauseReason

/** The media notification's side of a read aloud session. */
interface ReadAloudNotification {

    /** Routes the notification's controls to these and brings the notification up. */
    fun connect(
        onPlay: () -> Unit,
        onPause: (TtsPauseReason) -> Unit,
        onStop: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
        onSeek: (paragraph: Int) -> Unit,
    )

    fun publish(state: NovelTtsSession.State)

    /** True once when the sleep timer is set to stop at this chapter's end instead of reading on. */
    fun takeStopAtChapterEnd(): Boolean

    /** Takes the notification down, unless another session has connected since. */
    fun release()
}

/**
 * [ReadAloudNotification] over the app-wide [NovelTtsSession]. It only writes to that singleton while the
 * callbacks it installed are still the session's.
 */
class NovelTtsSessionTransport(private val context: Context) : ReadAloudNotification {

    private var installed: (() -> Unit)? = null

    override fun connect(
        onPlay: () -> Unit,
        onPause: (TtsPauseReason) -> Unit,
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

    override fun publish(state: NovelTtsSession.State) {
        if (owns()) NovelTtsSession.publish(state)
    }

    override fun takeStopAtChapterEnd() = owns() && NovelTtsSession.sleepTimer.takeEndOfChapter()

    override fun release() {
        if (owns()) NovelTtsSession.reset()
        installed = null
    }

    private fun owns() = installed != null && NovelTtsSession.onPlay === installed
}
