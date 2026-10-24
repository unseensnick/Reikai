package reikai.presentation.reader

import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PageSlideTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    // A web turn waits on its page, so it can reach the slide after the reader left the screen.
    @Test
    fun aTurnAfterTheWindowLostItsSurfaceStillLands() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        instrumentation.waitForIdleSync()
        lateinit var content: View
        scenario.onActivity { content = it.webView }
        scenario.moveToState(Lifecycle.State.CREATED)
        awaitHidden(content)

        val ran = CountDownLatch(1)
        var thrown: Throwable? = null
        instrumentation.runOnMainSync {
            thrown = runCatching { PageSlide(content).turn(leftward = true) { ran.countDown() } }.exceptionOrNull()
        }

        assertEquals(null, thrown)
        assertTrue("the turn never ran", ran.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }

    /** A stopped activity's window is relaid out invisible, which is what releases its surface. */
    private fun awaitHidden(content: View) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var visibility = View.VISIBLE
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { visibility = content.windowVisibility }
            if (visibility != View.VISIBLE) return
            SystemClock.sleep(POLL_MS)
        }
        error("the window stayed visible after the activity stopped")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
