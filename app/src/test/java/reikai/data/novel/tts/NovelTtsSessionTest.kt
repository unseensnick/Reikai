package reikai.data.novel.tts

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.tts.ReadAloudStop
import reikai.domain.novel.tts.TtsPlayback

class NovelTtsSessionTest {

    @AfterEach
    fun tearDown() {
        NovelTtsSession.reset()
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

    // The teardown after a stop publishes Stopped again, which a reader of the state alone could see first.
    @Test
    fun `a stop's reason is still delivered once the teardown has published Stopped again`() = runTest {
        val delivered = mutableListOf<ReadAloudStop?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            NovelTtsSession.stops.collect { delivered += it.stopReason }
        }

        NovelTtsSession.publish(NovelTtsSession.State(TtsPlayback.Stopped, "", stopReason = ReadAloudStop.EngineSilent))
        NovelTtsSession.reset()

        delivered shouldBe listOf(ReadAloudStop.EngineSilent)
    }
}
