package reikai.presentation.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One reader's view of read aloud: the session while it reads this reader's series ([novelId] in
 * [sourceScoped]), else nothing playing. Every verb of the reader's goes through here, so a reader of
 * another series cannot pause, step or mark someone else's speech, and a play there starts this
 * series instead. [memberIds] are the series' novels as the reader resolved them.
 */
class ReadAloudLink(
    private val session: ReadAloudSession,
    val novelId: Long,
    val sourceScoped: Boolean,
    private val memberIds: () -> List<Long>,
) {

    private var surface: ReadAloudSurface? = null

    /** Speech as this reader sees it. */
    val state: Flow<ReadAloudSessionState> = session.state
        .map { if (it.isOf(novelId, sourceScoped)) it else ReadAloudSessionState() }
        .distinctUntilChanged()

    fun owns(): Boolean = session.state.value.isOf(novelId, sourceScoped)

    fun play() {
        if (!owns()) start()
        session.play()
    }

    fun readFromHere() {
        if (!owns()) start()
        session.readFromHere()
    }

    fun pause() {
        if (owns()) session.pause()
    }

    fun stop() {
        if (owns()) session.stop()
    }

    fun nextParagraph() {
        if (owns()) session.nextParagraph() else play()
    }

    fun previousParagraph() {
        if (owns()) session.previousParagraph() else play()
    }

    /** The reader opened a chapter itself, which read aloud does not follow: one series, one place. */
    fun onUserNavigated() = stop()

    fun attach(surface: ReadAloudSurface) {
        this.surface = surface
        if (owns()) session.attach(surface)
    }

    fun detach(surface: ReadAloudSurface) {
        if (this.surface === surface) this.surface = null
        session.detach(surface)
    }

    fun onRendererLanded() {
        if (owns()) session.onRendererLanded()
    }

    fun onWindowChanged() {
        if (owns()) session.onWindowChanged()
    }

    private fun start() = session.start(ReadAloudSeries(novelId, memberIds(), sourceScoped), surface)
}
