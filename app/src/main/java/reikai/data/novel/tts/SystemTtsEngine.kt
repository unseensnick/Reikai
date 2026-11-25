package reikai.data.novel.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsPiece
import reikai.domain.novel.tts.TtsUtteranceSplitter
import reikai.domain.novel.tts.TtsVoice
import java.util.Locale

/**
 * [NovelTtsEngine] backed by Android's [TextToSpeech]. Initialization is asynchronous, so callers
 * must wait for [onInit] before [speak]; [onInit] reports failure too, since an
 * engine that never starts would otherwise leave the caller waiting. One paragraph (queued as several
 * utterances) is in flight at a time, each [speak] flushing the last, so one [SpokenSlot] is enough.
 * [TextToSpeech] fires its progress callbacks on a binder thread, and the caller marshals to the main
 * thread itself before touching the renderer.
 */
class SystemTtsEngine(
    context: Context,
    enginePackage: String,
    private val onInit: (ready: Boolean) -> Unit,
) : NovelTtsEngine {

    @Volatile
    private var isReady: Boolean = false

    /** The paragraph being spoken, so a piece's start can be told where it sits. Replaced by every speak. */
    private val spoken = SpokenSlot()

    /** Raised by every speak, so a callback from pieces already flushed names a paragraph no longer spoken. */
    private var generation = 0

    /** The voice's locale, kept once known: asking the engine is a call into its process, measured at 10 to
     *  28ms on the main thread, and every paragraph needs it to split at sentence ends. */
    @Volatile
    private var voiceLocale: Locale? = null

    private val tts: TextToSpeech = TextToSpeech(
        context.applicationContext,
        { status ->
            isReady = status == TextToSpeech.SUCCESS
            onInit(isReady)
        },
        enginePackage.ifBlank { null },
    ).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val current = spoken.value ?: return
                val index = current.indexOf(utteranceId) ?: return
                current.onPieceStart(index)
            }

            // A paragraph can be several utterances, so only the last one finishes the caller's.
            override fun onDone(utteranceId: String?) {
                val current = spoken.value ?: return
                if (current.indexOf(utteranceId) == current.pieces.lastIndex && spoken.claim(current)) current.onDone()
            }

            // Checked as onStart and onDone are, or an error from a piece the last speak flushed would end
            // the paragraph being spoken now.
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = abortIfCurrent(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = abortIfCurrent(utteranceId)
        })
    }

    private fun abortIfCurrent(utteranceId: String?) {
        val current = spoken.value ?: return
        if (current.indexOf(utteranceId) != null) abort(current)
    }

    /** Ends the paragraph early. Whichever piece failed, the rest are dropped rather than read out of
     *  context, and the caller is told so it moves on instead of waiting for a callback never coming.
     *  The slot is cleared first, so the stop's own callbacks cannot come back around, and the stop runs
     *  before a next speak can take the slot, so it never flushes that paragraph. */
    private fun abort(current: SpokenParagraph) {
        if (spoken.claim(current) { runCatching { tts.stop() } }) current.onDone()
    }

    override fun availableEngines(): List<TtsEngineInfo> =
        runCatching { tts.engines }.getOrNull().orEmpty()
            .map { TtsEngineInfo(it.name, it.label) }
            .sortedBy { it.label }

    override fun availableVoices(): List<TtsVoice> =
        runCatching { tts.voices }.getOrNull().orEmpty()
            .map { TtsVoice(it.name, "${it.locale.displayName} (${it.name})", it.locale.toLanguageTag()) }
            .sortedBy { it.displayName }

    override fun setVoice(voiceName: String) {
        // Asked for again on the next paragraph, whichever voice ends up speaking it.
        voiceLocale = null
        if (voiceName.isBlank()) return
        val voice = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == voiceName } ?: return
        runCatching { tts.voice = voice }
        voiceLocale = voice.locale
    }

    override fun setRate(rate: Float) {
        tts.setSpeechRate(rate.coerceIn(0.1f, 5.0f))
    }

    override fun setPitch(pitch: Float) {
        tts.setPitch(pitch.coerceIn(0.1f, 5.0f))
    }

    // An utterance past the engine's own maximum fails, often later through onError rather than as a
    // refusal at speak, so a long paragraph goes out as several and only the last completes the caller's.
    override fun pieces(text: String, bySentence: Boolean): List<TtsPiece> = TtsUtteranceSplitter.pieces(
        text = text,
        maxLength = TextToSpeech.getMaxSpeechInputLength(),
        locale = voiceLocale
            ?: runCatching { tts.voice?.locale }.getOrNull()?.also { voiceLocale = it }
            ?: Locale.getDefault(),
        bySentence = bySentence,
    )

    override fun speak(pieces: List<TtsPiece>, onPieceStart: (index: Int) -> Unit, onDone: () -> Unit) {
        if (!isReady || pieces.isEmpty()) {
            onDone()
            return
        }
        val current = SpokenParagraph(++generation, pieces, onPieceStart, onDone)
        spoken.replace(current)
        pieces.forEachIndexed { index, piece ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            // A refusal is reported as a return value and never reaches the listener, so without this
            // nothing would clear the callback and playback would stop here for good.
            if (tts.speak(piece.text, queueMode, null, current.idOf(index)) == TextToSpeech.ERROR) {
                abort(current)
                return
            }
        }
    }

    override fun stop() {
        spoken.replace(null)
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        spoken.replace(null)
        runCatching { tts.shutdown() }
    }
}

/** A paragraph's pieces under one generation. An utterance id names both, so a stale one matches nothing. */
internal class SpokenParagraph(
    val generation: Int,
    val pieces: List<TtsPiece>,
    val onPieceStart: (index: Int) -> Unit,
    val onDone: () -> Unit,
) {
    fun idOf(index: Int) = "$UTTERANCE_PREFIX$generation:$index"

    fun indexOf(utteranceId: String?): Int? {
        val (idGeneration, index) = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.split(':')
            ?.takeIf { it.size == 2 } ?: return null
        if (idGeneration.toIntOrNull() != generation) return null
        return index.toIntOrNull()?.takeIf { it in pieces.indices }
    }

    private companion object {
        const val UTTERANCE_PREFIX = "reikai-novel-tts:"
    }
}

/**
 * Who is being spoken, read on the binder thread and replaced on the main one. A paragraph carries its
 * own done callback and is claimed whole, so a callback that read the slot before a speak replaced it
 * can only finish the paragraph it read, never the next one.
 */
internal class SpokenSlot {
    private var current: SpokenParagraph? = null

    val value: SpokenParagraph? get() = synchronized(this) { current }

    fun replace(paragraph: SpokenParagraph?) = synchronized(this) { current = paragraph }

    /** Empties the slot if [paragraph] is still in it, running [whileHeld] before a speak can refill it. */
    fun claim(paragraph: SpokenParagraph, whileHeld: () -> Unit = {}): Boolean = synchronized(this) {
        if (current !== paragraph) return false
        current = null
        whileHeld()
        true
    }
}
