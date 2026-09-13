package reikai.data.novel.tts

import android.support.v4.media.session.PlaybackStateCompat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.data.novel.tts.NovelTtsSession.Capability
import reikai.domain.novel.tts.TtsPlayback

class NovelTtsSessionTest {

    private val stepAndSeek = PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO

    @AfterEach
    fun tearDown() {
        NovelTtsSession.reset()
    }

    @Test
    fun `a state published without a capability offers only play, pause and stop`() {
        NovelTtsSession.State(TtsPlayback.Playing, "").capability shouldBe Capability.PlayPauseStop
    }

    @Test
    fun `an owner that only plays offers the system no step or seek`() {
        mediaSessionActions(Capability.PlayPauseStop) and stepAndSeek shouldBe 0L
    }

    @Test
    fun `an owner reading by paragraph offers the system step and seek`() {
        mediaSessionActions(Capability.Paragraphs(index = 1, count = 3)) and stepAndSeek shouldBe stepAndSeek
    }

    @Test
    fun `an owner that only plays cannot hold the end of chapter timer`() {
        NovelTtsSession.sleepTimer.setEndOfChapter()

        NovelTtsSession.publish(NovelTtsSession.State(TtsPlayback.Playing, "title"))

        NovelTtsSession.sleepTimer.timer.value shouldBe SleepTimer.Off
    }

    @Test
    fun `an owner reading by paragraph holds the end of chapter timer`() {
        NovelTtsSession.sleepTimer.setEndOfChapter()

        NovelTtsSession.publish(NovelTtsSession.State(TtsPlayback.Playing, "title", Capability.Paragraphs(0, 3)))

        NovelTtsSession.sleepTimer.timer.value shouldBe SleepTimer.EndOfChapter
    }

    @Test
    fun `publishing stopped clears the sleep timer`() {
        NovelTtsSession.sleepTimer.setEndOfChapter()

        NovelTtsSession.publish(NovelTtsSession.State(TtsPlayback.Stopped, "", Capability.Paragraphs(0, 0)))

        NovelTtsSession.sleepTimer.timer.value shouldBe SleepTimer.Off
    }
}
