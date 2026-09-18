package reikai.domain.novel.tts

/**
 * The reader's text-to-speech voice layer, kept behind an interface so the default Android
 * [android.speech.tts.TextToSpeech] backend can be swapped for an offline neural engine later
 * without touching the reader, its renderers, or the playback service.
 *
 * The contract is deliberately small: speak one chunk of text and report when it finishes. The
 * "what to speak next" and the highlight live in `ReadAloudController`; this only produces sound.
 */
interface NovelTtsEngine {

    /** Installed engines on the device, for the settings engine picker. */
    fun availableEngines(): List<TtsEngineInfo>

    /** Voices the active engine offers, for the settings voice picker. */
    fun availableVoices(): List<TtsVoice>

    fun setVoice(voiceName: String)
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)

    /**
     * [text] as the pieces this engine can speak, each within its input limit. [bySentence] gives every
     * sentence a piece of its own, so the one being spoken can be marked and stepped to.
     */
    fun pieces(text: String, bySentence: Boolean): List<TtsPiece>

    /**
     * Speak [pieces] in order; [onDone] fires once when all of them finish (or fail). Replaces any
     * utterance already in progress. [onPieceStart] fires as each piece begins, with its index in [pieces].
     */
    fun speak(pieces: List<TtsPiece>, onPieceStart: (index: Int) -> Unit, onDone: () -> Unit)

    /** Stop the current utterance immediately, without firing its [speak] `onDone`. */
    fun stop()

    /** Release backend resources. The engine is unusable afterward. */
    fun shutdown()
}

/** Reader read-aloud playback state, shared by the controller, the floating control, and the media
 *  notification service. */
enum class TtsPlayback { Stopped, Playing, Paused }

/** An installed TTS engine: [packageName] feeds the backend constructor, [label] is user-facing. */
data class TtsEngineInfo(val packageName: String, val label: String)

/** A voice within an engine. [name] is the stable id passed back to select it; [displayName] and
 *  [locale] (a BCP-47 language tag) drive the picker UI and its language grouping. */
data class TtsVoice(val name: String, val displayName: String, val locale: String) {
    /** The base language (`en` of `en-US`) the language filter works in. */
    val baseLanguage: String get() = locale.substringBefore('-')
}

/** Base language codes the voices span, in first-seen order. */
fun List<TtsVoice>.baseLanguages(): List<String> = map { it.baseLanguage }.filter { it.isNotBlank() }.distinct()

/**
 * The voices in [languages], counting only the ones these voices offer. None of them, the preference's
 * empty default included, is no filter: one kept from another engine would leave nothing to pick. It is
 * not cleared on a switch, so switching back brings it back.
 */
fun List<TtsVoice>.inLanguages(languages: Set<String>): List<TtsVoice> {
    val offered = languages intersect baseLanguages().toSet()
    return if (offered.isEmpty()) this else filter { it.baseLanguage in offered }
}
