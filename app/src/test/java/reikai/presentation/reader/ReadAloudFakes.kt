package reikai.presentation.reader

import reikai.data.novel.tts.NovelTtsSession
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsPauseReason
import reikai.domain.novel.tts.TtsPiece
import reikai.domain.novel.tts.TtsUtteranceSplitter
import reikai.domain.novel.tts.TtsVoice
import java.util.Locale

/** A voice that says what it is given once a test lets each paragraph finish. */
class HarnessTtsEngine(val enginePackage: String, private val onInit: (Boolean) -> Unit) : NovelTtsEngine {

    val spoken = mutableListOf<String>()
    private val finishes = mutableListOf<() -> Unit>()
    var shutDown = false

    fun init(ready: Boolean = true) = onInit(ready)

    /** The voice finishing the paragraph it was given last, having said it unless [said] is false. */
    fun finishLast(said: Boolean = true) {
        if (said) starts.last()(0)
        finishes.last()()
    }

    private val starts = mutableListOf<(Int) -> Unit>()

    override fun availableEngines() = emptyList<TtsEngineInfo>()
    override fun availableVoices() = emptyList<TtsVoice>()
    override fun setVoice(voiceName: String) = Unit
    override fun setRate(rate: Float) = Unit
    override fun setPitch(pitch: Float) = Unit

    override fun pieces(text: String, bySentence: Boolean): List<TtsPiece> =
        TtsUtteranceSplitter.pieces(text, maxLength = 1000, Locale.ENGLISH, bySentence)

    override fun speak(pieces: List<TtsPiece>, onPieceStart: (index: Int) -> Unit, onDone: () -> Unit) {
        spoken += pieces.joinToString(" ") { it.text }
        starts += onPieceStart
        finishes += onDone
    }

    override fun stop() = Unit

    override fun shutdown() {
        shutDown = true
    }
}

/** The notification as the service would show it, with its controls for a test to press. */
class HarnessNotification : ReadAloudNotification {

    val published = mutableListOf<NovelTtsSession.State>()
    var connected = false
    var released = 0
    var stopAtChapterEnd = false
    var onPlay: () -> Unit = {}
    var onPause: (TtsPauseReason) -> Unit = {}
    var onStop: () -> Unit = {}

    val last: NovelTtsSession.State? get() = published.lastOrNull()

    override fun connect(
        onPlay: () -> Unit,
        onPause: (TtsPauseReason) -> Unit,
        onStop: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
        onSeek: (paragraph: Int) -> Unit,
    ) {
        connected = true
        this.onPlay = onPlay
        this.onPause = onPause
        this.onStop = onStop
    }

    override fun publish(state: NovelTtsSession.State) {
        published += state
    }

    override fun takeStopAtChapterEnd() = stopAtChapterEnd.also { stopAtChapterEnd = false }

    override fun release() {
        released++
        connected = false
    }
}

/** A text renderer's read aloud side, holding the chapters a test puts on screen. */
class HarnessSurface(
    val chapters: MutableMap<Long, List<String>> = mutableMapOf(),
    var firstVisible: ReadAloudPosition? = null,
) : ReadAloudSurface {

    val highlights = mutableListOf<ReadAloudPosition?>()

    override suspend fun paragraphs(chapterId: Long): List<String>? = chapters[chapterId]

    override suspend fun firstVisibleParagraph(): ReadAloudPosition? = firstVisible

    override fun highlight(position: ReadAloudPosition?, range: IntRange?, follows: Boolean) {
        highlights += position
    }
}
