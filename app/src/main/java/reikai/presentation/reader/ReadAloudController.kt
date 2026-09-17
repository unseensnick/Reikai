package reikai.presentation.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.TtsPiece
import reikai.domain.novel.tts.TtsPlayback
import kotlin.math.abs

data class ReadAloudState(
    val playback: TtsPlayback = TtsPlayback.Stopped,
)

/** The chapters read-aloud can move through, answered by the reader model. */
interface ReadAloudNavigation {

    /** The chapter a forward step from [chapterId] lands on, null when there is none. */
    fun chapterAfter(chapterId: Long): Long?

    /** Opens [chapterId] as a forward step that is not the reader navigating away from read-aloud. */
    fun openForReadAloud(chapterId: Long)

    fun titleOf(chapterId: Long): String
}

/** The media notification's side of playback. */
interface ReadAloudTransport {

    /** Routes the notification's controls to these and brings the notification up. */
    fun connect(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onStop: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
        onSeek: (paragraph: Int) -> Unit,
    )

    fun publish(playback: TtsPlayback, title: String, paragraph: Int, paragraphCount: Int)

    /** True once when the sleep timer is set to stop at this chapter's end instead of reading on. */
    fun takeStopAtChapterEnd(): Boolean

    /** Takes the notification down, unless another reader has connected since. */
    fun release()
}

/**
 * Read-aloud on the shared reader host: which paragraph is spoken, over [NovelTtsEngine] for the voice
 * and a [ReadAloudSurface] for the text. Speech outlives the renderer, which the Activity rebuilds, so
 * the surface comes and goes through [attach] and [detach]. Everything runs on [scope], so call from
 * its thread; engine callbacks arrive on a binder thread and are moved onto it.
 */
