package reikai.presentation.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.text.NovelWords
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.ReadAloudStop
import reikai.domain.novel.tts.TtsPiece
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.reader.ReadAloudProgress
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** [progress] is where speech is in its chapter, null before a chapter is taken up. */
data class ReadAloudState(
    val playback: TtsPlayback = TtsPlayback.Stopped,
    val progress: ReadAloudProgress? = null,
)

/** The chapters read-aloud can move through and where it has got to, answered by the session. */
interface ReadAloudNavigation {

    /** The chapter a forward step from [chapterId] lands on, null when there is none. */
    suspend fun chapterAfter(chapterId: Long): Long?

    /** Speech has reached [share] of [chapterId]'s words, saved as the reader's place in it. */
    fun savePosition(chapterId: Long, share: Double)

    /** Speech has reached [chapterId]'s end, which finishes it as reading to the end does. */
    fun finishChapter(chapterId: Long)

    /** [chapterId]'s paragraphs with no renderer to ask, read as the reader reads them; throws when it cannot. */
    suspend fun loadParagraphs(chapterId: Long): List<String>

    /** Speech has moved on to [chapterId] by itself, which a reader of the series follows. */
    fun movedTo(chapterId: Long)

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

    /** [stopReason] is set only on the Stopped a stop by itself publishes. */
    fun publish(
        playback: TtsPlayback,
        chapterId: Long?,
        title: String,
        paragraph: Int,
        paragraphCount: Int,
        stopReason: ReadAloudStop? = null,
    )

    /** True once when the sleep timer is set to stop at this chapter's end instead of reading on. */
    fun takeStopAtChapterEnd(): Boolean

    /** Takes the notification down, unless another reader has connected since. */
    fun release()
}

/**
 * Read-aloud's speech: which paragraph is spoken, over [NovelTtsEngine] for the voice and a
 * [ReadAloudSurface] for the text while a reader lends one. Speech outlives the reader, so the surface
 * comes and goes through [attach] and [detach], and a chapter reached with none is read through
 * [ReadAloudNavigation.loadParagraphs]. Everything runs on [scope], so call from its thread; engine
 * callbacks arrive on a binder thread and are moved onto it.
 */
