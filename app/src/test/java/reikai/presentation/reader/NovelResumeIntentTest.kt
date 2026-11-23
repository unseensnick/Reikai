package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import reikai.data.novel.tts.NovelTtsSession
import reikai.domain.novel.tts.TtsPlayback

/** Every resume (Continue, Stats, the details Resume) follows read aloud while it plays or is paused. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class NovelResumeIntentTest(private val playback: TtsPlayback, private val follows: Boolean) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf<Any>(TtsPlayback.Playing, true),
            arrayOf<Any>(TtsPlayback.Paused, true),
            arrayOf<Any>(TtsPlayback.Stopped, false),
        )
    }

    @After
    fun tearDown() = NovelTtsSession.reset()

    @Test
    fun `a resume follows read aloud only while it is going`() {
        NovelTtsSession.publish(NovelTtsSession.State(playback, title = ""))

        novelResumeIntent(RuntimeEnvironment.getApplication(), novelId = 1L, chapterId = 5L)
            .getBooleanExtra(EXTRA_FOLLOW_SESSION, false) shouldBe follows
    }
}
