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
import tachiyomi.core.common.util.lang.withIOContext

/** The speech engines installed and the voices of the one in use, for a voice picker. */
data class TtsOptions(
    val engines: List<TtsEngineInfo> = emptyList(),
    val voices: List<TtsVoice> = emptyList(),
)

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