class ReadAloudController(
    private val scope: CoroutineScope,
    private val preferences: NovelPreferences,
    private val createEngine: (enginePackage: String, onInit: (ready: Boolean) -> Unit) -> NovelTtsEngine,
    private val navigation: ReadAloudNavigation,
    private val transport: ReadAloudTransport,
    /** Called once speech has stopped, whether a caller stopped it or it came to a stop by itself. */
    private val onStopped: () -> Unit = {},
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

    /** Words before each paragraph of [paragraphs], then the chapter's total, for [ReadAloudState.progress]. */
    private var wordsBefore: LongArray = LongArray(1)
    private var position: ReadAloudPosition? = null

    /** The paragraph last saved as the reader's place, so a sentence step or a resume does not write it again. */
    private var saved: ReadAloudPosition? = null

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

    /** The chapter being loaded to carry on in, which holds every command that would act on the last one. */
    private var pendingChapter: Long? = null

    /** The load of [pendingChapter], its own job so a pause does not cancel it. */
    private var loadJob: Job? = null

    /** Raised by every speak and every command, so a finish from speech since replaced is ignored. */
    private var token = 0
    private var job: Job? = null

    /** Whether the paragraph being spoken had anything to say, and whether the voice has begun saying it. */
    private var speaking = false
    private var pieceStarted = false

    /** Paragraphs in a row the voice ended without starting, which an engine gone quiet produces. */
    private var silentParagraphs = 0
    private var rebuiltForSilence = false

    /** Whether this chapter had anything to say, and whether any of it was heard. */
    private var speakableInChapter = false
    private var spokeInChapter = false

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

    /** Ignored while the next chapter opens, as a [step] is: the paragraphs are still the finished chapter's. */
    fun seekToParagraph(index: Int) {
        if (pendingChapter != null || chapterId == null || paragraphs.isEmpty()) return
        command { playAt(index.coerceIn(0, paragraphs.lastIndex)) }
    }

    fun stop() = stop(reason = null)

    private fun stop(reason: ReadAloudStop?) {
        token++
        job?.cancel()
        job = null
        loadJob?.cancel()
        loadJob = null
        pendingChapter = null
        awaitingInit = false
        engine?.stop()
        chapterId = null
        paragraphs = emptyList()
        position = null
        saved = null
        sentence = null
        sentences = emptyList()
        unplaced = false
        playback = TtsPlayback.Stopped
        surface?.highlight(null)
        publish(reason)
        onStopped()
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

    /**
     * The renderer started over: the same text laid out again (a
     * rotation, a mode switch, a setting reload) or a reader that has just joined, where the paragraphs
     * may differ from the ones speech holds and speech already going must not restart. While the next
     * chapter loads there is nothing to place: the load takes up whatever the renderer holds by then.
     */
    fun onRendererLanded() {
        if (playback == TtsPlayback.Stopped || pendingChapter != null) return
        unplaced = true
        // Drawn as soon as the renderer can, since a layout that did not shift needs no relocation.
        surface?.highlight(position, sentence, follows = playback == TtsPlayback.Playing)
        scope.launch { relocate() }
    }

    /** The renderer's window gained or lost chapters, which can bring in the one being read. */
    fun onWindowChanged() {
        // Only while unplaced: the page rebuilds a chapter's paragraph map for a question after any change.
        if (unplaced) scope.launch { relocate() }
    }

    /**
     * One paragraph, or one sentence while sentences are spoken, crossing into the neighbouring paragraph
     * past either end: forward to its first sentence, back to its last. With nothing to speak before it,
     * a step back starts the paragraph again.
     */
    private fun step(delta: Int) {
        // While the next chapter opens, the position is still the last chapter's end, so a step would finish
        // that chapter and open the next one again; the landing starts the new chapter, as play() leaves it.
        if (pendingChapter != null) return
        val at = position ?: return readFromHere()
        val target = sentenceIndex + delta
        command {
            val back = if (delta < 0) speakableBefore(at.paragraph) else null
            when {
                target in sentences.indices -> playAt(at.paragraph, fromSentence = target)
                delta > 0 -> playAt(at.paragraph + 1)
                back == null -> playAt(at.paragraph)
                sentences.isEmpty() -> playAt(back)
                else -> playAt(back, fromSentence = LAST_SENTENCE)
            }
        }
    }

    /**
     * The nearest paragraph before [index] with something to speak. One with nothing, such as a scene
     * break, finishes at once and speech would carry on forward to where the step left.
     */
    private fun speakableBefore(index: Int): Int? = (index - 1 downTo 0).firstOrNull { i ->
        val text = paragraphs.getOrNull(i) ?: return@firstOrNull false
        engine?.pieces(text, bySentence = false)?.isEmpty() != true
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
        takeUp(chapter)
        unplaced = false
        speakableInChapter = false
        spokeInChapter = false
        if (chapter.isEmpty()) return endChapter()
        playAt(index.coerceIn(0, chapter.lastIndex))
    }

    /** Speaks paragraph [index] from sentence [fromSentence], or from its last for [LAST_SENTENCE]. */
    private suspend fun playAt(index: Int, fromSentence: Int = 0) {
        val id = chapterId ?: return stop()
        if (index > paragraphs.lastIndex) return endChapter()
        moveTo(id, index, fromSentence)
        savePosition()
        setPlayback(TtsPlayback.Playing)
        speakCurrent()
    }

    /** Every move to a paragraph, so no sentence state of the one before is carried into it. */
    private fun moveTo(id: Long, index: Int, fromSentence: Int = 0) {
        position = ReadAloudPosition(id, index)
        sentence = null
        sentences = emptyList()
        sentenceIndex = fromSentence
    }

    private suspend fun endChapter() {
        val ended = chapterId ?: return stop()
        // A chapter the voice said none of was not heard, so it is neither finished nor passed.
        if (speakableInChapter && !spokeInChapter) return stop(ReadAloudStop.EngineSilent)
        // First, since neither stopping nor carrying on below moves a screen that would finish it.
        navigation.finishChapter(ended)
        if (transport.takeStopAtChapterEnd()) return stop()
        if (!preferences.readerTtsAutoPageAdvance().get()) return stop()
        val next = navigation.chapterAfter(ended) ?: return stop()
        // A renderer holding a window already has it, and carrying on there keeps the reader's scroll.
        val held = ask { paragraphs(next) }
        if (held != null) {
            navigation.movedTo(next)
            return beginChapter(next, held, 0)
        }
        pendingChapter = next
        loadJob = scope.launch { loadNext(next) }
    }

    /**
     * Reads [next] with no renderer, on its own job: a pause while it loads holds the chapter at its first
     * paragraph rather than cancelling the load. A renderer that came to hold it meanwhile is spoken from.
     */
    private suspend fun loadNext(next: Long) {
        val loaded = try {
            withTimeout(LOAD_TIMEOUT) { navigation.loadParagraphs(next) }
        } catch (e: TimeoutCancellationException) {
            return stop(ReadAloudStop.LoadTimedOut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Read aloud could not load chapter $next" }
            return stop(ReadAloudStop.LoadFailed)
        }
        if (pendingChapter != next) return
        val shown = ask { paragraphs(next) } ?: loaded
        navigation.movedTo(next)
        if (playback == TtsPlayback.Paused) {
            pendingChapter = null
            holdAt(next, shown)
        } else {
            command { beginChapter(next, shown, 0) }
        }
    }

    /** Paused across a chapter handoff: take the new chapter up without speaking it. */
    private fun holdAt(id: Long, chapter: List<String>) {
        chapterId = id
        takeUp(chapter)
        unplaced = false
        speakableInChapter = false
        spokeInChapter = false
        moveTo(id, 0)
        surface?.highlight(position)
        publish()
    }

    private fun speakCurrent() {
        val at = position ?: return
        val bySentence = preferences.readerTtsHighlight().get() && preferences.readerTtsHighlightSentence().get()
        // Marked sentence by sentence as each starts, so marking the paragraph first would flash all of it.
        if (!bySentence) surface?.highlight(at)
        val speaker = engine ?: buildEngine() ?: return
        if (!engineReady) {
            awaitingInit = true
            return
        }
        val spoken = ++token
        val pieces = speaker.pieces(paragraphs[at.paragraph], bySentence)
        speaking = pieces.isNotEmpty()
        pieceStarted = false
        if (speaking) speakableInChapter = true
        sentences = if (bySentence) pieces else emptyList()
        // Clamped for LAST_SENTENCE too, since a paragraph with nothing to speak has no last piece.
        val first = (if (sentenceIndex == LAST_SENTENCE) pieces.lastIndex else sentenceIndex)
            .coerceIn(0, maxOf(0, pieces.lastIndex))
        sentenceIndex = first
        speaker.speak(
            pieces = pieces.drop(first),
            onPieceStart = { index -> scope.launch { onPieceStart(spoken, first + index, bySentence) } },
            onDone = { scope.launch { onParagraphDone(spoken) } },
        )
    }

    private fun onPieceStart(spoken: Int, index: Int, bySentence: Boolean) {
        if (spoken != token) return
        pieceStarted = true
        spokeInChapter = true
        silentParagraphs = 0
        if (bySentence) onSentenceStart(spoken, index)
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
        val at = position?.paragraph ?: 0
        if (speaking && !pieceStarted && ++silentParagraphs >= SILENT_PARAGRAPHS) {
            if (rebuiltForSilence) return stop(ReadAloudStop.EngineSilent)
            // Once, on the paragraph it went quiet on: a voice the system restarted under it comes back.
            rebuiltForSilence = true
            silentParagraphs = 0
            discardEngine()
            return command { playAt(at) }
        }
        command { playAt(at + 1) }
    }

    /** Finds [position] in the renderer's layout, or leaves it unplaced while the window lacks its chapter. */
    private suspend fun relocate() {
        val id = chapterId ?: return
        val laidOut = ask { paragraphs(id) }?.takeIf { it.isNotEmpty() } ?: return
        // Read after the query, since speech may have moved on while the renderer answered.
        val at = position?.takeIf { it.chapterId == id && unplaced } ?: return
        unplaced = false
        val spokenText = paragraphs.getOrNull(at.paragraph)
        // By how far through the chapter's words each sits, which two renderers' counts of one chapter share
        // where their paragraph indices need not.
        val spokenShare = shareOf(wordsBefore, at.paragraph)
        val laidOutWords = wordsBeforeOf(laidOut)
        val nearest = compareBy<Int> { abs(shareOf(laidOutWords, it) - spokenShare) }
        val index = laidOut.indices.filter { laidOut[it] == spokenText }.minWithOrNull(nearest)
            ?: laidOut.indices.minWith(nearest)
        takeUp(laidOut)
        // The sentence state holds only in the text it was taken from, which a different match is not.
        if (laidOut[index] == spokenText) position = ReadAloudPosition(id, index) else moveTo(id, index)
        surface?.highlight(position, sentence, follows = playback == TtsPlayback.Playing)
        publish()
    }

    /**
     * The engine now in use, or null when its start failed before the constructor returned: on an
     * immediate scope that failure has already discarded this generation and stopped playback.
     */
    private fun buildEngine(): NovelTtsEngine? {
        val generation = ++engineGeneration
        enginePackage = preferences.readerTtsEngine().get()
        engineReady = false
        val built = createEngine(enginePackage) { ready -> scope.launch { onEngineInit(generation, ready) } }
        if (generation != engineGeneration) {
            built.shutdown()
            return null
        }
        engine = built
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

        /** Paragraphs the voice may end unsaid in a row before the engine is taken for gone quiet. */
        const val SILENT_PARAGRAPHS = 3

        /** How long speech waits on the next chapter, which is a network fetch and a plugin starting. */
        val LOAD_TIMEOUT = 90.seconds
    }

    private fun takeUp(chapter: List<String>) {
        paragraphs = chapter
        wordsBefore = wordsBeforeOf(chapter)
    }

    private fun wordsBeforeOf(chapter: List<String>): LongArray {
        val before = LongArray(chapter.size + 1)
        chapter.forEachIndexed { i, text -> before[i + 1] = before[i] + NovelWords.count(text) }
        return before
    }

    private fun shareOf(before: LongArray, index: Int): Double =
        before.last().takeIf { it > 0 }?.let { before[index.coerceIn(0, before.lastIndex)].toDouble() / it } ?: 0.0

    /**
     * The spoken paragraph as the reader's place, whether or not the screen follows it: as the share of the
     * chapter's words before it, which a reopen by percent lands on screen while text height follows words.
     */
    private fun savePosition() {
        val at = position?.takeIf { it != saved } ?: return
        saved = at
        val words = wordsBefore.last()
        navigation.savePosition(at.chapterId, if (words > 0) wordsBefore[at.paragraph].toDouble() / words else 0.0)
    }

    private fun publish(stopReason: ReadAloudStop? = null) {
        val progress = position?.let { at ->
            val paragraph = at.paragraph.coerceIn(0, paragraphs.size)
            ReadAloudProgress(at.chapterId, paragraph, wordsBefore[paragraph], wordsBefore.last(), paragraphs.size)
        }
        state.value = ReadAloudState(playback, progress)
        val title = chapterId?.let(navigation::titleOf).orEmpty()
        transport.publish(playback, chapterId, title, position?.paragraph ?: 0, paragraphs.size, stopReason)
    }
}
