package reikai.data.novel.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Silence played from this app while read-aloud speaks. Android hands media buttons to the session of
 * an app it hears playing, and the voice plays from the TTS engine's own process, so without this
 * `dumpsys media_session` reports no media button session and headset keys go elsewhere. A static
 * buffer of zeros looped by the track itself, so it costs no work once started.
 */
internal class TtsMediaButtonClaim {

    private var track: AudioTrack? = null

    fun hold(playing: Boolean) = if (playing) start() else release()

    private fun start() {
        if (track != null) return
        track = runCatching {
            val frames = SAMPLE_RATE / 2
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(frames * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .apply {
                    write(ShortArray(frames), 0, frames)
                    setLoopPoints(0, frames, -1)
                    play()
                }
        }.getOrNull()
    }

    fun release() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 8_000
    }
}
