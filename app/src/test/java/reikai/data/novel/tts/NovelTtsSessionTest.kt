package reikai.data.novel.tts

import android.support.v4.media.session.PlaybackStateCompat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.tts.TtsPlayback

class NovelTtsSessionTest {

    private val stepAndSeek = PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO

    @AfterEach
    fun tearDown() {
        NovelTtsSession.reset()
    }

    @Test
    fun `read-aloud offers the system step and seek`() {
        MEDIA_SESSION_ACTIONS and stepAndSeek shouldBe stepAndSeek
    }

    @Test
    fun `reading on holds the end of chapter timer`() {
        NovelTtsSession.sleepTimer.setEndOfChapter()

        NovelTtsSession.publish(NovelTtsSession.State(TtsPlayback.Playing, "title", paragraph = 0, paragraphCount = 3))

        NovelTtsSession.sleepTimer.timer.value shouldBe SleepTimer.EndOfChapter
    }

    @Test
    fun `publishing stopped clears the sleep timer`() {
        NovelTtsSession.sleepTimer.setEndOfChapter()

        NovelTtsSession.publish(NovelTtsSession.State(TtsPlayback.Stopped, ""))

        NovelTtsSession.sleepTimer.timer.value shouldBe SleepTimer.Off
    }
}
