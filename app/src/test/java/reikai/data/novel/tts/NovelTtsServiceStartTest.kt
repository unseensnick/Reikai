package reikai.data.novel.tts

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import reikai.domain.novel.tts.TtsPauseReason

/** Android can refuse the start itself, before the service runs, and speech must pause rather than crash. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class NovelTtsServiceStartTest(private val refusal: RuntimeException) {

    @After
    fun reset() {
        NovelTtsSession.onPause = {}
    }

    @Test
    fun aRefusedStartPausesSpeech() {
        var paused: TtsPauseReason? = null
        NovelTtsSession.onPause = { paused = it }
        val refusing = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun startForegroundService(service: Intent): ComponentName = throw refusal
        }

        NovelTtsService.start(refusing)
        shadowOf(Looper.getMainLooper()).idle()

        paused shouldBe TtsPauseReason.Refused
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun refusals() = listOf(
            arrayOf<Any>(ForegroundServiceStartNotAllowedException("from the background")),
            arrayOf<Any>(SecurityException("foreground not allowed as per app op")),
        )
    }
}
