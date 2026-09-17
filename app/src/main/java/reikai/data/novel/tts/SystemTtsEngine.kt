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
 * must wait for [onInit] (or check [isReady]) before [speak]; [onInit] reports failure too, since an
 * engine that never starts would otherwise leave the caller waiting. One utterance is in flight at a
 * time (each [speak] flushes the previous), so a single pending callback slot is enough.
 * [TextToSpeech] fires its progress callbacks on a binder thread, and the caller marshals to the main
 * thread itself before touching the renderer.
 */
class SystemTtsEngine(
    context: Context,
    enginePackage: String,
    private val onInit: (ready: Boolean) -> Unit,
) : NovelTtsEngine {

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    private var pendingDone: (() -> Unit)? = null

    /** The paragraph being spoken, so a piece's start can be told where it sits. Replaced by every speak. */
    @Volatile
    private var spoken: Spoken? = null

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
                val current = spoken ?: return
                val index = current.indexOf(utteranceId) ?: return
                current.onPieceStart(index)
            }

            // A paragraph can be several utterances, so only the last one finishes the caller's.
            override fun onDone(utteranceId: String?) {
                val current = spoken ?: return
                if (current.indexOf(utteranceId) == current.pieces.lastIndex) fireDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = abort()
            override fun onError(utteranceId: String?, errorCode: Int) = abort()
        })
    }

    private fun fireDone() {
        val cb = pendingDone
        pendingDone = null
        spoken = null
        cb?.invoke()
    }

    /** Ends the paragraph early. Whichever piece failed, the rest are dropped rather than read out of
     *  context, and the caller is told so it moves on instead of waiting for a callback never coming.
     *  The slot is cleared first, so the stop's own callbacks cannot come back around. */
    private fun abort() {
        val cb = pendingDone ?: return
        pendingDone = null
        spoken = null
        runCatching { tts.stop() }
        cb()
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
        pendingDone = onDone
        val current = Spoken(++generation, pieces, onPieceStart)
        spoken = current
        pieces.forEachIndexed { index, piece ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            // A refusal is reported as a return value and never reaches the listener, so without this
            // nothing would clear the callback and playback would stop here for good.
            if (tts.speak(piece.text, queueMode, null, current.idOf(index)) == TextToSpeech.ERROR) {
                abort()
                return
            }
        }
    }

    override fun stop() {
        pendingDone = null
        spoken = null
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        pendingDone = null
        spoken = null
        runCatching { tts.shutdown() }
    }

    /** A paragraph's pieces under one generation. An utterance id names both, so a stale one matches nothing. */
    private class Spoken(
        val generation: Int,
        val pieces: List<TtsPiece>,
        val onPieceStart: (index: Int) -> Unit,
    ) {
        fun idOf(index: Int) = "$UTTERANCE_PREFIX$generation:$index"

        fun indexOf(utteranceId: String?): Int? {
            val (idGeneration, index) = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.split(':')
                ?.takeIf { it.size == 2 } ?: return null
            if (idGeneration.toIntOrNull() != generation) return null
            return index.toIntOrNull()?.takeIf { it in pieces.indices }
        }
    }

    private companion object {
        const val UTTERANCE_PREFIX = "reikai-novel-tts:"
    }
}
