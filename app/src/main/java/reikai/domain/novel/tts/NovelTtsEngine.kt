package reikai.domain.novel.tts

/**
 * The reader's text-to-speech voice layer, kept behind an interface so the default Android
 * [android.speech.tts.TextToSpeech] backend can be swapped for an offline neural engine later
 * without touching the reader, the WebView bridge, or the playback service.
 *
 * The contract is deliberately small: speak one chunk of text and report when it finishes. The
 * "what to speak next" and the highlight live in the WebView's `core.js`; this only produces sound.
 */
interface NovelTtsEngine {

    /** Whether the backend has finished initializing and can speak. */
    val isReady: Boolean

    /** Installed engines on the device, for the settings engine picker. */
    fun availableEngines(): List<TtsEngineInfo>

    /** Voices the active engine offers, for the settings voice picker. */
    fun availableVoices(): List<TtsVoice>

    fun setVoice(voiceName: String)
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)

    /** Speak [text]; [onDone] fires once when the utterance finishes (or fails). Replaces any
     *  utterance already in progress. */
    fun speak(text: String, onDone: () -> Unit)

    /** Stop the current utterance immediately, without firing its [speak] `onDone`. */
    fun stop()

    /** Release backend resources. The engine is unusable afterward. */
    fun shutdown()
}

/** An installed TTS engine: [packageName] feeds the backend constructor, [label] is user-facing. */
data class TtsEngineInfo(val packageName: String, val label: String)

/** A voice within an engine. [name] is the stable id passed back to select it; [displayName] and
 *  [locale] (a BCP-47 language tag) drive the picker UI and its language grouping. */
data class TtsVoice(val name: String, val displayName: String, val locale: String)
