package reikai.data.novel.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsVoice

/**
 * [NovelTtsEngine] backed by Android's [TextToSpeech]. Initialization is asynchronous, so callers
 * must wait for [onReady] (or check [isReady]) before [speak]; a speak before then no-ops its
 * `onDone` so the caller doesn't spin the chapter forward silently.
 *
 * One utterance is in flight at a time (each [speak] flushes the previous), so a single pending
 * callback slot is enough. [TextToSpeech] fires its progress callbacks on a binder thread; the
 * caller marshals to the main thread itself before touching the WebView.
 */
class SystemTtsEngine(
    context: Context,
    enginePackage: String,
    private val onReady: () -> Unit,
) : NovelTtsEngine {

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    private var pendingDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(
        context.applicationContext,
        { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) onReady()
        },
        enginePackage.ifBlank { null },
    ).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = fireDone()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = fireDone()
            override fun onError(utteranceId: String?, errorCode: Int) = fireDone()
        })
    }

    private fun fireDone() {
        val cb = pendingDone
        pendingDone = null
        cb?.invoke()
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
        if (voiceName.isBlank()) return
        val voice = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == voiceName } ?: return
        runCatching { tts.voice = voice }
    }

    override fun setRate(rate: Float) {
        tts.setSpeechRate(rate.coerceIn(0.1f, 5.0f))
    }

    override fun setPitch(pitch: Float) {
        tts.setPitch(pitch.coerceIn(0.1f, 5.0f))
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!isReady) {
            onDone()
            return
        }
        pendingDone = onDone
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun stop() {
        pendingDone = null
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        pendingDone = null
        runCatching { tts.shutdown() }
    }

    private companion object {
        const val UTTERANCE_ID = "reikai-novel-tts"
    }
}
