package reikai.presentation.reader

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import reikai.data.novel.tts.SystemTtsEngine
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsVoice
import reikai.domain.novel.tts.baseLanguages
import reikai.domain.novel.tts.inLanguages
import tachiyomi.core.common.util.lang.withIOContext
import java.util.Locale

/**
 * The speech engines installed and the voices of the one in use, for a voice picker. The labels are
 * built here so the reader sheet and Settings name the same choice the same way.
 */
data class TtsOptions(
    val engines: List<TtsEngineInfo> = emptyList(),
    val voices: List<TtsVoice> = emptyList(),
) {
    /** The engines, the system's own first under [defaultLabel]: package name to label. */
    fun engineEntries(defaultLabel: String): Map<String, String> =
        mapOf("" to defaultLabel) + engines.associate { it.packageName to it.label }

    /** [engine] by its label, or its package name once it is uninstalled. */
    fun engineLabel(engine: String, defaultLabel: String): String = engineEntries(defaultLabel)[engine] ?: engine

    /** The base languages the voices span, code to name, named in the device's language and sorted by name. */
    fun languageNames(): Map<String, String> = voices.baseLanguages()
        .map { code -> code to Locale.forLanguageTag(code).displayLanguage.ifBlank { code } }
        .sortedBy { it.second }
        .toMap()

    /** The picked [languages] by name, in [languageNames] order; empty when none is picked. */
    fun languagesLabel(languages: Set<String>): String =
        languageNames().filterKeys { it in languages }.values.joinToString()

    /** The voices in [languages], the engine's own first under [defaultLabel]: voice id to name. */
    fun voiceEntries(languages: Set<String>, defaultLabel: String): Map<String, String> =
        mapOf("" to defaultLabel) + voices.inLanguages(languages).associate { it.name to it.displayName }

    /** [voice] by name, looked up in every voice: one picked before the language filter changed still plays. */
    fun voiceLabel(voice: String, defaultLabel: String): String =
        if (voice.isEmpty()) defaultLabel else voices.firstOrNull { it.name == voice }?.displayName ?: voice
}

/**
 * The installed engines and the voices of [engine]. Needs a live [SystemTtsEngine], which is bound
 * only while this is composed and rebuilt when the engine changes. Voices can arrive a little after
 * the engine reports ready, so the lists are polled briefly.
 */
@Composable
fun rememberTtsOptions(context: Context, engine: String): State<TtsOptions> =
    produceState(TtsOptions(), engine) {
        value = value.copy(voices = emptyList())
        val ready = CompletableDeferred<Boolean>()
        val tts = SystemTtsEngine(context, engine) { ready.complete(it) }
        try {
            if (!ready.await()) return@produceState
            for (attempt in 1..VOICE_POLLS) {
                value = withIOContext { TtsOptions(tts.availableEngines(), tts.availableVoices()) }
                if (value.voices.isNotEmpty()) break
                delay(VOICE_POLL_INTERVAL)
            }
            awaitCancellation()
        } finally {
            tts.shutdown()
        }
    }

private const val VOICE_POLLS = 12
private const val VOICE_POLL_INTERVAL = 300L