class ReadAloudController(
    private val scope: CoroutineScope,
    private val preferences: NovelPreferences,
    private val createEngine: (enginePackage: String, onInit: (ready: Boolean) -> Unit) -> NovelTtsEngine,
    private val navigation: ReadAloudNavigation,
    private val transport: ReadAloudTransport,
) {

    val state: StateFlow<ReadAloudState>
        field = MutableStateFlow(ReadAloudState())

    private var surface: ReadAloudSurface? = null

    private var engine: NovelTtsEngine? = null
    private var enginePackage = ""
    private var engineReady = false

    /** Raised whenever an engine is dropped, so a late init from it is not taken for the current one's. */
    private var engineGeneration = 0

    /** A paragraph is waiting for the engine to finish starting. */
    private var awaitingInit = false

    private var playback = TtsPlayback.Stopped
    private var chapterId: Long? = null
    private var paragraphs: List<String> = emptyList()
    private var position: ReadAloudPosition? = null

    /** The sentence of [position] being spoken, while sentences are marked; null marks the paragraph. */
    private var sentence: IntRange? = null

    /**
     * [position]'s paragraph as spoken by sentence, and which of them is playing, so a step moves one
     * sentence. Empty while paragraphs are spoken whole, where a step moves a paragraph.
     */
    private var sentences: List<TtsPiece> = emptyList()
    private var sentenceIndex = 0

    /**
     * The renderer started over and [position] has not been found in its layout yet. Kept until it is,
     * since the chapter being read can join the window only after the landing.
     */
    private var unplaced = false

    /** The chapter opened for read-aloud to carry on in once the renderer lands on it. */
    private var pendingChapter: Long? = null

    /** Raised by every speak and every command, so a finish from speech since replaced is ignored. */
    private var token = 0
    private var job: Job? = null

    private val remotePlay = { play() }
    private val remotePause = { pause() }
    private val remoteStop = { stop() }
    private val remoteNext = { nextParagraph() }
    private val remotePrevious = { previousParagraph() }
    private val remoteSeek = { index: Int -> seekToParagraph(index) }

    init {
        preferences.readerTtsRate().changes().onEach { readyEngine()?.setRate(it) }.launchIn(scope)
        preferences.readerTtsPitch().changes().onEach { readyEngine()?.setPitch(it) }.launchIn(scope)
        preferences.readerTtsVoice().changes().onEach { readyEngine()?.setVoice(it) }.launchIn(scope)
        preferences.readerTtsEngine().changes()
            .onEach {
                if (engine == null || it == enginePackage) return@onEach
                discardEngine()
                if (playback != TtsPlayback.Stopped) stop()
            }
            .launchIn(scope)
    }

    fun attach(surface: ReadAloudSurface) {
        this.surface = surface
    }

    fun detach(surface: ReadAloudSurface) {
        if (this.surface === surface) this.surface = null
    }

    fun play() {
        when (playback) {
            TtsPlayback.Playing -> Unit
            // Paused while the next chapter opens: the landing starts it, where a command would drop the
            // handoff and speak the last chapter's final paragraph again.
            TtsPlayback.Paused -> if (pendingChapter != null) {
                setPlayback(TtsPlayback.Playing)
            } else {
                // By sentence, resuming carries on from the sentence paused in rather than its paragraph's start.
                command { playAt(position?.paragraph ?: 0, fromSentence = sentenceIndex) }
            }
            TtsPlayback.Stopped -> readFromHere()
        }
    }

    fun readFromHere() = command { startFromViewport() }

    fun pause() {
        if (playback != TtsPlayback.Playing) return
        token++
        job?.cancel()
        awaitingInit = false
        engine?.stop()
        setPlayback(TtsPlayback.Paused)
    }

    fun nextParagraph() = step(1)

    fun previousParagraph() = step(-1)

    fun seekToParagraph(index: Int) {
        if (chapterId == null || paragraphs.isEmpty()) return
        command { playAt(index.coerceIn(0, paragraphs.lastIndex)) }
    }

    fun stop() {
        token++
        job?.cancel()
        job = null
        pendingChapter = null
        awaitingInit = false
        engine?.stop()
        chapterId = null
        paragraphs = emptyList()
        position = null
        sentence = null
        sentences = emptyList()
        unplaced = false
        playback = TtsPlayback.Stopped
        surface?.highlight(null)
        publish()
    }

    fun shutdown() {
        stop()
        discardEngine()
        transport.release()
    }

    /** The reader opened a chapter itself, which read-aloud does not follow. */
    fun onUserNavigated() {
        if (playback != TtsPlayback.Stopped) stop()
    }

    /** Safe from any thread, since a load fails on the loader's. */
    fun onChapterLoadFailed() {
        scope.launch { if (pendingChapter != null) stop() }
    }

    /**
     * The renderer started over on a window anchored at [landedId]. Either that is the chapter read-aloud
     * opened, or the same text was laid out again (a rotation, a mode switch, a setting reload), where
     * the paragraphs may have shifted and speech already going must not restart.
     */
    fun onRendererLanded(landedId: Long) {
        if (pendingChapter == landedId) {
            command {
                val landed = ask { paragraphs(landedId) } ?: return@command stop()
                if (playback == TtsPlayback.Paused) holdAt(landedId, landed) else beginChapter(landedId, landed, 0)
            }
            return
        }
        if (playback == TtsPlayback.Stopped) return
        unplaced = true
        // Drawn as soon as the renderer can, since a layout that did not shift needs no relocation.
        surface?.highlight(position, sentence)
        scope.launch { relocate() }
    }

    /** The renderer's window gained or lost chapters, which can bring in the one being read. */
    fun onWindowChanged() {
        // Only while unplaced: the page rebuilds a chapter's paragraph map for a question after any change.
        if (unplaced) scope.launch { relocate() }
    }

    /**
     * One paragraph, or one sentence while sentences are spoken, crossing into the neighbouring paragraph
     * past either end: forward to its first sentence, back to its last.
     */
    private fun step(delta: Int) {
        val at = position ?: return readFromHere()
        val target = sentenceIndex + delta
        command {
            when {
                sentences.isEmpty() -> playAt((at.paragraph + delta).coerceAtLeast(0))
                target in sentences.indices -> playAt(at.paragraph, fromSentence = target)
                delta > 0 -> playAt(at.paragraph + 1)
                at.paragraph > 0 -> playAt(at.paragraph - 1, fromSentence = LAST_SENTENCE)
                else -> playAt(0)
            }
        }
    }

    /** Replaces whatever was in progress, including the paragraph being spoken, with [block]. */
    private fun command(block: suspend () -> Unit) {
        token++
        pendingChapter = null
        job?.cancel()
        job = scope.launch { block() }
    }

    private suspend fun startFromViewport() {
        val at = ask { firstVisibleParagraph() } ?: return stop()
        val shown = ask { paragraphs(at.chapterId) } ?: return stop()
        beginChapter(at.chapterId, shown, at.paragraph)
    }

    private suspend fun beginChapter(id: Long, chapter: List<String>, index: Int) {
        chapterId = id
        paragraphs = chapter
        unplaced = false
        if (chapter.isEmpty()) return endChapter()
        playAt(index.coerceIn(0, chapter.lastIndex))
    }

    /** Speaks paragraph [index] from sentence [fromSentence], or from its last for [LAST_SENTENCE]. */
    private suspend fun playAt(index: Int, fromSentence: Int = 0) {
        val id = chapterId ?: return stop()
        if (index > paragraphs.lastIndex) return endChapter()
        position = ReadAloudPosition(id, index)
        sentence = null
        sentences = emptyList()
        sentenceIndex = fromSentence
        setPlayback(TtsPlayback.Playing)
        speakCurrent()
    }

    private suspend fun endChapter() {
        val ended = chapterId ?: return stop()
        if (transport.takeStopAtChapterEnd()) return stop()
        if (!preferences.readerTtsAutoPageAdvance().get()) return stop()
        val next = navigation.chapterAfter(ended) ?: return stop()
        // A renderer holding a window already has it, and carrying on there keeps the reader's scroll.
        val held = ask { paragraphs(next) }
        if (held != null) return beginChapter(next, held, 0)
        pendingChapter = next
        navigation.openForReadAloud(next)
    }

    /** Paused across a chapter handoff: take the new chapter up without speaking it. */
    private fun holdAt(id: Long, chapter: List<String>) {
        chapterId = id
        paragraphs = chapter
        unplaced = false
        position = ReadAloudPosition(id, 0)
        sentence = null
        surface?.highlight(position)
        publish()
    }

    private fun speakCurrent() {
        val at = position ?: return
        val bySentence = preferences.readerTtsHighlight().get() && preferences.readerTtsHighlightSentence().get()
        // Marked sentence by sentence as each starts, so marking the paragraph first would flash all of it.
        if (!bySentence) surface?.highlight(at)
        val speaker = engine ?: buildEngine()
        // Building can fail at once, which has already stopped playback and dropped the engine.
        if (playback != TtsPlayback.Playing || engine !== speaker) return
        if (!engineReady) {
            awaitingInit = true
            return
        }
        val spoken = ++token
        val pieces = speaker.pieces(paragraphs[at.paragraph], bySentence)
        sentences = if (bySentence) pieces else emptyList()
        val first = if (sentenceIndex ==
            LAST_SENTENCE
        ) {
            pieces.lastIndex
        } else {
            sentenceIndex.coerceIn(0, maxOf(0, pieces.lastIndex))
        }
        sentenceIndex = first
        speaker.speak(
            pieces = pieces.drop(first),
            onPieceStart = { index -> if (bySentence) scope.launch { onSentenceStart(spoken, first + index) } },
            onDone = { scope.launch { onParagraphDone(spoken) } },
        )
    }

    private fun onSentenceStart(spoken: Int, index: Int) {
        if (spoken != token || playback != TtsPlayback.Playing) return
        val piece = sentences.getOrNull(index) ?: return
        sentenceIndex = index
        sentence = piece.start until piece.end
        surface?.highlight(position, sentence)
    }

    private fun onParagraphDone(spoken: Int) {
        if (spoken != token || playback != TtsPlayback.Playing) return
        command { playAt((position?.paragraph ?: 0) + 1) }
    }

    /** Finds [position] in the renderer's layout, or leaves it unplaced while the window lacks its chapter. */
    private suspend fun relocate() {
        val id = chapterId ?: return
        val laidOut = ask { paragraphs(id) }?.takeIf { it.isNotEmpty() } ?: return
        // Read after the query, since speech may have moved on while the renderer answered.
        val at = position?.takeIf { it.chapterId == id && unplaced } ?: return
        unplaced = false
        val spokenText = paragraphs.getOrNull(at.paragraph)
        val index = laidOut.indices
            .filter { laidOut[it] == spokenText }
            .minByOrNull { abs(it - at.paragraph) }
            ?: at.paragraph.coerceIn(0, laidOut.lastIndex)
        paragraphs = laidOut
        position = ReadAloudPosition(id, index)
        // The sentence's offsets hold only in the text they were taken from, which a different match is not.
        if (laidOut[index] != spokenText) sentence = null
        surface?.highlight(position, sentence)
        publish()
    }

    private fun buildEngine(): NovelTtsEngine {
        val generation = ++engineGeneration
        enginePackage = preferences.readerTtsEngine().get()
        engineReady = false
        val built = createEngine(enginePackage) { ready -> scope.launch { onEngineInit(generation, ready) } }
        // An init that failed before the constructor returned has already discarded this generation.
        if (generation == engineGeneration) engine = built else built.shutdown()
        return built
    }

    private fun onEngineInit(generation: Int, ready: Boolean) {
        if (generation != engineGeneration) return
        if (!ready) {
            discardEngine()
            stop()
            return
        }
        engineReady = true
        engine?.let {
            it.setRate(preferences.readerTtsRate().get())
            it.setPitch(preferences.readerTtsPitch().get())
            it.setVoice(preferences.readerTtsVoice().get())
        }
        if (awaitingInit) {
            awaitingInit = false
            if (playback == TtsPlayback.Playing) speakCurrent()
        }
    }

    private fun discardEngine() {
        engineGeneration++
        engine?.shutdown()
        engine = null
        engineReady = false
        awaitingInit = false
    }

    private fun readyEngine() = engine?.takeIf { engineReady }

    /** Waits as long as the renderer takes: it answers every question, with null once it is replaced. */
    private suspend fun <T> ask(query: suspend ReadAloudSurface.() -> T?): T? = surface?.query()

    private fun setPlayback(value: TtsPlayback) {
        if (value == TtsPlayback.Playing && playback != TtsPlayback.Playing) {
            transport.connect(remotePlay, remotePause, remoteStop, remoteNext, remotePrevious, remoteSeek)
        }
        playback = value
        publish()
    }

    private companion object {
        /** A [playAt] start naming the paragraph's last sentence, for a step back into it. */
        const val LAST_SENTENCE = -1
    }

    private fun publish() {
        state.value = ReadAloudState(playback)
        val title = chapterId?.let(navigation::titleOf).orEmpty()
        transport.publish(playback, title, position?.paragraph ?: 0, paragraphs.size)
    }
}
