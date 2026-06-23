package reikai.presentation.novel.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import reikai.data.novel.tts.NovelTtsService
import reikai.data.novel.tts.NovelTtsSession
import reikai.data.novel.tts.SystemTtsEngine
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.novel.tts.TtsVoice

/**
 * Drives read-aloud for the novel reader. The WebView's `core.js` owns "which paragraph and the
 * highlight"; this owns the voice. The loop: tap play -> we ask `core.js` to start -> it posts a
 * `speak` per paragraph -> we voice it with the [NovelTtsEngine] -> on each utterance's end we tell
 * `core.js` to advance (`tts.next()`), which highlights and posts the next `speak`.
 *
 * Lifecycle-owned by [NovelReaderScreenModel] (so it survives rotation); the WebView registers its
 * `evaluateJavascript` sink via [setEvalJs] and forwards `core.js` messages via [onWebMessage].
 *
 * Playback state is mirrored to [NovelTtsSession], which [NovelTtsService] renders as a media-style
 * notification + MediaSession (lock-screen / headset controls) and which keeps the process alive so
 * reading continues when the app is backgrounded.
 */
class NovelTtsController(
    private val context: Context,
    private val preferences: NovelPreferences,
    private val onChapterEnd: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _playback = MutableStateFlow(TtsPlayback.Stopped)
    val playback: StateFlow<TtsPlayback> = _playback.asStateFlow()

    private var engine: NovelTtsEngine? = null
    private var evalJs: ((String) -> Unit)? = null

    /** play() was requested before the engine finished its async init; honored on ready. */
    private var startPending = false

    /** auto-page-advance asked for the next chapter; play() once its document is up. */
    private var autoStartPending = false

    /** core.js `tts.start()` calls `this.stop()` first, posting a spurious `stop-speak`; swallow that
     *  one so it doesn't reset our just-set Playing state. */
    private var suppressStopOnce = false

    /** Now-playing chapter title for the media notification. */
    private var nowPlaying: String = ""

    fun setEvalJs(sink: (String) -> Unit) {
        evalJs = sink
    }

    fun clearEvalJs() {
        evalJs = null
    }

    /** Current chapter title, shown in the media notification. */
    fun setNowPlaying(title: String) {
        nowPlaying = title
        if (_playback.value != TtsPlayback.Stopped) publishSession()
    }

    private fun setPlayback(value: TtsPlayback) {
        _playback.value = value
        publishSession()
    }

    private fun publishSession() {
        NovelTtsSession.state.value = NovelTtsSession.State(_playback.value, nowPlaying)
    }

    /** Point the shared session's transport actions at this controller and bring up the foreground
     *  service (keep-alive + media notification). Idempotent. */
    private fun ensureSessionAndService() {
        NovelTtsSession.onPlay = ::play
        NovelTtsSession.onPause = ::pause
        NovelTtsSession.onStop = ::stop
        NovelTtsService.start(context)
    }

    private fun ensureEngine(): NovelTtsEngine =
        engine ?: SystemTtsEngine(context, preferences.readerTtsEngine().get()) {
            mainHandler.post {
                applyEngineSettings()
                if (startPending) {
                    startPending = false
                    issueStart()
                }
            }
        }.also { engine = it }

    private fun applyEngineSettings() {
        val e = engine ?: return
        e.setRate(preferences.readerTtsRate().get())
        e.setPitch(preferences.readerTtsPitch().get())
        e.setVoice(preferences.readerTtsVoice().get())
    }

    /** Re-apply rate/pitch/voice after a settings edit. An engine change needs a rebuild, which
     *  stops any current playback. */
    fun refreshSettings(engineChanged: Boolean) {
        if (engineChanged) {
            engine?.shutdown()
            engine = null
            startPending = false
            if (_playback.value != TtsPlayback.Stopped) stop()
        } else {
            applyEngineSettings()
        }
    }

    fun toggle() = if (_playback.value == TtsPlayback.Playing) pause() else play()

    fun play() {
        ensureSessionAndService()
        val e = ensureEngine()
        if (_playback.value == TtsPlayback.Paused) {
            setPlayback(TtsPlayback.Playing)
            evalJs?.invoke(JS_RESUME)
            return
        }
        setPlayback(TtsPlayback.Playing)
        if (e.isReady) issueStart() else startPending = true
    }

    private fun issueStart() {
        applyEngineSettings()
        suppressStopOnce = true
        evalJs?.invoke(JS_START)
    }

    fun pause() {
        if (_playback.value != TtsPlayback.Playing) return
        setPlayback(TtsPlayback.Paused)
        evalJs?.invoke(JS_PAUSE)
    }

    fun stop() {
        startPending = false
        autoStartPending = false
        suppressStopOnce = false
        setPlayback(TtsPlayback.Stopped)
        engine?.stop()
        evalJs?.invoke(JS_STOP)
    }

    /** A chapter's document finished loading (the bundled helper posts `reikai-ready`). Reset to
     *  stopped, unless auto-page-advance asked us to keep reading into this chapter. */
    fun onReaderReady() {
        engine?.stop()
        if (autoStartPending) {
            autoStartPending = false
            setPlayback(TtsPlayback.Stopped)
            play()
        } else {
            setPlayback(TtsPlayback.Stopped)
        }
    }

    /** Dispatch a TTS message `core.js` posted. Runs on the WebView's binder thread. */
    fun onWebMessage(type: String, json: JSONObject) {
        when (type) {
            "speak" -> engine?.speak(json.optString("data")) {
                mainHandler.post {
                    if (_playback.value == TtsPlayback.Playing) evalJs?.invoke(JS_NEXT)
                }
            }
            "pause-speak" -> engine?.stop()
            "stop-speak" -> {
                engine?.stop()
                // tts.start()'s internal cleanup posts one stop-speak; ignore it (we're starting, not
                // stopping). A real end-of-chapter stop-speak falls through to reset the state.
                if (suppressStopOnce) {
                    suppressStopOnce = false
                } else {
                    mainHandler.post { setPlayback(TtsPlayback.Stopped) }
                }
            }
            "next" -> if (json.optBoolean("autoStartTTS")) {
                autoStartPending = true
                mainHandler.post { onChapterEnd() }
            }
        }
    }

    fun availableEngines(): List<TtsEngineInfo> = ensureEngine().availableEngines()
    fun availableVoices(): List<TtsVoice> = ensureEngine().availableVoices()

    fun shutdown() {
        engine?.shutdown()
        engine = null
        evalJs = null
        // Drops the notification + stops the service, and clears the stale callbacks.
        NovelTtsSession.reset()
    }

    private companion object {
        const val JS_START = "if (window.reikaiTtsStart) reikaiTtsStart();"
        const val JS_NEXT = "if (window.tts) tts.next();"
        const val JS_PAUSE = "if (window.tts) tts.pause();"
        const val JS_RESUME = "if (window.tts) tts.resume();"
        const val JS_STOP = "if (window.tts) tts.stop();"
    }
}
