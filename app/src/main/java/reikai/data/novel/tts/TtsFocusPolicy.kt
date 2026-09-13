package reikai.data.novel.tts

import reikai.domain.novel.tts.TtsPlayback

/**
 * Audio focus decisions for read-aloud, free of Android types; [NovelTtsService] carries them out. Every
 * loss pauses, ducking included, since speech under other audio cannot be followed. Only a pause a
 * transient loss caused resumes on the gain, and anything the user does in between cancels that.
 */
class TtsFocusPolicy {

    enum class Change { Gain, Loss, LossTransient, LossTransientCanDuck }

    enum class Focus { Keep, Request, Abandon }

    enum class Response { Nothing, Pause, PauseAndAbandon, Resume }

    private var playback = TtsPlayback.Stopped
    private var holding = false
    private var resumeOnGain = false

    /** Called with every playback the session shows; only a change of it decides anything. */
    fun onPlayback(value: TtsPlayback): Focus {
        if (value == playback) return Focus.Keep
        playback = value
        if (value == TtsPlayback.Playing) {
            resumeOnGain = false
            return if (holding) Focus.Keep else Focus.Request.also { holding = true }
        }
        if (value == TtsPlayback.Stopped) resumeOnGain = false
        // A pause a transient loss caused keeps focus, or the gain that resumes it never arrives.
        return if (holding && !resumeOnGain) Focus.Abandon.also { holding = false } else Focus.Keep
    }

    /** The answer to a [Focus.Request]: playback does not go on without focus. */
    fun onRequestResult(granted: Boolean): Response {
        if (granted) return Response.Nothing
        holding = false
        return Response.Pause
    }

    fun onFocusChange(change: Change): Response = when (change) {
        Change.Gain -> if (resumeOnGain && playback == TtsPlayback.Paused) {
            resumeOnGain = false
            Response.Resume
        } else {
            Response.Nothing
        }
        Change.Loss -> {
            holding = false
            resumeOnGain = false
            Response.PauseAndAbandon
        }
        Change.LossTransient, Change.LossTransientCanDuck -> if (playback == TtsPlayback.Playing) {
            resumeOnGain = true
            Response.Pause
        } else {
            Response.Nothing
        }
    }

    /** A pause the user or the sleep timer asked for, which no later gain may undo. */
    fun onUserPause(): Focus {
        resumeOnGain = false
        return if (holding && playback != TtsPlayback.Playing) Focus.Abandon.also { holding = false } else Focus.Keep
    }
}
